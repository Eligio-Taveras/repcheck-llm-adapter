package com.repcheck.llm.adapter.claude

import scala.jdk.CollectionConverters._

import io.circe.Json

import com.anthropic.core.{JsonArray, JsonBoolean, JsonNull, JsonNumber, JsonObject, JsonString, JsonValue}

/**
 * Bridge between circe (our codec layer) and the SDK's `JsonValue` wire values — a TOTAL structural interpreter in both
 * directions: circe's `fold` outbound, the SDK's own visitor inbound. Every node kind maps explicitly — no class
 * tokens, no reflection, no string round-trips, nothing to throw. The lossy corners (a missing value, a NaN/infinite
 * number JSON cannot represent) map to `Json.Null` by design.
 */
private[claude] object ClaudeJson {

  def toJsonValue(json: Json): JsonValue =
    json.fold(
      jsonNull = JsonNull.of(),
      jsonBoolean = JsonBoolean.of(_),
      // a circe JsonNumber's toString is contractually its exact JSON numeric literal — BigDecimal parses it exactly
      jsonNumber = number => JsonNumber.of(new java.math.BigDecimal(number.toString)),
      jsonString = JsonString.of(_),
      jsonArray = values => JsonArray.of(values.map(toJsonValue).asJava),
      jsonObject = fields => JsonObject.of(fields.toMap.map { case (key, value) => (key, toJsonValue(value)) }.asJava),
    )

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
      // total over ANY Number impl: an exotic subclass whose toString isn't numeric degrades to Null, never throws
      case other => scala.util.Try(BigDecimal(other.toString)).fold(_ => Json.Null, Json.fromBigDecimal)
    }

}
