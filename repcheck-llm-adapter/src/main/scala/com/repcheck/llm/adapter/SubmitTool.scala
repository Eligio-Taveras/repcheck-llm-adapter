package com.repcheck.llm.adapter

import cats.Applicative

import io.circe.Json

import repcheck.shared.models.llm.codec.{StructuredCodec, StructuredSchema}
import repcheck.shared.models.llm.tool.{LlmTool, ToolInputError, ToolSpec}

/**
 * The terminal tool the runner injects: the model calls `submit(<output JSON>)` to end the loop with the final answer.
 * Its `input_schema` is the output type's JSON Schema, so the answer is schema-constrained exactly like any tool call.
 */
final class SubmitTool[F[_]: Applicative, A](output: StructuredSchema[A])(using codec: StructuredCodec[A])
    extends LlmTool[F] {
  type In  = A
  type Out = A

  val spec: ToolSpec = ToolSpec(
    name = SubmitTool.Name,
    description = "Submit the final answer once confident; its arguments MUST match the output schema.",
    parametersSchema = output.jsonSchema,
    resultSchema = output.jsonSchema,
    exampleArgs = output.example,
    exampleResult = output.example,
  )

  def decode(arguments: Json): Either[ToolInputError, A] =
    codec.decoder.decodeJson(arguments).left.map(df => ToolInputError("output", df.getMessage))

  def execute(in: A): F[A] = Applicative[F].pure(in)

  def encodeResult(out: A): Json = codec.encoder(out)
}

object SubmitTool {
  val Name: String = "submit"
}
