package com.repcheck.llm.adapter

import cats.FlatMap
import cats.effect.Ref
import cats.syntax.all._

import repcheck.shared.models.llm.agentic.{LoopPolicy, Turn}
import repcheck.shared.models.llm.prompt.ChatMessage
import repcheck.shared.models.llm.tool.ToolSpec

/**
 * A stateful conversation with a model. The session holds the fixed system prompt, the tool catalogue, the loop policy,
 * and the accumulated history, and defines the exchange structure once: each [[exchange]] appends ONLY the new
 * messages, then hands the full conversation together with the retained `tools` to the provider's wire call
 * ([[respond]]). Two properties fall out structurally — a provider cannot forget to offer the tools (the framework
 * supplies them every turn), and the caller cannot re-send accumulated context (exchange accepts only the delta).
 *
 * What stays a provider responsibility (a conformance law, not type-enforceable): keeping that stable prefix cheap on
 * the wire via prompt caching / windowing against `policy.tokenBudget`, rather than re-billing it each `respond`.
 */
abstract class LlmSession[F[_]: FlatMap](
  system: String,
  tools: List[ToolSpec],
  policy: LoopPolicy,
  history: Ref[F, List[ChatMessage]],
) {

  final def exchange(newMessages: List[ChatMessage]): F[Turn] =
    history.updateAndGet(_ ++ newMessages).flatMap(conversation => respond(system, tools, policy, conversation))

  /**
   * The provider's per-turn wire round-trip: produce the next [[Turn]] from the retained system prompt + tools + policy
   * and the full conversation. Implementations SHOULD cache the stable prefix rather than re-billing it.
   */
  protected def respond(
    system: String,
    tools: List[ToolSpec],
    policy: LoopPolicy,
    conversation: List[ChatMessage],
  ): F[Turn]

}
