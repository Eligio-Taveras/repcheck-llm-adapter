package com.repcheck.llm.adapter

import scala.concurrent.duration._

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{IO, Ref}

import io.circe.Json

import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import repcheck.shared.models.llm.agentic.{LoopPolicy, Turn}
import repcheck.shared.models.llm.prompt.ChatMessage
import repcheck.shared.models.llm.tool.ToolSpec

/**
 * The base session is the structural guarantee: `exchange` accumulates only the delta and hands the FULL conversation
 * plus the retained tools to `respond` on every turn. A provider that implements `respond` therefore cannot be handed
 * the tools "once" and lose them, nor can a caller re-send history.
 */
class LlmSessionSpec extends AsyncFlatSpec with AsyncIOSpec with Matchers {

  private val policy = LoopPolicy(maxIterations = 3, perCallTimeout = 1.second, tokenBudget = None)
  private val tools  = List(ToolSpec("echo", "e", Json.obj(), Json.obj(), Json.obj(), Json.obj()))

  "exchange" should "accumulate deltas and hand the full conversation + retained tools to respond each turn" in {
    for {
      calls   <- Ref[IO].of(Vector.empty[(List[ToolSpec], List[ChatMessage])])
      history <- Ref[IO].of(List.empty[ChatMessage])
      session = new LlmSession[IO]("sys", tools, policy, history) {
        protected def respond(
          s: String,
          t: List[ToolSpec],
          p: LoopPolicy,
          conversation: List[ChatMessage],
        ): IO[Turn] = calls.update(_ :+ ((t, conversation))).as(Turn(0, Nil, Nil))
      }
      _    <- session.exchange(List(ChatMessage("user", "a")))
      _    <- session.exchange(List(ChatMessage("tool", "b")))
      seen <- calls.get
    } yield {
      seen.map(_._1).distinct shouldBe Vector(tools) // tools retained on every call, not just the first
      seen.map(_._2) shouldBe Vector(
        List(ChatMessage("user", "a")),
        List(ChatMessage("user", "a"), ChatMessage("tool", "b")), // accumulated by exactly the delta, no duplication
      )
    }
  }

}
