package com.repcheck.llm.adapter.claude

import java.util.UUID

/**
 * The session's cumulative token spend reached `LoopPolicy.tokenBudget` before another Messages API call could be made
 * — the provider-side window guard (the F2 conformance law), so a runaway loop cannot bill unbounded tokens.
 */
final case class ClaudeTokenBudgetExhausted(correlationId: UUID, spentTokens: Long, budget: Int)
    extends Exception(
      s"Claude session $correlationId spent $spentTokens tokens, reaching the LoopPolicy budget of $budget"
    )
