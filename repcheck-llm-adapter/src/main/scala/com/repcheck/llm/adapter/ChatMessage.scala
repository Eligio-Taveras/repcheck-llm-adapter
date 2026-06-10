package com.repcheck.llm.adapter

import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder}

/** One message in the conversation sent to a provider. */
final case class ChatMessage(role: String, content: String)

object ChatMessage {
  given Encoder[ChatMessage] = deriveEncoder[ChatMessage]
  given Decoder[ChatMessage] = deriveDecoder[ChatMessage]
}
