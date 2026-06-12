package com.repcheck.llm.adapter.claude

import scala.jdk.CollectionConverters._

import io.circe.Json

import com.anthropic.core.JsonValue
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Bridge between circe (our codec layer) and the SDK's `JsonValue` wire values. The response side is a TOTAL structural
 * traversal via the SDK's own visitor — every node kind maps explicitly, no class tokens, no reflection, nothing to
 * throw: the lossy corners (a missing value, a NaN/infinite number JSON cannot represent) map to `Json.Null` by design.
 */
private[claude] object ClaudeJson {

  private val mapper = new ObjectMapper()

  def toJsonValue(json: Json): JsonValue =
    JsonValue.fromJsonNode(mapper.readTree(json.noSpaces))

  def toCirce(value: JsonValue): Json =
    value.accept(new JsonValue.Visitor[Json] {
      override def visitNull(): Json                  = Json.Null
      override def visitMissing(): Json               = Json.Null
      override def visitBoolean(value: Boolean): Json = Json.fromBoolean(value)
      override def visitNumber(value: Number): Json   = numberToJson(value)
      override def visitString(value: String): Json   = Json.fromString(value)

      override def visitArray(values: java.util.List[? <: JsonValue]): Json =
        Json.fromValues(values.asScala.toList.map(toCirce))

      override def visitObject(values: java.util.Map[String, ? <: JsonValue]): Json =
        Json.fromFields(values.asScala.toList.map { case (key, value) => (key, toCirce(value)) })
    })

  private def numberToJson(value: Number): Json =
    value match {
      case d: java.lang.Double if d.isNaN || d.isInfinite => Json.Null
      case f: java.lang.Float if f.isNaN || f.isInfinite  => Json.Null
      case other                                          => Json.fromBigDecimal(BigDecimal(other.toString))
    }

}
