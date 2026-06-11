package com.repcheck.llm.adapter.ollama

import io.circe.Json

import repcheck.shared.models.llm.tool.ToolCall

/**
 * One decoded `/api/chat` reply. Everything the session INTERPRETS is already structured here: `toolCalls` (decoded,
 * arguments normalized to Json objects) and the token counts. `assistantMessage` is deliberately NOT a model — it is
 * the verbatim `message` object from the wire, never read again, only appended to the session transcript untouched so
 * the next request's prefix is byte-identical (decoding and re-encoding could reorder keys and break the server's KV
 * prefix-cache reuse). Typed interpretation of tool calls happens downstream where the types live: the runner decodes
 * each call's arguments via its `LlmTool`/`StructuredCodec`.
 */
final private[ollama] case class OllamaChatReply(
  assistantMessage: Json,
  toolCalls: List[ToolCall],
  promptEvalCount: Long,
  evalCount: Long,
)
