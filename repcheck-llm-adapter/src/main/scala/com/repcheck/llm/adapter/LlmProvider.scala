package com.repcheck.llm.adapter

import repcheck.shared.models.llm.agentic.{LoopPolicy, Turn}
import repcheck.shared.models.llm.tool.ToolSpec

/**
 * A tool-calling chat provider (Ollama now; Claude/OpenAI later). One call = one model round-trip: given the
 * conversation + the available tool specs, it returns the [[Turn]] the model produced (the tool calls it wants). The
 * runner owns the loop; the provider owns the wire protocol and the structured-output channel.
 *
 * Context efficiency is the PROVIDER's responsibility, not the runner's. The runner is stateless by design: it hands
 * over the full logical conversation (`prompt`) and the full tool catalogue (`tools`) on every round-trip. A provider
 * MUST NOT re-send that entire context as fresh tokens each call. The stable prefix — system instruction, tool specs,
 * and prior turns — should be kept cheap across iterations via prompt caching (e.g. Anthropic `cache_control`
 * breakpoints, Ollama `keep_alive`/context reuse) and/or a bounded windowing strategy honouring
 * [[repcheck.shared.models.llm.agentic.LoopPolicy.tokenBudget]]. Tool specs in particular are invariant across the loop
 * and should be cached, not re-billed every turn.
 */
trait LlmProvider[F[_]] {
  def chatWithTools(prompt: AssembledPrompt, tools: List[ToolSpec], policy: LoopPolicy): F[Turn]
}
