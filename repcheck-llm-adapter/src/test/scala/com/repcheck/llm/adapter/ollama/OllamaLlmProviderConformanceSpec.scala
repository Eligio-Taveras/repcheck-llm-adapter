package com.repcheck.llm.adapter.ollama

import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec

import org.http4s.Uri
import org.http4s.ember.client.EmberClientBuilder

import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import repcheck.shared.models.llm.agentic.LoopPolicy
import repcheck.shared.models.llm.output.ClusterConceptOutput
import repcheck.shared.models.llm.prompt.{AssembledPrompt, ChatMessage}

import com.repcheck.llm.adapter.DefaultAgenticLlmRunner
import com.repcheck.utils.errors.{RetryConfig, RetryWrapper}
import com.repcheck.utils.tags.DockerRequired

/**
 * F2b conformance against a LIVE Ollama (excluded from `sbt test`; run with `testOnly *OllamaLlmProviderConformanceSpec
 * -- -n DockerRequired`). Needs a tool-calling model pulled, e.g. `docker exec <ollama> ollama pull qwen3:0.6b`.
 * Override via OLLAMA_BASE_URI / OLLAMA_TOOL_MODEL.
 *
 * The wire half of the context-efficiency law (byte-identical append-only prefix + keep_alive on every request) is
 * asserted deterministically in [[OllamaLlmProviderSpec]]; here the budget window is enforced against REAL token counts
 * and the bounded loop must produce a schema-valid submit end-to-end.
 */
class OllamaLlmProviderConformanceSpec extends AsyncFlatSpec with AsyncIOSpec with Matchers {

  private val baseUri = sys.env.getOrElse("OLLAMA_BASE_URI", "http://localhost:11434")
  private val model   = sys.env.getOrElse("OLLAMA_TOOL_MODEL", "qwen3:0.6b")

  // generous timeouts: the first call may load the model into memory
  private val config = OllamaConfig(
    baseUri = Uri.unsafeFromString(baseUri),
    model = model,
    numCtx = 8192,
    keepAlive = 5.minutes,
    requestTimeout = 180.seconds,
    retry = RetryConfig(maxRetries = 2, initialBackoffMs = 500L, maxBackoffMs = 5000L),
  )

  private val policy       = LoopPolicy(maxIterations = 6, perCallTimeout = 180.seconds, tokenBudget = None)
  private val retryWrapper = new RetryWrapper[IO]((_, _, _, _, _, _) => IO.unit)

  private def withProvider[A](use: OllamaLlmProvider[IO] => IO[A]): IO[A] =
    EmberClientBuilder.default[IO].withTimeout(config.requestTimeout).build.use { client =>
      use(new OllamaLlmProvider[IO](client, config, retryWrapper))
    }

  private val prompt = AssembledPrompt(
    "You classify legislative concepts. When confident, call the submit tool ONCE with arguments matching its " +
      "parameter schema exactly: a short label, a one-sentence summary, and selectedNodeIds as an empty array.",
    List(
      ChatMessage(
        "user",
        "These bill sections extend the residential solar investment tax credit through 2030 and raise the " +
          "credit rate to 30 percent. Submit the concept.",
      )
    ),
  )

  "OllamaLlmProvider against live Ollama" should "complete a bounded agentic run with a schema-valid submit" taggedAs
    DockerRequired in {
      withProvider { provider =>
        new DefaultAgenticLlmRunner[IO](provider).run[ClusterConceptOutput](prompt, Nil, policy)
      }.asserting { result =>
        result.output.label should not be empty
        result.output.summary should not be empty
        result.iterations should be <= policy.maxIterations
        result.transcript should not be empty
      }
    }

  it should "enforce the token budget window against real token counts" taggedAs DockerRequired in {
    val budgeted = policy.copy(tokenBudget = Some(1))
    withProvider { provider =>
      provider.open(prompt.system, Nil, budgeted).use { session =>
        session.exchange(prompt.messages) *> // real spend immediately exceeds the 1-token budget
          session.exchange(List(ChatMessage("user", "continue"))).attempt
      }
    }.asserting {
      case Left(e: OllamaTokenBudgetExhausted) =>
        e.budget shouldBe 1
        e.spentTokens should be >= 1L
      case other => fail(s"expected OllamaTokenBudgetExhausted, got $other")
    }
  }

}
