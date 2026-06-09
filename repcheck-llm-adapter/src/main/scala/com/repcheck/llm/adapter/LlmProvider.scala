package com.repcheck.llm.adapter

import cats.effect.Resource

import repcheck.shared.models.llm.agentic.LoopPolicy
import repcheck.shared.models.llm.tool.ToolSpec

/**
 * A tool-calling chat provider (Ollama now; Claude/OpenAI later). It does not expose a per-call "send the whole
 * conversation" method; instead it [[open]]s a stateful [[LlmSession]] bound to the static system prompt + tool
 * catalogue, which the runner then drives with incremental messages.
 *
 * This shape makes context ownership structural: `system` and `tools` are supplied exactly once, and the session has no
 * method that re-accepts the full history or the tool list — so the runner cannot re-send accumulated context or
 * re-bill the tool catalogue each round-trip. The provider owns the wire representation and SHOULD keep the stable
 * prefix cheap via prompt caching (Anthropic `cache_control`, Ollama context reuse) and/or windowing against
 * [[repcheck.shared.models.llm.agentic.LoopPolicy.tokenBudget]]. That last behaviour (retain + re-offer tools, cache
 * the prefix) is not type-enforceable; it is a law every provider must pass in the conformance suite (F2b/F2c).
 */
trait LlmProvider[F[_]] {
  def open(system: String, tools: List[ToolSpec], policy: LoopPolicy): Resource[F, LlmSession[F]]
}
