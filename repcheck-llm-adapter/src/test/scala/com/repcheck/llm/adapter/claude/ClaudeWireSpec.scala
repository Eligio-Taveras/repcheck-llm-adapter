package com.repcheck.llm.adapter.claude

import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._

import io.circe.Json
import io.circe.syntax._

import com.anthropic.models.messages.{ContentBlockParam, MessageParam}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import repcheck.shared.models.llm.prompt.ChatMessage
import repcheck.shared.models.llm.tool.ToolSpec

/** Pure mapping coverage — the tool_use id pairing rule and the moving cache breakpoint are the load-bearing parts. */
class ClaudeWireSpec extends AnyFlatSpec with Matchers {

  private val spec = ToolSpec(
    name = "echo",
    description = "echoes.",
    parametersSchema = Json.obj(
      "type"       -> "object".asJson,
      "properties" -> Json.obj("text" -> Json.obj("type" -> "string".asJson)),
      "required"   -> Json.arr("text".asJson),
    ),
    resultSchema = Json.obj("type" -> "string".asJson),
    exampleArgs = Json.obj("text" -> "hi".asJson),
    exampleResult = "hi".asJson,
  )

  private def blocksOf(param: MessageParam): List[ContentBlockParam] =
    param.content().blockParams().toScala.map(_.asScala.toList).getOrElse(Nil)

  "toolOf" should "carry name, the D21-shaped description, and the schema fields" in {
    val tool = ClaudeWire.toolOf(spec)
    tool.name() shouldBe "echo"
    tool.description().toScala.getOrElse("") should include("Example arguments:")
    tool.inputSchema()._additionalProperties().asScala.keySet should contain allOf ("properties", "required")
  }

  "userMessage" should "send the opening prompt as plain text blocks when no tool_use ids are pending" in {
    val param  = ClaudeWire.userMessage(List(ChatMessage("user", "go")), Nil)
    val blocks = blocksOf(param)
    blocks.size shouldBe 1
    blocks.flatMap(_.text().toScala).map(_.text()) shouldBe List("go")
  }

  it should "pair tool messages with the pending ids in order" in {
    val param = ClaudeWire.userMessage(
      List(ChatMessage("tool", "one"), ChatMessage("tool", "two")),
      List("tu_1", "tu_2"),
    )
    val results = blocksOf(param).flatMap(_.toolResult().toScala)
    results.map(_.toolUseId()) shouldBe List("tu_1", "tu_2")
    results.flatMap(_.content().toScala).flatMap(_.string().toScala) shouldBe List("one", "two")
    results.flatMap(_.isError().toScala) shouldBe Nil
  }

  it should "answer an unanswered tool_use with an is_error tool_result when the delta is retry feedback" in {
    val param   = ClaudeWire.userMessage(List(ChatMessage("user", "submit did not match the schema")), List("tu_9"))
    val results = blocksOf(param).flatMap(_.toolResult().toScala)
    results.map(_.toolUseId()) shouldBe List("tu_9")
    results.flatMap(_.isError().toScala).map(Boolean.unbox) shouldBe List(true)
  }

  it should "degrade extra messages beyond the pending ids to text blocks" in {
    val param  = ClaudeWire.userMessage(List(ChatMessage("tool", "one"), ChatMessage("user", "note")), List("tu_1"))
    val blocks = blocksOf(param)
    blocks.flatMap(_.toolResult().toScala).map(_.toolUseId()) shouldBe List("tu_1")
    blocks.flatMap(_.text().toScala).map(_.text()) shouldBe List("note")
  }

  "withBreakpointOnLastBlock" should "mark only the final block, whatever its kind" in {
    val mixed = ClaudeWire.userMessage(
      List(ChatMessage("tool", "one"), ChatMessage("user", "note")),
      List("tu_1"),
    )
    val marked = blocksOf(ClaudeWire.withBreakpointOnLastBlock(mixed))
    marked.flatMap(_.toolResult().toScala).flatMap(_.cacheControl().toScala) shouldBe Nil // first block untouched
    marked.flatMap(_.text().toScala).flatMap(_.cacheControl().toScala).size shouldBe 1    // last block marked

    val toolLast = ClaudeWire.userMessage(List(ChatMessage("tool", "one")), List("tu_1"))
    blocksOf(ClaudeWire.withBreakpointOnLastBlock(toolLast))
      .flatMap(_.toolResult().toScala)
      .flatMap(_.cacheControl().toScala)
      .size shouldBe 1
  }

  it should "leave a message with no blocks unchanged" in {
    val empty = ClaudeWire.userMessage(Nil, Nil)
    ClaudeWire.withBreakpointOnLastBlock(empty) shouldBe empty
  }

}
