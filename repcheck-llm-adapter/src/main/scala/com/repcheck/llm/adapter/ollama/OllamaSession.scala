package com.repcheck.llm.adapter.ollama

import java.util.UUID

import cats.effect.syntax.temporal._
import cats.effect.{Async, Ref}
import cats.syntax.all._

import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.{Method, Request}

import repcheck.shared.models.llm.agentic.{LoopPolicy, Turn}
import repcheck.shared.models.llm.prompt.ChatMessage
import repcheck.shared.models.llm.tool.ToolSpec

import com.repcheck.llm.adapter.LlmSession
import com.repcheck.utils.errors.RetryWrapper

/**
 * One Ollama `/api/chat` conversation. The wire transcript is append-only — each turn adds only the new base messages
 * and the verbatim assistant reply, so the request prefix stays byte-identical and the server's KV prefix cache (kept
 * warm by `keep_alive`) does the context caching; nothing is ever re-encoded or re-ordered. Before every call the
 * cumulative token spend is checked against `LoopPolicy.tokenBudget` (the F2b window guard).
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
      transcript = current.transcript ++ conversation.drop(current.consumed).map(OllamaWire.wireMessage)
      reply <- retried(postChat(system, tools, policy, transcript))
      _ <- state.set(
        OllamaSessionState(
          conversation.length,
          transcript :+ reply.assistantMessage,
          current.spentTokens + reply.promptEvalCount + reply.evalCount,
        )
      )
    } yield Turn(0, reply.toolCalls, Nil)

  private[ollama] def raiseWhenBudgetSpent(policy: LoopPolicy, current: OllamaSessionState): F[Unit] =
    policy.tokenBudget match {
      case Some(budget) if current.spentTokens >= budget =>
        Async[F].raiseError(OllamaTokenBudgetExhausted(correlationId, current.spentTokens, budget))
      case _ => Async[F].unit
    }

  private def retried(call: F[OllamaChatReply]): F[OllamaChatReply] =
    retry.withRetry(call, config.retry, OllamaErrorClassifier, OllamaChatRequestFailed.apply, correlationId)

  private def postChat(
    system: String,
    tools: List[ToolSpec],
    policy: LoopPolicy,
    transcript: Vector[io.circe.Json],
  ): F[OllamaChatReply] = {
    val request = Request[F](Method.POST, OllamaWire.chatUri(config.baseUri))
      .withEntity(OllamaWire.requestJson(config, system, tools, transcript))
    client
      .run(request)
      .use { response =>
        response.bodyText.compile.string.flatMap { rawBody =>
          if (response.status.isSuccess) {
            OllamaWire.parseReply(rawBody).liftTo[F]
          } else {
            Async[F].raiseError(OllamaHttpError(response.status.code, rawBody))
          }
        }
      }
      .timeout(config.requestTimeout.min(policy.perCallTimeout))
  }

}
