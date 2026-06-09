package com.repcheck.llm.adapter

import scala.concurrent.duration._

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{IO, Ref}

import io.circe.Json

import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import repcheck.shared.models.llm.agentic.{LoopPolicy, Turn}
import repcheck.shared.models.llm.codec.StructuredCodec
import repcheck.shared.models.llm.output.{ProposedNode, TaxonomyOutput}
import repcheck.shared.models.llm.tool.{LlmTool, ToolCall, ToolInputError, ToolSpec}

/**
 * §10c AgenticLlmRunner conformance: a scripted provider drives the loop deterministically (record/replay) so the loop
 * behaviour is verified with no live model — bounds, termination, tool dispatch, never returns unstructured.
 */
class AgenticLlmRunnerSpec extends AsyncFlatSpec with AsyncIOSpec with Matchers {

  private val policy = LoopPolicy(maxIterations = 5, perCallTimeout = 1.second, tokenBudget = None)
  private val prompt = AssembledPrompt("system", List(ChatMessage("user", "go")))

  private val sample                       = TaxonomyOutput(List(ProposedNode("Healthcare", "desc", None)))
  private val validSubmit                  = StructuredCodec[TaxonomyOutput].encoder(sample)
  private def submitTurn(args: Json): Turn = Turn(0, List(ToolCall("submit", args)), Nil)

  private def runner(turns: List[Turn]): IO[AgenticLlmRunner[IO]] =
    Ref[IO].of(turns).map { ref =>
      val provider = new LlmProvider[IO] {
        def chatWithTools(p: AssembledPrompt, tools: List[ToolSpec], pol: LoopPolicy): IO[Turn] =
          ref.modify {
            case h :: t => (t, h)
            case Nil    => (Nil, Turn(0, Nil, Nil))
          }
      }
      new DefaultAgenticLlmRunner[IO](provider)
    }

  "the runner" should "terminate on a valid submit, returning the typed output" in {
    runner(List(submitTurn(validSubmit))).flatMap(_.run[TaxonomyOutput](prompt, Nil, policy)).asserting { r =>
      r.output shouldBe sample
      r.iterations shouldBe 1
    }
  }

  it should "raise AgenticRunFailed when the budget is exhausted without a submit" in {
    val noop = Turn(0, List(ToolCall("noop", Json.obj())), Nil)
    runner(List(noop, noop, noop))
      .flatMap(_.run[TaxonomyOutput](prompt, Nil, policy.copy(maxIterations = 2)))
      .attempt
      .asserting {
        case Left(e: AgenticRunFailed) => e.iterations shouldBe 2
        case other                     => fail(s"expected AgenticRunFailed, got $other")
      }
  }

  it should "re-prompt on an invalid submit, then succeed" in {
    runner(List(submitTurn(Json.obj("nope" -> Json.fromString("x"))), submitTurn(validSubmit)))
      .flatMap(_.run[TaxonomyOutput](prompt, Nil, policy))
      .asserting { r =>
        r.output shouldBe sample
        r.iterations shouldBe 2
      }
  }

  it should "dispatch a typed tool (decode -> execute -> encodeResult), then submit" in {
    val echoCall = Turn(0, List(ToolCall("echo", Json.fromString("hi"))), Nil)
    runner(List(echoCall, submitTurn(validSubmit)))
      .flatMap(_.run[TaxonomyOutput](prompt, List(EchoTool), policy))
      .asserting { r =>
        r.iterations shouldBe 2
        r.transcript match {
          case first :: _ => first.toolResults.map(_.content) shouldBe List(Json.fromString("hi"))
          case Nil        => fail("expected a recorded turn")
        }
      }
  }

  it should "flag an unknown tool call as an error and continue" in {
    val unknown = Turn(0, List(ToolCall("ghost", Json.obj())), Nil)
    runner(List(unknown, submitTurn(validSubmit))).flatMap(_.run[TaxonomyOutput](prompt, Nil, policy)).asserting { r =>
      r.transcript match {
        case first :: _ => first.toolResults.exists(_.isError) shouldBe true
        case Nil        => fail("expected a recorded turn")
      }
    }
  }

  it should "flag a tool-arg decode failure as an error and continue" in {
    val badEcho = Turn(0, List(ToolCall("echo", Json.obj())), Nil) // echo expects a string, gets an object
    runner(List(badEcho, submitTurn(validSubmit)))
      .flatMap(_.run[TaxonomyOutput](prompt, List(EchoTool), policy))
      .asserting { r =>
        r.transcript match {
          case first :: _ => first.toolResults.exists(_.isError) shouldBe true
          case Nil        => fail("expected a recorded turn")
        }
      }
  }

  private object EchoTool extends LlmTool[IO] {
    type In  = String
    type Out = String
    val spec: ToolSpec = ToolSpec("echo", "echoes", Json.obj(), Json.obj(), Json.obj(), Json.obj())

    def decode(args: Json): Either[ToolInputError, String] =
      args.asString.toRight(ToolInputError("arguments", "expected a string"))

    def execute(in: String): IO[String] = IO.pure(in)
    def encodeResult(out: String): Json = Json.fromString(out)
  }

}
