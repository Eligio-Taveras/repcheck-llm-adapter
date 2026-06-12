package com.repcheck.llm.adapter.claude

import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec

import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax._

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.{BadRequestException, InternalServerException, RateLimitException}
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.stubbing.Scenario
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import repcheck.shared.models.llm.agentic.LoopPolicy
import repcheck.shared.models.llm.prompt.ChatMessage
import repcheck.shared.models.llm.tool.ToolSpec

import com.repcheck.llm.adapter.LlmSession
import com.repcheck.utils.errors.{RetryConfig, RetryWrapper}

/**
 * Wire-protocol conformance against a simulated Messages API. The cacheability tests are the F2c half of the
 * context-efficiency law: the static prefix (system) carries a `cache_control` breakpoint, the moving breakpoint rides
 * only on the newest user message, and replayed transcript messages are sent without markers — the documented
 * incremental multi-turn caching pattern.
 */
class ClaudeLlmProviderSpec
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

  private def config: ClaudeConfig =
    ClaudeConfig(model = "claude-test", maxTokens = 1024, requestTimeout = 5.seconds, retry = fastRetry)

  private def sdkClient(): AnthropicClient =
    AnthropicOkHttpClient.builder().apiKey("test-key").baseUrl(server.baseUrl()).maxRetries(0).build()

  private def withSession[A](pol: LoopPolicy)(use: LlmSession[IO] => IO[A]): IO[A] =
    new ClaudeLlmProvider[IO](sdkClient(), config, retryWrapper).open("sys", List(echoSpec), pol).use(use)

  private def replyBody(toolUseId: String = "tu_1", inputTokens: Int = 10, outputTokens: Int = 5): String =
    s"""{"id":"msg_01","type":"message","role":"assistant","model":"claude-test",
       |"content":[{"type":"tool_use","id":"$toolUseId","name":"echo","input":{"text":"hi"}}],
       |"stop_reason":"tool_use","stop_sequence":null,
       |"usage":{"input_tokens":$inputTokens,"output_tokens":$outputTokens,
       |"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}""".stripMargin

  private def stubOk(): Unit = {
    server.stubFor(post(urlEqualTo("/v1/messages")).willReturn(okJson(replyBody())))
    ()
  }

  private def errorBody(kind: String): String =
    s"""{"type":"error","error":{"type":"$kind","message":"simulated"}}"""

  private def requestBody(index: Int): Json = {
    val raw = server.findAll(postRequestedFor(urlEqualTo("/v1/messages"))).get(index).getBodyAsString
    parse(raw).fold(e => fail(s"request $index was not JSON: $e"), identity)
  }

  private def messagesOf(body: Json): List[Json] =
    body.hcursor.get[List[Json]]("messages").fold(e => fail(s"no messages: $e"), identity)

  private def countRequests(): Int =
    server.findAll(postRequestedFor(urlEqualTo("/v1/messages"))).size()

  "respond" should "map a tool_use reply onto a Turn with decoded arguments" in {
    stubOk()
    withSession(policy)(_.exchange(List(ChatMessage("user", "go")))).asserting { turn =>
      turn.index shouldBe 0
      turn.toolCalls.map(_.name) shouldBe List("echo")
      turn.toolCalls.map(_.arguments) shouldBe List(Json.obj("text" -> "hi".asJson))
    }
  }

  it should "send the documented request shape — cached system, function tools, forced tool choice, marked user" in {
    stubOk()
    withSession(policy)(_.exchange(List(ChatMessage("user", "go")))).asserting { _ =>
      val body = requestBody(0)
      body.hcursor.get[String]("model") shouldBe Right("claude-test")
      body.hcursor.get[Int]("max_tokens") shouldBe Right(1024)
      body.hcursor.downField("tool_choice").get[String]("type") shouldBe Right("any")

      val system = body.hcursor.downField("system").downArray
      system.get[String]("text") shouldBe Right("sys")
      system.downField("cache_control").get[String]("type") shouldBe Right("ephemeral")

      val tool = body.hcursor.downField("tools").downArray
      tool.get[String]("name") shouldBe Right("echo")
      tool.get[String]("description").map(_.contains("Example arguments:")) shouldBe Right(true)

      val lastBlock = messagesOf(body).lastOption
        .flatMap(_.hcursor.get[List[Json]]("content").toOption)
        .flatMap(_.lastOption)
        .getOrElse(Json.Null)
      lastBlock.hcursor.downField("cache_control").get[String]("type") shouldBe Right("ephemeral")
    }
  }

  it should "replay the transcript without markers and pair the tool result with the pending tool_use id" in {
    stubOk()
    withSession(policy) { session =>
      session.exchange(List(ChatMessage("user", "go"))) *>
        session.exchange(List(ChatMessage("tool", "\"hi\"")))
    }.asserting { turn =>
      turn.index shouldBe 1
      val second   = messagesOf(requestBody(1))
      val replayed = second.headOption.getOrElse(Json.Null)
      // the replayed opening message is byte-equal to what was first sent MINUS the moving cache marker
      replayed.hcursor.downField("content").downArray.downField("cache_control").focus shouldBe None
      replayed.hcursor.downField("content").downArray.get[String]("text") shouldBe Right("go")
      // assistant tool_use replayed verbatim, then the delta answers it
      second.lift(1).flatMap(_.hcursor.get[String]("role").toOption) shouldBe Some("assistant")
      val result = second.lift(2).map(_.hcursor.downField("content").downArray).getOrElse(fail("no third message"))
      result.get[String]("type") shouldBe Right("tool_result")
      result.get[String]("tool_use_id") shouldBe Right("tu_1")
    }
  }

  it should "send schema-retry feedback as an is_error tool_result answering the unanswered submit" in {
    stubOk()
    withSession(policy) { session =>
      session.exchange(List(ChatMessage("user", "go"))) *>
        session.exchange(List(ChatMessage("user", "submit did not match the schema")))
    }.asserting { _ =>
      val result = messagesOf(requestBody(1))
        .lift(2)
        .map(_.hcursor.downField("content").downArray)
        .getOrElse(fail("no third message"))
      result.get[String]("type") shouldBe Right("tool_result")
      result.get[String]("tool_use_id") shouldBe Right("tu_1")
      result.get[Boolean]("is_error") shouldBe Right(true)
    }
  }

  it should "retry transient 429/5xx and succeed" in {
    server.stubFor(
      post(urlEqualTo("/v1/messages"))
        .inScenario("recovery")
        .whenScenarioStateIs(Scenario.STARTED)
        .willReturn(
          aResponse()
            .withStatus(429)
            .withHeader("Content-Type", "application/json")
            .withBody(errorBody("rate_limit_error"))
        )
        .willSetStateTo("recovered")
    )
    server.stubFor(
      post(urlEqualTo("/v1/messages"))
        .inScenario("recovery")
        .whenScenarioStateIs("recovered")
        .willReturn(okJson(replyBody()))
    )
    withSession(policy)(_.exchange(List(ChatMessage("user", "go")))).asserting { turn =>
      turn.toolCalls.map(_.name) shouldBe List("echo")
      countRequests() shouldBe 2
    }
  }

  it should "classify a 500 as transient and a 400 as systemic" in {
    server.stubFor(
      post(urlEqualTo("/v1/messages"))
        .willReturn(
          aResponse().withStatus(500).withHeader("Content-Type", "application/json").withBody(errorBody("api_error"))
        )
    )
    withSession(policy)(_.exchange(List(ChatMessage("user", "go")))).attempt
      .flatMap { transientOutcome =>
        server.resetAll()
        server.stubFor(
          post(urlEqualTo("/v1/messages"))
            .willReturn(
              aResponse()
                .withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody(errorBody("invalid_request_error"))
            )
        )
        withSession(policy)(_.exchange(List(ChatMessage("user", "go")))).attempt
          .map(systemicOutcome => (transientOutcome, systemicOutcome))
      }
      .asserting {
        case (Left(transient: ClaudeChatRequestFailed), Left(systemic: ClaudeChatRequestFailed)) =>
          transient.underlying shouldBe a[InternalServerException]
          systemic.underlying shouldBe a[BadRequestException]
          countRequests() shouldBe 1 // 400 fails fast — one request after the reset
        case other => fail(s"expected two ClaudeChatRequestFailed, got $other")
      }
  }

  it should "stop the session when the token budget is spent — before issuing another wire call" in {
    stubOk() // each reply spends 15 tokens
    val budgeted = policy.copy(tokenBudget = Some(10))
    withSession(budgeted) { session =>
      session.exchange(List(ChatMessage("user", "go"))) *>
        session.exchange(List(ChatMessage("tool", "result"))).attempt
    }.asserting {
      case Left(e: ClaudeTokenBudgetExhausted) =>
        e.spentTokens shouldBe 15L
        e.budget shouldBe 10
        countRequests() shouldBe 1
      case other => fail(s"expected ClaudeTokenBudgetExhausted, got $other")
    }
  }

  it should "allow further calls while the budget is not yet spent" in {
    stubOk()
    val roomy = policy.copy(tokenBudget = Some(1000))
    withSession(roomy) { session =>
      session.exchange(List(ChatMessage("user", "go"))) *>
        session.exchange(List(ChatMessage("tool", "result")))
    }.asserting { turn =>
      turn.toolCalls.map(_.name) shouldBe List("echo")
      countRequests() shouldBe 2
    }
  }

  it should "omit tool_choice when the session has no tools — the API rejects a forced choice with none" in {
    stubOk()
    new ClaudeLlmProvider[IO](sdkClient(), config, retryWrapper)
      .open("sys", Nil, policy)
      .use(_.exchange(List(ChatMessage("user", "go"))))
      .asserting(_ => requestBody(0).hcursor.downField("tool_choice").focus shouldBe None)
  }

  it should "treat a timed-out call as transient, then fail wrapped once retries are spent" in {
    server.stubFor(post(urlEqualTo("/v1/messages")).willReturn(okJson(replyBody()).withFixedDelay(800)))
    val impatient =
      ClaudeConfig(model = "claude-test", maxTokens = 1024, requestTimeout = 150.millis, retry = fastRetry)
    new ClaudeLlmProvider[IO](sdkClient(), impatient, retryWrapper)
      .open("sys", List(echoSpec), policy)
      .use(_.exchange(List(ChatMessage("user", "go"))))
      .attempt
      .asserting {
        case Left(e: ClaudeChatRequestFailed) => e.underlying shouldBe a[java.util.concurrent.TimeoutException]
        case other                            => fail(s"expected ClaudeChatRequestFailed, got $other")
      }
  }

  // keeps RateLimitException referenced so the import stays honest about what the 429 path throws
  it should "surface RateLimitException as the transient cause when retries are spent on 429s" in {
    server.stubFor(
      post(urlEqualTo("/v1/messages"))
        .willReturn(
          aResponse()
            .withStatus(429)
            .withHeader("Content-Type", "application/json")
            .withBody(errorBody("rate_limit_error"))
        )
    )
    withSession(policy)(_.exchange(List(ChatMessage("user", "go")))).attempt.asserting {
      case Left(e: ClaudeChatRequestFailed) =>
        e.underlying shouldBe a[RateLimitException]
        countRequests() shouldBe 2 // first attempt + one retry
      case other => fail(s"expected ClaudeChatRequestFailed, got $other")
    }
  }

}
