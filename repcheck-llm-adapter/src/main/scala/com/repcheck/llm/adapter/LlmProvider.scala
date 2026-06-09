package com.repcheck.llm.adapter

import cats.effect.Resource

import repcheck.shared.models.llm.agentic.LoopPolicy
import repcheck.shared.models.llm.tool.ToolSpec

/**
 * A tool-calling chat provider (Ollama now; Claude/OpenAI later). It [[open]]s a stateful [[LlmSession]] bound to the
 * static system prompt + tool catalogue. The session — not the provider — owns the exchange structure: it accumulates
 * history and hands the retained tools into the provider's wire round-trip (`LlmSession.respond`) on every turn. So a
 * provider implements only `respond`, and tool retention is structural: the framework supplies the tools each call and
 * the runner cannot re-send accumulated context (`exchange` accepts only the delta).
 *
 * The one behaviour types can't enforce is what the provider does with that context on the wire: it SHOULD cache the
 * stable prefix (Anthropic `cache_control`, Ollama context reuse) and/or window against
 * [[repcheck.shared.models.llm.agentic.LoopPolicy.tokenBudget]] rather than re-billing it each `respond`. That is a law
 * every provider must pass in the conformance suite (F2b/F2c).
 */
trait LlmProvider[F[_]] {
  def open(system: String, tools: List[ToolSpec], policy: LoopPolicy): Resource[F, LlmSession[F]]
}
