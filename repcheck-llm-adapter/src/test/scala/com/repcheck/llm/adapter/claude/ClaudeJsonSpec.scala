package com.repcheck.llm.adapter.claude

import io.circe.Json
import io.circe.syntax._

import com.anthropic.core.{JsonMissing, JsonNumber, JsonValue}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ClaudeJsonSpec extends AnyFlatSpec with Matchers {

  "the bridge" should "round-trip every JSON node kind structurally" in {
    val json = Json.obj(
      "s"      -> "text".asJson,
      "i"      -> 42.asJson,
      "d"      -> 1.5.asJson,
      "b"      -> true.asJson,
      "n"      -> Json.Null,
      "arr"    -> Json.arr(1.asJson, "two".asJson, false.asJson),
      "nested" -> Json.obj("k" -> "v".asJson),
    )
    ClaudeJson.toCirce(ClaudeJson.toJsonValue(json)) shouldBe json
  }

  it should "map a missing value to Json.Null" in {
    ClaudeJson.toCirce(JsonMissing.of()) shouldBe Json.Null
  }

  it should "map NaN and infinite numbers (unrepresentable in JSON) to Json.Null" in {
    ClaudeJson.toCirce(JsonValue.from(Double.NaN)) shouldBe Json.Null
    ClaudeJson.toCirce(JsonValue.from(Double.PositiveInfinity)) shouldBe Json.Null
    ClaudeJson.toCirce(JsonValue.from(Float.NaN)) shouldBe Json.Null
  }

  it should "round-trip numbers at full precision — outbound is structural, no document re-parse" in {
    val precise = Json.obj(
      "big"     -> Long.MaxValue.asJson,
      "decimal" -> BigDecimal("123456789.000000001").asJson,
    )
    ClaudeJson.toCirce(ClaudeJson.toJsonValue(precise)) shouldBe precise
  }

  it should "convert plain finite numbers exactly" in {
    ClaudeJson.toCirce(JsonValue.from(7L)) shouldBe 7.asJson
    ClaudeJson.toCirce(JsonValue.from(2.25d)) shouldBe 2.25.asJson
    ClaudeJson.toCirce(JsonValue.from(1.5f)) shouldBe 1.5.asJson
  }

  it should "degrade a Number impl with a non-numeric toString to Json.Null instead of throwing" in {
    object Rogue extends Number {
      override def toString: String      = "not-a-number"
      override def intValue(): Int       = 0
      override def longValue(): Long     = 0L
      override def floatValue(): Float   = 0f
      override def doubleValue(): Double = 0d
    }
    // JsonNumber.of is the SDK's public, unvalidated door for arbitrary Number impls — JsonValue.from would
    // reject this eagerly via Jackson, but values built with of() reach the visitor as-is
    ClaudeJson.toCirce(JsonNumber.of(Rogue)) shouldBe Json.Null
  }

}
