package com.repcheck.llm.adapter.claude

import java.util.UUID

import cats.effect.{Async, Ref}
import cats.syntax.all._

import com.anthropic.client.AnthropicClient
import repcheck.shared.models.llm.agentic.LoopPolicy
import repcheck.shared.models.llm.prompt.ChatMessage
import repcheck.shared.models.llm.tool.{ToolCall, ToolSpec}

import com.repcheck.llm.adapter.ProviderLlmSession
import com.repcheck.utils.errors.RetryWrapper

/**
 * One Claude Messages API conversation. Each turn packs the delta into a single user param (tool results paired to the
 * pending `tool_use` ids in order — see [[ClaudeWire.userMessage]]), replays the retained transcript verbatim, and lets
 * the `cache_control` breakpoints (static system block + moving last-message marker) keep the growing prefix served
 * from the server's prompt cache. Budget window, retry, and timeout structure come from [[ProviderLlmSession]].
 */
final private[claude] class ClaudeSession[F[_]: Async](
  client: AnthropicClient,
  config: ClaudeConfig,
  retry: RetryWrapper[F],
  correlationId: UUID,
  system: String,
  tools: List[ToolSpec],
  policy: LoopPolicy,
  history: Ref[F, List[ChatMessage]],
  state: Ref[F, ClaudeSessionState],
) extends ProviderLlmSession[F, ClaudeSessionState](
      retry,
      config.retry,
      ClaudeErrorClassifier,
      ClaudeChatRequestFailed.apply,
      ClaudeTokenBudgetExhausted.apply,
      config.requestTimeout,
      correlationId,
      system,
      tools,
      policy,
      history,
      state,
    ) {

  protected def spentTokens(current: ClaudeSessionState): Long   = current.spentTokens
  protected def completedTurns(current: ClaudeSessionState): Int = current.completedTurns

  protected def callModel(
    system: String,
    tools: List[ToolSpec],
    policy: LoopPolicy,
    conversation: List[ChatMessage],
    current: ClaudeSessionState,
  ): F[(List[ToolCall], ClaudeSessionState)] = {
    val newUser = ClaudeWire.userMessage(conversation.drop(current.consumed), current.pendingToolUseIds)
    val params  = ClaudeWire.requestParams(config, system, tools, current.transcript, newUser)
    Async[F]
      .blocking(client.messages().create(params))
      .map(ClaudeWire.decode)
      .map(reply => (reply.toolCalls, current.advancedBy(conversation.length, newUser, reply)))
  }

}
