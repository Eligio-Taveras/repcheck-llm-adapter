package com.repcheck.llm.adapter.claude

import java.util.UUID

import cats.effect.syntax.temporal._
import cats.effect.{Async, Ref}
import cats.syntax.all._

import com.anthropic.client.AnthropicClient
import repcheck.shared.models.llm.agentic.{LoopPolicy, Turn}
import repcheck.shared.models.llm.prompt.ChatMessage
import repcheck.shared.models.llm.tool.ToolSpec

import com.repcheck.llm.adapter.LlmSession
import com.repcheck.utils.errors.RetryWrapper

/**
 * One Claude Messages API conversation. Each turn packs the delta into a single user param (tool results paired to the
 * pending `tool_use` ids in order — see [[ClaudeWire.userMessage]]), replays the retained transcript verbatim, and lets
 * the `cache_control` breakpoints (static system block + moving last-message marker) keep the growing prefix served
 * from the server's prompt cache. Before every call the cumulative token spend is checked against
 * `LoopPolicy.tokenBudget` (the window guard).
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
) extends LlmSession[F](system, tools, policy, history) {

  protected def respond(
    system: String,
    tools: List[ToolSpec],
    policy: LoopPolicy,
    conversation: List[ChatMessage],
  ): F[Turn] =
    for {
      current <- state.get
      _       <- raiseWhenBudgetSpent(policy, current)
      newUser = ClaudeWire.userMessage(conversation.drop(current.consumed), current.pendingToolUseIds)
      reply <- retried(createMessage(system, tools, policy, current, newUser))
      _     <- state.set(current.advancedBy(conversation.length, newUser, reply))
    } yield Turn(current.completedTurns, reply.toolCalls, Nil)

  /** `tokenBudget = None` is the unlimited mode — no window guard, the session never refuses a call. */
  private[claude] def raiseWhenBudgetSpent(policy: LoopPolicy, current: ClaudeSessionState): F[Unit] =
    policy.tokenBudget match {
      case None => Async[F].unit
      case Some(budget) if current.spentTokens >= budget =>
        Async[F].raiseError(ClaudeTokenBudgetExhausted(correlationId, current.spentTokens, budget))
      case Some(_) => Async[F].unit
    }

  private def retried(call: F[ClaudeChatReply]): F[ClaudeChatReply] =
    retry.withRetry(call, config.retry, ClaudeErrorClassifier, ClaudeChatRequestFailed.apply, correlationId)

  private def createMessage(
    system: String,
    tools: List[ToolSpec],
    policy: LoopPolicy,
    current: ClaudeSessionState,
    newUser: com.anthropic.models.messages.MessageParam,
  ): F[ClaudeChatReply] = {
    val params = ClaudeWire.requestParams(config, system, tools, current.transcript, newUser)
    // timeoutAndForget, not timeout: the SDK call is uncancelable blocking IO, so a plain timeout would wait for it
    // and return the late result; this raises TimeoutException at the deadline and abandons the call in background
    Async[F]
      .blocking(client.messages().create(params))
      .map(ClaudeWire.decode)
      .timeoutAndForget(config.requestTimeout.min(policy.perCallTimeout))
  }

}
