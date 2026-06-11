package com.repcheck.llm.adapter.ollama

import io.circe.Json

import repcheck.shared.models.llm.tool.ToolCall

/**
 * One decoded `/api/chat` reply. `assistantMessage` is the verbatim `message` object from the wire — it is appended to
 * the session transcript untouched so the next request's prefix is byte-identical (what lets the server reuse its KV
 * prefix cache instead of re-evaluating the conversation).
 */
final private[ollama] case class OllamaChatReply(
  assistantMessage: Json,
  toolCalls: List[ToolCall],
  promptEvalCount: Long,
  evalCount: Long,
)
