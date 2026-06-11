package com.repcheck.llm.adapter.ollama

import io.circe.Json

import repcheck.shared.models.llm.prompt.ChatMessage

/**
 * What the session has put on the wire so far: how many base-conversation messages are already in `transcript`
 * (assistant replies are interleaved, so the transcript is longer than the base history), and the cumulative token
 * spend for the `LoopPolicy.tokenBudget` window guard.
 */
final private[ollama] case class OllamaSessionState(consumed: Int, transcript: Vector[Json], spentTokens: Long) {

  /**
   * The wire view of `conversation`: the retained transcript plus the not-yet-wired base messages. Dropping `consumed`
   * is total and correct because `LlmSession.exchange` is final and append-only — the prior prefix never changes.
   */
  def extendedWith(conversation: List[ChatMessage]): Vector[Json] =
    transcript ++ conversation.drop(consumed).map(OllamaWire.wireMessage)

  /** State after a successful turn: assistant reply retained verbatim (cacheable prefix), token spend accumulated. */
  def advancedBy(conversationLength: Int, extended: Vector[Json], reply: OllamaChatReply): OllamaSessionState =
    OllamaSessionState(
      conversationLength,
      extended :+ reply.assistantMessage,
      spentTokens + reply.promptEvalCount + reply.evalCount,
    )

}

private[ollama] object OllamaSessionState {
  val initial: OllamaSessionState = OllamaSessionState(0, Vector.empty, 0L)
}
