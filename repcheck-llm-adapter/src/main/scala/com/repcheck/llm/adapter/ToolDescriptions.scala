package com.repcheck.llm.adapter

import repcheck.shared.models.llm.tool.ToolSpec

/**
 * Provider wire formats (Ollama and Anthropic alike) only carry name/description/input-schema for a tool, so the D21
 * "show the model both shapes" rule rides in the description: example arguments, the result schema, and an example
 * result — all codec-derived, shared verbatim by every provider so the model-facing contract cannot drift per wire.
 */
object ToolDescriptions {

  def withShapes(spec: ToolSpec): String =
    s"${spec.description} Example arguments: ${spec.exampleArgs.noSpaces}. " +
      s"Result schema: ${spec.resultSchema.noSpaces}. Example result: ${spec.exampleResult.noSpaces}."

}
