package com.repcheck.llm.adapter.ollama

import java.util.UUID

/**
 * The session's cumulative token spend reached `LoopPolicy.tokenBudget` before another wire call could be made — the
 * provider-side window guard (F2b conformance law), so a runaway loop cannot bill unbounded tokens.
 */
final case class OllamaTokenBudgetExhausted(correlationId: UUID, spentTokens: Long, budget: Int)
    extends Exception(
      s"Ollama session $correlationId spent $spentTokens tokens, reaching the LoopPolicy budget of $budget"
    )
