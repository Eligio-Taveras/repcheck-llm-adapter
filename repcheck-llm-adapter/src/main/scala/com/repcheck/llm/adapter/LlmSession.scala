package com.repcheck.llm.adapter

import repcheck.shared.models.llm.agentic.Turn

/**
 * A live, stateful conversation with a model, bound to a fixed system prompt + tool catalogue at [[LlmProvider.open]].
 * The session — not the caller — retains all prior turns and the tool catalogue, and owns their wire representation
 * (prompt caching / windowing). Callers advance it with ONLY the new messages each turn; there is deliberately no way
 * to hand it the full history or the tool list again, so the runner cannot re-send accumulated context.
 *
 * Note the structural limit: this seam guarantees the *caller* never re-sends context or tools, but it cannot force an
 * implementation to actually re-offer the retained tools or cache the stable prefix on the wire. Those are behavioural
 * laws verified by the provider conformance suite against a live provider (F2b Ollama / F2c Claude).
 */
trait LlmSession[F[_]] {
  def exchange(newMessages: List[ChatMessage]): F[Turn]
}
