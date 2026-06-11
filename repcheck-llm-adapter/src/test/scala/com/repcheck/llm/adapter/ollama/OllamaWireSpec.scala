package com.repcheck.llm.adapter.ollama

import scala.concurrent.duration._

import io.circe.Json
import io.circe.syntax._

import org.http4s.Uri

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import repcheck.shared.models.llm.prompt.ChatMessage
import repcheck.shared.models.llm.tool.ToolSpec

import com.repcheck.utils.errors.RetryConfig

/** Equivalence-class coverage of the pure wire mapping — every malformed-reply branch has a case. */
class OllamaWireSpec extends AnyFlatSpec with Matchers {

  private val config = OllamaConfig(
    baseUri = Uri.unsafeFromString("http://localhost:11434"),
    model = "qwen3:0.6b",
    numCtx = 8192,
    keepAlive = 300.seconds,
    requestTimeout = 30.seconds,
    retry = RetryConfig(),
  )

  private val spec = ToolSpec(
    name = "echo",
    description = "echoes.",
    parametersSchema = Json.obj("type" -> "object".asJson),
    resultSchema = Json.obj("type" -> "string".asJson),
    exampleArgs = Json.obj("text" -> "hi".asJson),
    exampleResult = "hi".asJson,
  )

  "chatUri" should "append /api/chat to the base" in {
    OllamaWire.chatUri(config.baseUri).renderString shouldBe "http://localhost:11434/api/chat"
  }

  "requestJson" should "carry model, system-first messages, tools, stream=false, num_ctx and keep_alive in seconds" in {
    val transcript = Vector(OllamaWire.wireMessage(ChatMessage("user", "go")))
    val body       = OllamaWire.requestJson(config, "sys", List(spec), transcript)
    val cursor     = body.hcursor

    cursor.get[String]("model") shouldBe Right("qwen3:0.6b")
    cursor.get[Boolean]("stream") shouldBe Right(false)
    cursor.downField("options").get[Int]("num_ctx") shouldBe Right(8192)
    cursor.get[String]("keep_alive") shouldBe Right("300s")
    cursor.get[List[Json]]("messages").map(_.map(_.hcursor.get[String]("role"))) shouldBe
      Right(List(Right("system"), Right("user")))
  }

  "toolJson" should "emit the function shape with the D21 example/result shapes folded into the description" in {
    val cursor = OllamaWire.toolJson(spec).hcursor
    cursor.get[String]("type") shouldBe Right("function")

    val function = cursor.downField("function")
    function.get[String]("name") shouldBe Right("echo")
    function.get[Json]("parameters") shouldBe Right(spec.parametersSchema)
    function.get[String]("description") shouldBe Right(
      """echoes. Example arguments: {"text":"hi"}. Result schema: {"type":"string"}. Example result: "hi"."""
    )
  }

  "parseReply" should "decode tool calls, the verbatim assistant message, and the token counts" in {
    val raw =
      """{"message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"echo","arguments":{"text":"hi"}}}]},
        |"prompt_eval_count":10,"eval_count":5}""".stripMargin
    OllamaWire.parseReply(raw) match {
      case Right(reply) =>
        reply.toolCalls.map(_.name) shouldBe List("echo")
        reply.toolCalls.map(_.arguments) shouldBe List(Json.obj("text" -> "hi".asJson))
        reply.promptEvalCount shouldBe 10L
        reply.evalCount shouldBe 5L
        reply.assistantMessage.hcursor.get[String]("role") shouldBe Right("assistant")
      case Left(err) => fail(s"expected a parsed reply, got $err")
    }
  }

  it should "treat a prose-only reply (no tool_calls) as zero tool calls" in {
    val raw = """{"message":{"role":"assistant","content":"thinking..."},"prompt_eval_count":3,"eval_count":2}"""
    OllamaWire.parseReply(raw).map(_.toolCalls) shouldBe Right(Nil)
  }

  it should "default missing token counts to zero" in {
    val raw = """{"message":{"role":"assistant","content":""}}"""
    OllamaWire.parseReply(raw).map(r => (r.promptEvalCount, r.evalCount)) shouldBe Right((0L, 0L))
  }

  it should "decode string-encoded arguments into structured Json" in {
    val raw =
      """{"message":{"role":"assistant","tool_calls":[{"function":{"name":"echo","arguments":"{\"text\":\"hi\"}"}}]}}"""
    OllamaWire.parseReply(raw).map(_.toolCalls.map(_.arguments)) shouldBe
      Right(List(Json.obj("text" -> "hi".asJson)))
  }

  it should "keep unparseable string arguments verbatim so the tool's decode produces the re-prompt feedback" in {
    val raw =
      """{"message":{"role":"assistant","tool_calls":[{"function":{"name":"echo","arguments":"not json"}}]}}"""
    OllamaWire.parseReply(raw).map(_.toolCalls.map(_.arguments)) shouldBe Right(List(Json.fromString("not json")))
  }

  it should "default a tool call with no arguments field to an empty object" in {
    val raw = """{"message":{"role":"assistant","tool_calls":[{"function":{"name":"echo"}}]}}"""
    OllamaWire.parseReply(raw).map(_.toolCalls) shouldBe
      Right(List(repcheck.shared.models.llm.tool.ToolCall("echo", Json.obj())))
  }

  it should "fail on a body that is not JSON" in {
    OllamaWire.parseReply("nope") match {
      case Left(err) => err.rawBody shouldBe "nope"
      case Right(r)  => fail(s"expected a parse failure, got $r")
    }
  }

  it should "fail on a reply with no message" in {
    OllamaWire.parseReply("""{"done":true}""") match {
      case Left(err) => err.detail shouldBe "no 'message'"
      case Right(r)  => fail(s"expected a parse failure, got $r")
    }
  }

  it should "fail on a tool call with no name" in {
    val raw = """{"message":{"role":"assistant","tool_calls":[{"function":{"arguments":{}}}]}}"""
    OllamaWire.parseReply(raw) match {
      case Left(err) => err.detail shouldBe "tool call has no name"
      case Right(r)  => fail(s"expected a parse failure, got $r")
    }
  }

}
