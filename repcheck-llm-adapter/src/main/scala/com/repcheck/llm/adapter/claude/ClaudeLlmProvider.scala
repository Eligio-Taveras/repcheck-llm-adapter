package com.repcheck.llm.adapter.claude

import cats.effect.std.UUIDGen
import cats.effect.{Async, Ref, Resource}
import cats.syntax.all._

import com.anthropic.client.AnthropicClient
import repcheck.shared.models.llm.agentic.LoopPolicy
import repcheck.shared.models.llm.prompt.ChatMessage
import repcheck.shared.models.llm.tool.ToolSpec

import com.repcheck.llm.adapter.{LlmProvider, LlmSession}
import com.repcheck.utils.errors.RetryWrapper

/**
 * Tool-calling provider over the Claude Messages API (forced tool use: `tool_choice = any`, so every reply is a
 * structured tool call — the terminal `submit` ends the loop). The injected `client` owns auth/transport and should
 * disable SDK retries (`maxRetries(0)`): transient failures (429/5xx, transport errors, timeouts) are retried by the
 * shared [[RetryWrapper]]; per-call latency is bounded by `min(config.requestTimeout, policy.perCallTimeout)`. The SDK
 * call is non-cancelable `blocking` — on timeout the fiber resumes but the underlying HTTP call may still run to
 * completion in the background.
 */
final class ClaudeLlmProvider[F[_]: Async: UUIDGen](
  client: AnthropicClient,
  config: ClaudeConfig,
  retry: RetryWrapper[F],
) extends LlmProvider[F] {

  def open(system: String, tools: List[ToolSpec], policy: LoopPolicy): Resource[F, LlmSession[F]] =
    Resource.eval(
      for {
        correlationId <- UUIDGen.randomUUID[F]
        history       <- Ref[F].of(List.empty[ChatMessage])
        state         <- Ref[F].of(ClaudeSessionState.initial)
      } yield new ClaudeSession[F](client, config, retry, correlationId, system, tools, policy, history, state)
    )

}
