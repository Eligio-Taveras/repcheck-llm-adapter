package com.repcheck.llm.adapter

import scala.concurrent.duration._

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all._

import io.circe.Json

import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import repcheck.shared.models.llm.agentic.{LoopPolicy, Turn}
import repcheck.shared.models.llm.codec.StructuredCodec
import repcheck.shared.models.llm.output.{ProposedNode, TaxonomyOutput}
import repcheck.shared.models.llm.tool.{LlmTool, ToolCall, ToolInputError, ToolSpec}

/**
 * §10c AgenticLlmRunner conformance: a scripted session drives the loop deterministically (record/replay) so the loop
 * behaviour is verified with no live model — bounds, termination, tool dispatch, never returns unstructured, and the
 * context contract (open once; send only incremental deltas).
 */
class AgenticLlmRunnerSpec extends AsyncFlatSpec with AsyncIOSpec with Matchers {

  private val policy = LoopPolicy(maxIterations = 5, perCallTimeout = 1.second, tokenBudget = None)
  private val prompt = AssembledPrompt("system", List(ChatMessage("user", "go")))

  private val sample                       = TaxonomyOutput(List(ProposedNode("Healthcare", "desc", None)))
  private val validSubmit                  = StructuredCodec[TaxonomyOutput].encoder(sample)
  private def submitTurn(args: Json): Turn = Turn(0, List(ToolCall("submit", args)), Nil)

  /**
   * What a scripted run recorded: every delta the session was sent, how many times open() ran, the tools open() saw.
   */
  final private case class Recorder(
    sends: Ref[IO, Vector[List[ChatMessage]]],
    opens: Ref[IO, Int],
    toolsSeen: Ref[IO, List[ToolSpec]],
  )

  private def scripted(turns: List[Turn]): IO[(AgenticLlmRunner[IO], Recorder)] =
    for {
      remaining <- Ref[IO].of(turns)
      sends     <- Ref[IO].of(Vector.empty[List[ChatMessage]])
      opens     <- Ref[IO].of(0)
      toolsSeen <- Ref[IO].of(List.empty[ToolSpec])
    } yield {
      val provider = new LlmProvider[IO] {
        def open(system: String, tools: List[ToolSpec], pol: LoopPolicy): Resource[IO, LlmSession[IO]] =
          Resource
            .eval(opens.update(_ + 1) *> toolsSeen.set(tools))
            .as(
              new LlmSession[IO] {
                def exchange(newMessages: List[ChatMessage]): IO[Turn] =
                  sends.update(_ :+ newMessages) *> remaining.modify {
                    case h :: t => (t, h)
                    case Nil    => (Nil, Turn(0, Nil, Nil))
                  }
              }
            )
      }
      (new DefaultAgenticLlmRunner[IO](provider), Recorder(sends, opens, toolsSeen))
    }

  "the runner" should "terminate on a valid submit, returning the typed output" in {
    scripted(List(submitTurn(validSubmit)))
      .flatMap { case (r, _) => r.run[TaxonomyOutput](prompt, Nil, policy) }
      .asserting { r =>
        r.output shouldBe sample
        r.iterations shouldBe 1
      }
  }

  it should "raise AgenticRunFailed when the budget is exhausted without a submit" in {
    val noop = Turn(0, List(ToolCall("noop", Json.obj())), Nil)
    scripted(List(noop, noop, noop))
      .flatMap { case (r, _) => r.run[TaxonomyOutput](prompt, Nil, policy.copy(maxIterations = 2)) }
      .attempt
      .asserting {
        case Left(e: AgenticRunFailed) => e.iterations shouldBe 2
        case other                     => fail(s"expected AgenticRunFailed, got $other")
      }
  }

  it should "re-prompt on an invalid submit, then succeed" in {
    scripted(List(submitTurn(Json.obj("nope" -> Json.fromString("x"))), submitTurn(validSubmit)))
      .flatMap { case (r, _) => r.run[TaxonomyOutput](prompt, Nil, policy) }
      .asserting { r =>
        r.output shouldBe sample
        r.iterations shouldBe 2
      }
  }

  it should "dispatch a typed tool (decode -> execute -> encodeResult), then submit" in {
    val echoCall = Turn(0, List(ToolCall("echo", Json.fromString("hi"))), Nil)
    scripted(List(echoCall, submitTurn(validSubmit)))
      .flatMap { case (r, _) => r.run[TaxonomyOutput](prompt, List(EchoTool), policy) }
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
    scripted(List(unknown, submitTurn(validSubmit)))
      .flatMap { case (r, _) => r.run[TaxonomyOutput](prompt, Nil, policy) }
      .asserting { r =>
        r.transcript match {
          case first :: _ => first.toolResults.exists(_.isError) shouldBe true
          case Nil        => fail("expected a recorded turn")
        }
      }
  }

  it should "flag a tool-arg decode failure as an error and continue" in {
    val badEcho = Turn(0, List(ToolCall("echo", Json.obj())), Nil) // echo expects a string, gets an object
    scripted(List(badEcho, submitTurn(validSubmit)))
      .flatMap { case (r, _) => r.run[TaxonomyOutput](prompt, List(EchoTool), policy) }
      .asserting { r =>
        r.transcript match {
          case first :: _ => first.toolResults.exists(_.isError) shouldBe true
          case Nil        => fail("expected a recorded turn")
        }
      }
  }

  // --- enforcement of the context contract (the seam can't guarantee provider-side caching; this pins the runner) ---

  it should "open the session exactly once and supply the tool catalogue only there, whatever the turn count" in {
    val echoCall = Turn(0, List(ToolCall("echo", Json.fromString("hi"))), Nil)
    scripted(List(echoCall, echoCall, submitTurn(validSubmit)))
      .flatMap {
        case (r, rec) =>
          r.run[TaxonomyOutput](prompt, List(EchoTool), policy) *> (rec.opens.get, rec.toolsSeen.get).tupled
      }
      .asserting {
        case (opens, tools) =>
          opens shouldBe 1
          tools.map(_.name) should contain allOf ("submit", "echo")
      }
  }

  it should "send only incremental deltas — the initial prompt, then just the new tool result, never the full history" in {
    val echoCall = Turn(0, List(ToolCall("echo", Json.fromString("hi"))), Nil)
    scripted(List(echoCall, submitTurn(validSubmit)))
      .flatMap { case (r, rec) => r.run[TaxonomyOutput](prompt, List(EchoTool), policy) *> rec.sends.get }
      .asserting { sends =>
        sends.toList match {
          case first :: second :: Nil =>
            first shouldBe prompt.messages                      // first exchange = the initial prompt only
            second shouldBe List(ChatMessage("tool", "\"hi\"")) // second exchange = ONLY the new tool result
          case other => fail(s"expected exactly 2 exchanges, got $other")
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
