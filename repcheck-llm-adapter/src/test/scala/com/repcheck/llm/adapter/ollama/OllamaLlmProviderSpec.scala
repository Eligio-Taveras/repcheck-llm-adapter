package com.repcheck.llm.adapter.ollama

import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec

import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax._

import org.http4s.Uri
import org.http4s.ember.client.EmberClientBuilder

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.stubbing.Scenario
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import repcheck.shared.models.llm.agentic.{LoopPolicy, Turn}
import repcheck.shared.models.llm.prompt.ChatMessage
import repcheck.shared.models.llm.tool.{ToolCall, ToolSpec}

import com.repcheck.llm.adapter.LlmSession
import com.repcheck.utils.errors.{RetryConfig, RetryWrapper}

/**
 * Wire-protocol conformance against a simulated Ollama. The append-only-transcript test is the provider half of the F2b
 * context-efficiency law: because the request prefix is byte-identical turn over turn, the server's KV prefix cache
 * (kept warm by `keep_alive`) can reuse it instead of re-evaluating the whole conversation.
 */
class OllamaLlmProviderSpec
    extends AsyncFlatSpec
    with AsyncIOSpec
    with Matchers
    with BeforeAndAfterAll
    with BeforeAndAfterEach {

  private val server = new WireMockServer(WireMockConfiguration.options().dynamicPort())

  override protected def beforeAll(): Unit = server.start()
  override protected def afterAll(): Unit  = server.stop()

  override protected def beforeEach(): Unit = {
    server.resetAll()
    ()
  }

  private val fastRetry    = RetryConfig(maxRetries = 1, initialBackoffMs = 1L, maxBackoffMs = 5L)
  private val policy       = LoopPolicy(maxIterations = 5, perCallTimeout = 5.seconds, tokenBudget = None)
  private val retryWrapper = new RetryWrapper[IO]((_, _, _, _, _, _) => IO.unit)
  private val echoSpec     = ToolSpec("echo", "echoes.", Json.obj(), Json.obj(), Json.obj(), Json.obj())

  private def config(requestTimeout: FiniteDuration = 5.seconds): OllamaConfig =
    OllamaConfig(
      baseUri = Uri.unsafeFromString(server.baseUrl()),
      model = "test-model",
      numCtx = 4096,
      keepAlive = 60.seconds,
      requestTimeout = requestTimeout,
      retry = fastRetry,
    )

  private def withSession[A](cfg: OllamaConfig, pol: LoopPolicy)(use: LlmSession[IO] => IO[A]): IO[A] =
    EmberClientBuilder.default[IO].build.use { client =>
      new OllamaLlmProvider[IO](client, cfg, retryWrapper).open("sys", List(echoSpec), pol).use(use)
    }

  private val assistantToolCall =
    """{"role":"assistant","content":"","tool_calls":[{"function":{"name":"echo","arguments":{"text":"hi"}}}]}"""

  private def replyBody(promptEval: Int = 10, evalCount: Int = 5): String =
    s"""{"message":$assistantToolCall,"done":true,"prompt_eval_count":$promptEval,"eval_count":$evalCount}"""

  private def stubOk(): Unit = {
    server.stubFor(post(urlEqualTo("/api/chat")).willReturn(okJson(replyBody())))
    ()
  }

  private def requestBody(index: Int): Json = {
    val raw = server.findAll(postRequestedFor(urlEqualTo("/api/chat"))).get(index).getBodyAsString
    parse(raw).fold(e => fail(s"request $index was not JSON: $e"), identity)
  }

  private def messagesOf(body: Json): List[Json] =
    body.hcursor.get[List[Json]]("messages").fold(e => fail(s"no messages: $e"), identity)

  "respond" should "map a tool-calling reply onto a Turn" in {
    stubOk()
    withSession(config(), policy)(_.exchange(List(ChatMessage("user", "go")))).asserting { turn =>
      turn shouldBe Turn(0, List(ToolCall("echo", Json.obj("text" -> "hi".asJson))), Nil)
    }
  }

  it should "send the documented request shape — model, system-first messages, function tools, num_ctx, keep_alive" in {
    stubOk()
    withSession(config(), policy)(_.exchange(List(ChatMessage("user", "go")))).asserting { _ =>
      val body = requestBody(0)
      body.hcursor.get[String]("model") shouldBe Right("test-model")
      body.hcursor.get[Boolean]("stream") shouldBe Right(false)
      body.hcursor.downField("options").get[Int]("num_ctx") shouldBe Right(4096)
      body.hcursor.get[String]("keep_alive") shouldBe Right("60s")
      body.hcursor.downField("tools").downArray.downField("function").get[String]("name") shouldBe Right("echo")
      messagesOf(body).map(_.hcursor.get[String]("role")) shouldBe List(Right("system"), Right("user"))
    }
  }

  it should "keep the wire transcript append-only across turns — the stable prefix is byte-identical" in {
    stubOk()
    withSession(config(), policy) { session =>
      session.exchange(List(ChatMessage("user", "go"))) *>
        session.exchange(List(ChatMessage("tool", "result")))
    }.asserting { _ =>
      val first  = messagesOf(requestBody(0))
      val second = messagesOf(requestBody(1))
      second.take(first.size) shouldBe first // the prior request is a verbatim prefix — cacheable
      second.drop(first.size) shouldBe List(
        parse(assistantToolCall).fold(e => fail(s"bad fixture: $e"), identity), // assistant reply, verbatim
        Json.obj("role" -> "tool".asJson, "content" -> "result".asJson), // then only the delta
      )
    }
  }

  it should "retry a transient 5xx and succeed" in {
    server.stubFor(
      post(urlEqualTo("/api/chat"))
        .inScenario("recovery")
        .whenScenarioStateIs(Scenario.STARTED)
        .willReturn(aResponse().withStatus(503).withBody("busy"))
        .willSetStateTo("recovered")
    )
    server.stubFor(
      post(urlEqualTo("/api/chat"))
        .inScenario("recovery")
        .whenScenarioStateIs("recovered")
        .willReturn(okJson(replyBody()))
    )
    withSession(config(), policy)(_.exchange(List(ChatMessage("user", "go")))).asserting { turn =>
      turn.toolCalls.map(_.name) shouldBe List("echo")
      countRequests() shouldBe 2
    }
  }

  it should "fail fast on a systemic 4xx without retrying" in {
    server.stubFor(post(urlEqualTo("/api/chat")).willReturn(aResponse().withStatus(400).withBody("bad request")))
    withSession(config(), policy)(_.exchange(List(ChatMessage("user", "go")))).attempt.asserting {
      case Left(e: OllamaChatRequestFailed) =>
        e.underlying shouldBe OllamaHttpError(400, "bad request")
        countRequests() shouldBe 1
      case other => fail(s"expected OllamaChatRequestFailed, got $other")
    }
  }

  it should "treat an unparseable 200 body as systemic" in {
    server.stubFor(post(urlEqualTo("/api/chat")).willReturn(okJson("""{"done":true}""")))
    withSession(config(), policy)(_.exchange(List(ChatMessage("user", "go")))).attempt.asserting {
      case Left(e: OllamaChatRequestFailed) =>
        e.underlying shouldBe OllamaResponseParseFailed("no 'message'", """{"done":true}""")
        countRequests() shouldBe 1
      case other => fail(s"expected OllamaChatRequestFailed, got $other")
    }
  }

  it should "retry a timed-out call as transient, then fail wrapped once retries are spent" in {
    server.stubFor(post(urlEqualTo("/api/chat")).willReturn(okJson(replyBody()).withFixedDelay(800)))
    withSession(config(requestTimeout = 150.millis), policy)(_.exchange(List(ChatMessage("user", "go")))).attempt
      .asserting {
        case Left(e: OllamaChatRequestFailed) =>
          e.underlying shouldBe a[java.util.concurrent.TimeoutException]
          countRequests() shouldBe 2 // first attempt + one retry
        case other => fail(s"expected OllamaChatRequestFailed, got $other")
      }
  }

  it should "stop the session when the token budget is spent — before issuing another wire call" in {
    stubOk() // each reply spends 15 tokens (10 prompt + 5 eval)
    val budgeted = policy.copy(tokenBudget = Some(10))
    withSession(config(), budgeted) { session =>
      session.exchange(List(ChatMessage("user", "go"))) *>
        session.exchange(List(ChatMessage("tool", "result"))).attempt
    }.asserting {
      case Left(e: OllamaTokenBudgetExhausted) =>
        e.spentTokens shouldBe 15L
        e.budget shouldBe 10
        countRequests() shouldBe 1 // the second exchange never reached the wire
      case other => fail(s"expected OllamaTokenBudgetExhausted, got $other")
    }
  }

  it should "allow further calls while the budget is not yet spent" in {
    stubOk()
    val roomy = policy.copy(tokenBudget = Some(1000))
    withSession(config(), roomy) { session =>
      session.exchange(List(ChatMessage("user", "go"))) *>
        session.exchange(List(ChatMessage("tool", "result")))
    }.asserting { turn =>
      turn.toolCalls.map(_.name) shouldBe List("echo")
      countRequests() shouldBe 2
    }
  }

  private def countRequests(): Int =
    server.findAll(postRequestedFor(urlEqualTo("/api/chat"))).size()

}
