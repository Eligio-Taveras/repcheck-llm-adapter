package com.repcheck.llm.adapter

import repcheck.shared.models.llm.agentic.{AgenticResult, LoopPolicy}
import repcheck.shared.models.llm.codec.StructuredCodec
import repcheck.shared.models.llm.tool.LlmTool

/**
 * Runs the bounded agentic tool-use loop and returns a schema-valid typed `A`, or fails (never an unstructured result).
 * The output schema + example are derived from `A`'s [[StructuredCodec]]; a terminal `submit` tool whose input_schema
 * is the output schema ends the loop.
 */
trait AgenticLlmRunner[F[_]] {

  def run[A](prompt: AssembledPrompt, tools: List[LlmTool[F]], policy: LoopPolicy)(using
    StructuredCodec[A]
  ): F[AgenticResult[A]]

}
