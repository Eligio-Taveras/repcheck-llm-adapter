package com.repcheck.llm.adapter

import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder}

/**
 * The prompt handed to the runner/provider: a system instruction + the running message list. The prompt-engine (F4)
 * assembles this; the runner appends tool results and re-prompt feedback as the loop progresses.
 */
final case class AssembledPrompt(system: String, messages: List[ChatMessage]) {
  def appended(message: ChatMessage): AssembledPrompt = copy(messages = messages :+ message)
}

object AssembledPrompt {
  given Encoder[AssembledPrompt] = deriveEncoder[AssembledPrompt]
  given Decoder[AssembledPrompt] = deriveDecoder[AssembledPrompt]
}
