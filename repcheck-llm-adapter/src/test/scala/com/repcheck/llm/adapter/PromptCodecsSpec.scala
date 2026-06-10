package com.repcheck.llm.adapter

import io.circe.syntax._

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PromptCodecsSpec extends AnyFlatSpec with Matchers {

  "AssembledPrompt / ChatMessage" should "round-trip through circe" in {
    val p = AssembledPrompt("sys", List(ChatMessage("user", "hi"), ChatMessage("tool", "{}")))
    p.asJson.as[AssembledPrompt] shouldBe Right(p)
  }

  "appended" should "add a message to the end" in {
    val p = AssembledPrompt("s", List(ChatMessage("user", "a")))
    p.appended(ChatMessage("tool", "b")).messages.map(_.content) shouldBe List("a", "b")
  }

}
