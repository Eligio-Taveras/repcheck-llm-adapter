package com.repcheck.llm.adapter.claude

import io.circe.Json
import io.circe.parser.parse

import com.anthropic.core.JsonValue
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}

/**
 * Bridge between circe (our codec layer) and the SDK's Jackson-backed `JsonValue` wire values. A conversion failure on
 * the response side is left to propagate — it surfaces as a systemic [[ClaudeChatRequestFailed]] via the retry layer,
 * which is the correct treatment for a malformed reply.
 */
private[claude] object ClaudeJson {

  private val mapper = new ObjectMapper()

  def toJsonValue(json: Json): JsonValue =
    JsonValue.fromJsonNode(mapper.readTree(json.noSpaces))

  def toCirce(value: JsonValue): Json =
    Option(value.convert(classOf[JsonNode]))
      .flatMap(node => parse(node.toString).toOption)
      .getOrElse(Json.Null)

}
