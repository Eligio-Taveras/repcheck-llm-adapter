package com.repcheck.llm.adapter.ollama

import cats.effect.std.UUIDGen
import cats.effect.{Async, Ref, Resource}
import cats.syntax.all._

import org.http4s.client.Client

import repcheck.shared.models.llm.agentic.LoopPolicy
import repcheck.shared.models.llm.prompt.ChatMessage
import repcheck.shared.models.llm.tool.ToolSpec

import com.repcheck.llm.adapter.{LlmProvider, LlmSession}
import com.repcheck.utils.errors.RetryWrapper

/**
 * Tool-calling provider over the Ollama `/api/chat` endpoint. The injected `client` owns connection pooling and connect
 * timeouts; per-call latency is bounded by `min(config.requestTimeout, policy.perCallTimeout)` and transient failures
 * (timeouts, refused connections, 429/5xx) are retried via the shared [[RetryWrapper]].
 */
final class OllamaLlmProvider[F[_]: Async: UUIDGen](client: Client[F], config: OllamaConfig, retry: RetryWrapper[F])
    extends LlmProvider[F] {

  def open(system: String, tools: List[ToolSpec], policy: LoopPolicy): Resource[F, LlmSession[F]] =
    Resource.eval(
      for {
        correlationId <- UUIDGen.randomUUID[F]
        history       <- Ref[F].of(List.empty[ChatMessage])
        state         <- Ref[F].of(OllamaSessionState.initial)
      } yield new OllamaSession[F](client, config, retry, correlationId, system, tools, policy, history, state)
    )

}
