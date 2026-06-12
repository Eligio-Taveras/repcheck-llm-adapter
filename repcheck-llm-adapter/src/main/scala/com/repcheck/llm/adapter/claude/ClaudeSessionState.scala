package com.repcheck.llm.adapter.claude

import com.anthropic.models.messages.MessageParam

/**
 * What the session has sent so far: how many base-conversation messages are already in `transcript` (each turn packs
 * them into one user param, and assistant replies are interleaved), the `tool_use` ids of the LAST assistant reply that
 * the next delta's results must answer (Anthropic rejects a request whose tool_use blocks go unanswered), the
 * cumulative token spend for the `LoopPolicy.tokenBudget` window guard, and how many turns have completed (the next
 * `Turn`'s index).
 */
final private[claude] case class ClaudeSessionState(
  consumed: Int,
  transcript: Vector[MessageParam],
  pendingToolUseIds: List[String],
  spentTokens: Long,
  completedTurns: Int,
) {

  /**
   * State after a successful turn: the user param we sent and the verbatim assistant reply retained, the reply's
   * tool_use ids now pending, token spend accumulated. Dropping `consumed` upstream is total because
   * `LlmSession.exchange` is final and append-only — the prior prefix never changes.
   */
  def advancedBy(conversationLength: Int, sentUser: MessageParam, reply: ClaudeChatReply): ClaudeSessionState =
    ClaudeSessionState(
      conversationLength,
      transcript :+ sentUser :+ reply.assistantMessage,
      reply.toolUseIds,
      spentTokens + reply.spentTokens,
      completedTurns + 1,
    )

}

private[claude] object ClaudeSessionState {
  val initial: ClaudeSessionState = ClaudeSessionState(0, Vector.empty, Nil, 0L, 0)
}
