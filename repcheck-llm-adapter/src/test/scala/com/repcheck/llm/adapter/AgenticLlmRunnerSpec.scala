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
 * context contract: the session is opened once and every model call is handed the retained tools + the conversation
 * grown by exactly the delta.
 */
class AgenticLlmRunnerSpec extends AsyncFlatSpec with AsyncIOSpec with Matchers {

  private val policy = LoopPolicy(maxIterations = 5, perCallTimeout = 1.second, tokenBudget = None)
  private val prompt = AssembledPrompt("system", List(ChatMessage("user", "go")))

  private val sample                       = TaxonomyOutput(List(ProposedNode("Healthcare", "desc", None)))
  private val validSubmit                  = StructuredCodec[TaxonomyOutput].encoder(sample)
  private def submitTurn(args: Json): Turn = Turn(0, List(ToolCall("submit", args)), Nil)

  /** What a scripted run recorded: the conversation + tools each model call saw, and how many times open() ran. */
  final private case class Recorder(
    conversations: Ref[IO, Vector[List[ChatMessage]]],
    toolsPerCall: Ref[IO, Vector[List[ToolSpec]]],
    opens: Ref[IO, Int],
  )

  private def scripted(turns: List[Turn]): IO[(AgenticLlmRunner[IO], Recorder)] =
    for {
      remaining     <- Ref[IO].of(turns)
      conversations <- Ref[IO].of(Vector.empty[List[ChatMessage]])
      toolsPerCall  <- Ref[IO].of(Vector.empty[List[ToolSpec]])
      opens         <- Ref[IO].of(0)
    } yield {
      val provider = new LlmProvider[IO] {
        def open(system: String, tools: List[ToolSpec], pol: LoopPolicy): Resource[IO, LlmSession[IO]] =
          Resource.eval(
            for {
              _       <- opens.update(_ + 1)
              history <- Ref[IO].of(List.empty[ChatMessage])
            } yield new LlmSession[IO](system, tools, pol, history) {
              protected def respond(
                sys: String,
                tls: List[ToolSpec],
                p: LoopPolicy,
                conversation: List[ChatMessage],
              ): IO[Turn] =
                conversations.update(_ :+ conversation) *> toolsPerCall.update(_ :+ tls) *> remaining.modify {
                  case h :: t => (t, h)
                  case Nil    => (Nil, Turn(0, Nil, Nil))
                }
            }
          )
      }
      (new DefaultAgenticLlmRunner[IO](provider), Recorder(conversations, toolsPerCall, opens))
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

  // --- enforcement of the context contract ---

  it should "open the session exactly once and hand the retained tool catalogue to every model call" in {
    val echoCall = Turn(0, List(ToolCall("echo", Json.fromString("hi"))), Nil)
    scripted(List(echoCall, echoCall, submitTurn(validSubmit)))
      .flatMap {
        case (r, rec) =>
          r.run[TaxonomyOutput](prompt, List(EchoTool), policy) *> (rec.opens.get, rec.toolsPerCall.get).tupled
      }
      .asserting {
        case (opens, toolsPerCall) =>
          opens shouldBe 1
          toolsPerCall.size shouldBe 3
          toolsPerCall.map(_.map(_.name)).distinct shouldBe Vector(List("submit", "echo"))
      }
  }

  it should "grow the conversation by exactly the delta each turn — initial prompt, then only the new tool result" in {
    val echoCall = Turn(0, List(ToolCall("echo", Json.fromString("hi"))), Nil)
    scripted(List(echoCall, submitTurn(validSubmit)))
      .flatMap { case (r, rec) => r.run[TaxonomyOutput](prompt, List(EchoTool), policy) *> rec.conversations.get }
      .asserting { conversations =>
        conversations.toList match {
          case c1 :: c2 :: Nil =>
            c1 shouldBe prompt.messages                                    // turn 1: just the initial prompt
            c2 shouldBe (prompt.messages :+ ChatMessage("tool", "\"hi\"")) // turn 2: prompt + ONLY the new tool result
          case other => fail(s"expected exactly 2 model calls, got $other")
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
