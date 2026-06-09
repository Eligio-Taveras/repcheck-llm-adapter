package com.repcheck.llm.adapter

import repcheck.shared.models.llm.agentic.{LoopPolicy, Turn}
import repcheck.shared.models.llm.tool.ToolSpec

/**
 * A tool-calling chat provider (Ollama now; Claude/OpenAI later). One call = one model round-trip: given the
 * conversation + the available tool specs, it returns the [[Turn]] the model produced (the tool calls it wants). The
 * runner owns the loop; the provider owns the wire protocol and the structured-output channel.
 */
trait LlmProvider[F[_]] {
  def chatWithTools(prompt: AssembledPrompt, tools: List[ToolSpec], policy: LoopPolicy): F[Turn]
}
