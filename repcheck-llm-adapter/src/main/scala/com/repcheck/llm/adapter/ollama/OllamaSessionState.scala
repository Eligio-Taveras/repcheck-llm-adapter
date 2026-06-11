package com.repcheck.llm.adapter.ollama

import io.circe.Json

/**
 * What the session has put on the wire so far: how many base-conversation messages are already in `transcript`
 * (assistant replies are interleaved, so the transcript is longer than the base history), and the cumulative token
 * spend for the `LoopPolicy.tokenBudget` window guard.
 */
final private[ollama] case class OllamaSessionState(consumed: Int, transcript: Vector[Json], spentTokens: Long)

private[ollama] object OllamaSessionState {
  val initial: OllamaSessionState = OllamaSessionState(0, Vector.empty, 0L)
}
