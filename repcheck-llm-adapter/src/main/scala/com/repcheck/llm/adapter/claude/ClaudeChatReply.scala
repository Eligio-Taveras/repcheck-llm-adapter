package com.repcheck.llm.adapter.claude

import com.anthropic.models.messages.MessageParam
import repcheck.shared.models.llm.tool.ToolCall

/**
 * One decoded Messages API reply. Everything the session INTERPRETS is structured here: `toolCalls` (decoded inputs),
 * the `toolUseIds` the next delta's tool results must answer (in order), and the token accounting. `assistantMessage`
 * is the SDK's own `Message.toParam()` of the response — appended to the transcript untouched so the conversation
 * prefix replays exactly and the server's prompt cache keeps hitting.
 *
 * @param spentTokens
 *   all tokens the call processed — input + output + cache creation + cache reads — mirroring the Ollama provider's
 *   prompt_eval + eval semantics for the `LoopPolicy.tokenBudget` window guard
 */
final private[claude] case class ClaudeChatReply(
  assistantMessage: MessageParam,
  toolCalls: List[ToolCall],
  toolUseIds: List[String],
  spentTokens: Long,
  cacheCreationTokens: Long,
  cacheReadTokens: Long,
)
