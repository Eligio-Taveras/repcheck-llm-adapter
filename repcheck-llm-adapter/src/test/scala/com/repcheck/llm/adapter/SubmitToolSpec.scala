package com.repcheck.llm.adapter

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec

import io.circe.Json

import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import repcheck.shared.models.llm.codec.{StructuredCodec, StructuredSchema}
import repcheck.shared.models.llm.output.{ProposedNode, TaxonomyOutput}

class SubmitToolSpec extends AsyncFlatSpec with AsyncIOSpec with Matchers {

  private val sc: StructuredCodec[TaxonomyOutput] = StructuredCodec[TaxonomyOutput]

  private val tool: SubmitTool[IO, TaxonomyOutput] =
    new SubmitTool[IO, TaxonomyOutput](StructuredSchema.from[TaxonomyOutput])

  private val a: TaxonomyOutput = TaxonomyOutput(List(ProposedNode("X", "d", None)))

  "SubmitTool.spec" should "advertise the output schema as its input schema" in {
    tool.spec.name shouldBe "submit"
    tool.spec.parametersSchema shouldBe sc.jsonSchema
  }

  "decode" should "accept valid args and reject invalid ones" in {
    tool.decode(sc.encoder(a)).isRight shouldBe true
    tool.decode(Json.obj("nope" -> Json.fromString("x"))).isLeft shouldBe true
  }

  "execute + encodeResult" should "be identity over the typed value" in {
    tool.execute(a).asserting { result =>
      result shouldBe a
      tool.encodeResult(a) shouldBe sc.encoder(a)
    }
  }

}
