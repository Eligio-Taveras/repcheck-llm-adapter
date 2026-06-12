package com.repcheck.llm.adapter

import java.util.UUID

import scala.concurrent.duration.FiniteDuration

import cats.effect.syntax.temporal._
import cats.effect.{Async, Ref}
import cats.syntax.all._

import repcheck.shared.models.llm.agentic.{LoopPolicy, Turn}
import repcheck.shared.models.llm.prompt.ChatMessage
import repcheck.shared.models.llm.tool.{ToolCall, ToolSpec}

import com.repcheck.utils.errors.{ErrorClassifier, RetryConfig, RetryWrapper}

/**
 * The provider-independent turn skeleton: every concrete provider repeats the same structure — check the token-budget
 * window, make the retried wire call bounded by `min(requestTimeout, policy.perCallTimeout)`, advance the session
 * state, and report the real per-session turn index. Subclasses supply only the wire round-trip ([[callModel]]) and
 * their state's accounting view; transport, message encoding, and error vocabulary are the only provider-specific
 * concerns (each provider keeps its own flat exceptions and classifier, passed in as factories).
 *
 * `timeoutAndForget`, not `timeout`: a wire call may be uncancelable blocking IO (the Anthropic SDK), where a plain
 * timeout waits the call out and returns the late result; this raises at the deadline and abandons the call in the
 * background.
 */
abstract class ProviderLlmSession[F[_]: Async, S](
  retry: RetryWrapper[F],
  retryConfig: RetryConfig,
  classifier: ErrorClassifier,
  wireFailure: (String, Throwable) => Throwable,
  budgetExhausted: (UUID, Long, Int) => Throwable,
  requestTimeout: FiniteDuration,
  correlationId: UUID,
  system: String,
  tools: List[ToolSpec],
  policy: LoopPolicy,
  history: Ref[F, List[ChatMessage]],
  state: Ref[F, S],
) extends LlmSession[F](system, tools, policy, history) {

  /** Cumulative token spend — drives the `LoopPolicy.tokenBudget` window guard. */
  protected def spentTokens(current: S): Long

  /** Turns completed so far — the next `Turn`'s index. */
  protected def completedTurns(current: S): Int

  /** The provider wire round-trip: the model's tool calls for this turn plus the advanced session state. */
  protected def callModel(
    system: String,
    tools: List[ToolSpec],
    policy: LoopPolicy,
    conversation: List[ChatMessage],
    current: S,
  ): F[(List[ToolCall], S)]

  final protected def respond(
    system: String,
    tools: List[ToolSpec],
    policy: LoopPolicy,
    conversation: List[ChatMessage],
  ): F[Turn] =
    for {
      current <- state.get
      _       <- raiseWhenBudgetSpent(policy, current)
      outcome <- retried(
        callModel(system, tools, policy, conversation, current)
          .timeoutAndForget(requestTimeout.min(policy.perCallTimeout))
      )
      (toolCalls, advanced) = outcome
      _ <- state.set(advanced)
    } yield Turn(completedTurns(current), toolCalls, Nil)

  /** `tokenBudget = None` is the unlimited mode — no window guard, the session never refuses a call. */
  private def raiseWhenBudgetSpent(policy: LoopPolicy, current: S): F[Unit] =
    policy.tokenBudget match {
      case None => Async[F].unit
      case Some(budget) if spentTokens(current) >= budget =>
        Async[F].raiseError(budgetExhausted(correlationId, spentTokens(current), budget))
      case Some(_) => Async[F].unit
    }

  private def retried[A](call: F[A]): F[A] =
    retry.withRetry(call, retryConfig, classifier, wireFailure, correlationId)

}
