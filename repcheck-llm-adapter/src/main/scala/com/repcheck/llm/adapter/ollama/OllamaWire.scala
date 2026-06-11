package com.repcheck.llm.adapter.ollama

import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax._

import org.http4s.Uri

import repcheck.shared.models.llm.prompt.ChatMessage
import repcheck.shared.models.llm.tool.{ToolCall, ToolSpec}

/** Pure request/response mapping for `/api/chat` — kept side-effect free so every wire branch is directly testable. */
private[ollama] object OllamaWire {

  def chatUri(baseUri: Uri): Uri = baseUri / "api" / "chat"

  def requestJson(config: OllamaConfig, system: String, tools: List[ToolSpec], transcript: Vector[Json]): Json =
    Json.obj(
      "model"      -> config.model.asJson,
      "messages"   -> Json.fromValues(systemMessage(system) +: transcript),
      "tools"      -> Json.fromValues(tools.map(toolJson)),
      "stream"     -> Json.False,
      "options"    -> Json.obj("num_ctx" -> config.numCtx.asJson),
      "keep_alive" -> s"${config.keepAlive.toSeconds}s".asJson,
    )

  def wireMessage(message: ChatMessage): Json =
    Json.obj("role" -> message.role.asJson, "content" -> message.content.asJson)

  /**
   * The wire tool definition only carries name/description/parameters, so the D21 "show the model both shapes" rule
   * rides in the description: example arguments, the result schema, and an example result (all codec-derived).
   */
  def toolJson(spec: ToolSpec): Json =
    Json.obj(
      "type" -> "function".asJson,
      "function" -> Json.obj(
        "name"        -> spec.name.asJson,
        "description" -> describedWithShapes(spec).asJson,
        "parameters"  -> spec.parametersSchema,
      ),
    )

  def parseReply(rawBody: String): Either[OllamaResponseParseFailed, OllamaChatReply] =
    for {
      body    <- parse(rawBody).left.map(e => OllamaResponseParseFailed(e.message, rawBody))
      message <- body.hcursor.get[Json]("message").left.map(_ => OllamaResponseParseFailed("no 'message'", rawBody))
      calls   <- toolCallsOf(message, rawBody)
    } yield OllamaChatReply(message, calls, countOf(body, "prompt_eval_count"), countOf(body, "eval_count"))

  private def systemMessage(system: String): Json =
    Json.obj("role" -> "system".asJson, "content" -> system.asJson)

  private def describedWithShapes(spec: ToolSpec): String =
    s"${spec.description} Example arguments: ${spec.exampleArgs.noSpaces}. " +
      s"Result schema: ${spec.resultSchema.noSpaces}. Example result: ${spec.exampleResult.noSpaces}."

  private def toolCallsOf(message: Json, rawBody: String): Either[OllamaResponseParseFailed, List[ToolCall]] = {
    val calls = message.hcursor.get[List[Json]]("tool_calls").getOrElse(Nil)
    calls.foldRight[Either[OllamaResponseParseFailed, List[ToolCall]]](Right(Nil)) { (call, acc) =>
      for {
        rest   <- acc
        parsed <- toolCallOf(call, rawBody)
      } yield parsed :: rest
    }
  }

  private def toolCallOf(call: Json, rawBody: String): Either[OllamaResponseParseFailed, ToolCall] = {
    val function = call.hcursor.downField("function")
    for {
      name <- function.get[String]("name").left.map(_ => OllamaResponseParseFailed("tool call has no name", rawBody))
      args = function.get[Json]("arguments").getOrElse(Json.obj())
    } yield ToolCall(name, normalizedArguments(args))
  }

  /** Some Ollama builds return `arguments` as a JSON-encoded string; decode it so tools always see structured Json. */
  private def normalizedArguments(args: Json): Json =
    args.asString.flatMap(raw => parse(raw).toOption).getOrElse(args)

  private def countOf(body: Json, field: String): Long =
    body.hcursor.get[Long](field).getOrElse(0L)

}
