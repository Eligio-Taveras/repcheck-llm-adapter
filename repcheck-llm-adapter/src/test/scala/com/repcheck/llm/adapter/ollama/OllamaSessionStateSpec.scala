package com.repcheck.llm.adapter.ollama

import io.circe.Json
import io.circe.syntax._

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import repcheck.shared.models.llm.prompt.ChatMessage

class OllamaSessionStateSpec extends AnyFlatSpec with Matchers {

  private val assistant = Json.obj("role" -> "assistant".asJson, "content" -> "".asJson)
  private val reply     = OllamaChatReply(assistant, Nil, promptEvalCount = 10L, evalCount = 5L)

  "extendedWith" should "wire the whole conversation from the initial state" in {
    val extended = OllamaSessionState.initial.extendedWith(List(ChatMessage("user", "go")))
    extended shouldBe Vector(Json.obj("role" -> "user".asJson, "content" -> "go".asJson))
  }

  it should "append only the not-yet-consumed base messages after the retained transcript" in {
    val prior = OllamaSessionState(consumed = 1, transcript = Vector(assistant), spentTokens = 0L, completedTurns = 1)
    val conversation = List(ChatMessage("user", "go"), ChatMessage("tool", "result"))
    prior.extendedWith(conversation) shouldBe Vector(
      assistant,
      Json.obj("role" -> "tool".asJson, "content" -> "result".asJson),
    )
  }

  "advancedBy" should "retain the assistant reply verbatim, accumulate the token spend, and count the turn" in {
    val extended = Vector(Json.obj("role" -> "user".asJson, "content" -> "go".asJson))
    val advanced = OllamaSessionState(0, Vector.empty, 7L, 3).advancedBy(1, extended, reply)
    advanced shouldBe OllamaSessionState(
      consumed = 1,
      transcript = extended :+ assistant,
      spentTokens = 22L,
      completedTurns = 4,
    )
  }

}
