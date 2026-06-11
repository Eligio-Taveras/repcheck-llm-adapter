package com.repcheck.llm.adapter.claude

import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import repcheck.shared.models.llm.agentic.LoopPolicy
import repcheck.shared.models.llm.output.ClusterConceptOutput
import repcheck.shared.models.llm.prompt.{AssembledPrompt, ChatMessage}

import com.repcheck.llm.adapter.DefaultAgenticLlmRunner
import com.repcheck.utils.errors.{RetryConfig, RetryWrapper}
import com.repcheck.utils.tags.E2ETest

/**
 * F2c conformance against the LIVE Anthropic API (excluded from `sbt test`; needs ANTHROPIC_API_KEY — run with
 * `testOnly *ClaudeLlmProviderConformanceSpec -- -n com.repcheck.tags.E2ETest`, see README). Costs a few cents on
 * haiku.
 *
 * The cache-law test is THE cloud half of the context-efficiency conformance: the second call of a session must report
 * `cache_read_input_tokens > 0` — proof the replayed prefix was served from the prompt cache, not re-billed. The system
 * prompt is padded past haiku's ~2048-token minimum cacheable prefix.
 */
class ClaudeLlmProviderConformanceSpec extends AsyncFlatSpec with AsyncIOSpec with Matchers {

  private val apiKey = sys.env.get("ANTHROPIC_API_KEY")
  private val model  = sys.env.getOrElse("ANTHROPIC_TOOL_MODEL", "claude-haiku-4-5-20251001")

  private val config = ClaudeConfig(
    model = model,
    maxTokens = 1024,
    requestTimeout = 120.seconds,
    retry = RetryConfig(maxRetries = 2, initialBackoffMs = 500L, maxBackoffMs = 5000L),
  )

  private val policy       = LoopPolicy(maxIterations = 4, perCallTimeout = 120.seconds, tokenBudget = None)
  private val retryWrapper = new RetryWrapper[IO]((_, _, _, _, _, _) => IO.unit)

  // well past haiku's minimum cacheable prefix (~4k tokens of padding), stable across calls so the prefix can hit
  private val longSystem: String =
    "You classify legislative concepts precisely and concisely. When confident, call the submit tool ONCE with " +
      "arguments matching its parameter schema exactly: a short label, a one-sentence summary, and selectedNodeIds " +
      "as an empty array. Do not narrate. " +
      ("Apply consistent judgement: read the described sections, identify the dominant policy concept, prefer " +
        "specific labels over broad ones, and never invent node ids that were not provided. ") * 150

  private val prompt = AssembledPrompt(
    longSystem,
    List(
      ChatMessage(
        "user",
        "These bill sections extend the residential solar investment tax credit through 2030 and raise the " +
          "credit rate to 30 percent. Submit the concept.",
      )
    ),
  )

  private def withClient[A](use: AnthropicClient => IO[A]): IO[A] =
    apiKey match {
      case None => IO(cancel("ANTHROPIC_API_KEY is not set in this shell"))
      case Some(key) =>
        IO(AnthropicOkHttpClient.builder().apiKey(key).maxRetries(0).build()).flatMap(use)
    }

  private def provider(client: AnthropicClient): ClaudeLlmProvider[IO] =
    new ClaudeLlmProvider[IO](client, config, retryWrapper)

  "ClaudeLlmProvider against the live API" should "complete a bounded agentic run with a schema-valid submit" taggedAs
    E2ETest in {
      withClient { client =>
        new DefaultAgenticLlmRunner[IO](provider(client)).run[ClusterConceptOutput](prompt, Nil, policy)
      }.asserting { result =>
        result.output.label should not be empty
        result.output.summary should not be empty
        result.iterations should be <= policy.maxIterations
        result.transcript should not be empty
      }
    }

  it should "enforce the token budget window against real token counts" taggedAs E2ETest in {
    val budgeted = policy.copy(tokenBudget = Some(1))
    withClient { client =>
      provider(client).open(prompt.system, Nil, budgeted).use { session =>
        session.exchange(prompt.messages) *>
          session.exchange(List(ChatMessage("user", "continue"))).attempt
      }
    }.asserting {
      case Left(e: ClaudeTokenBudgetExhausted) =>
        e.budget shouldBe 1
        e.spentTokens should be >= 1L
      case other => fail(s"expected ClaudeTokenBudgetExhausted, got $other")
    }
  }

  it should "serve the replayed prefix from the prompt cache — cache_read_input_tokens > 0 on the second call" taggedAs
    E2ETest in {
      val echoSpec = repcheck.shared.models.llm.tool.ToolSpec(
        "note_concept",
        "Records a working note about the concept before submission.",
        io.circe.Json.obj(),
        io.circe.Json.obj(),
        io.circe.Json.obj(),
        io.circe.Json.obj(),
      )
      withClient { client =>
        for {
          first <- IO
            .blocking(
              client
                .messages()
                .create(
                  ClaudeWire.requestParams(
                    config,
                    longSystem,
                    List(echoSpec),
                    Vector.empty,
                    ClaudeWire.userMessage(prompt.messages, Nil),
                  )
                )
            )
            .map(ClaudeWire.decode)
          state = ClaudeSessionState.initial
            .advancedBy(prompt.messages.length, ClaudeWire.userMessage(prompt.messages, Nil), first)
          second <- IO
            .blocking(
              client
                .messages()
                .create(
                  ClaudeWire.requestParams(
                    config,
                    longSystem,
                    List(echoSpec),
                    state.transcript,
                    ClaudeWire.userMessage(List(ChatMessage("tool", "\"noted\"")), state.pendingToolUseIds),
                  )
                )
            )
            .map(ClaudeWire.decode)
        } yield (first, second)
      }.asserting {
        case (first, second) =>
          first.cacheCreationTokens should be > 0L // the prefix qualified for caching (above the model minimum)
          second.cacheReadTokens should be > 0L    // ...and the second call was served from it, not re-billed
      }
    }

}
