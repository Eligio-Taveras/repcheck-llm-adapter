package com.repcheck.llm.adapter.ollama

import java.util.UUID

import cats.effect.{Async, Ref}
import cats.syntax.all._

import io.circe.Json

import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.{Method, Request}

import repcheck.shared.models.llm.agentic.LoopPolicy
import repcheck.shared.models.llm.prompt.ChatMessage
import repcheck.shared.models.llm.tool.{ToolCall, ToolSpec}

import com.repcheck.llm.adapter.ProviderLlmSession
import com.repcheck.utils.errors.RetryWrapper

/**
 * One Ollama `/api/chat` conversation. The wire transcript is append-only — each turn adds only the new base messages
 * and the verbatim assistant reply, so the request prefix stays byte-identical and the server's KV prefix cache (kept
 * warm by `keep_alive`) does the context caching; nothing is ever re-encoded or re-ordered. Budget window, retry, and
 * timeout structure come from [[ProviderLlmSession]].
 */
final private[ollama] class OllamaSession[F[_]: Async](
  client: Client[F],
  config: OllamaConfig,
  retry: RetryWrapper[F],
  correlationId: UUID,
  system: String,
  tools: List[ToolSpec],
  policy: LoopPolicy,
  history: Ref[F, List[ChatMessage]],
  state: Ref[F, OllamaSessionState],
) extends ProviderLlmSession[F, OllamaSessionState](
      retry,
      config.retry,
      OllamaErrorClassifier,
      OllamaChatRequestFailed.apply,
      OllamaTokenBudgetExhausted.apply,
      config.requestTimeout,
      correlationId,
      system,
      tools,
      policy,
      history,
      state,
    ) {

  protected def spentTokens(current: OllamaSessionState): Long   = current.spentTokens
  protected def completedTurns(current: OllamaSessionState): Int = current.completedTurns

  protected def callModel(
    system: String,
    tools: List[ToolSpec],
    policy: LoopPolicy,
    conversation: List[ChatMessage],
    current: OllamaSessionState,
  ): F[(List[ToolCall], OllamaSessionState)] = {
    val extended = current.extendedWith(conversation)
    postChat(system, tools, extended).map { reply =>
      (reply.toolCalls, current.advancedBy(conversation.length, extended, reply))
    }
  }

  private def postChat(system: String, tools: List[ToolSpec], transcript: Vector[Json]): F[OllamaChatReply] = {
    val request = Request[F](Method.POST, OllamaWire.chatUri(config.baseUri))
      .withEntity(OllamaWire.requestJson(config, system, tools, transcript))
    client.run(request).use { response =>
      response.bodyText.compile.string.flatMap { rawBody =>
        if (response.status.isSuccess) {
          OllamaWire.parseReply(rawBody).liftTo[F]
        } else {
          Async[F].raiseError(OllamaHttpError(response.status.code, rawBody))
        }
      }
    }
  }

}
