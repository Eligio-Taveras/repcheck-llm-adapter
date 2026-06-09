package com.repcheck.llm.adapter

import java.util.UUID

import cats.effect.Sync
import cats.effect.std.UUIDGen
import cats.syntax.all._

import io.circe.Json
import io.circe.syntax._

import repcheck.shared.models.llm.agentic.{AgenticResult, LoopPolicy, Turn}
import repcheck.shared.models.llm.codec.{StructuredCodec, StructuredSchema}
import repcheck.shared.models.llm.tool.{LlmTool, ToolCall, ToolResult, ToolSpec}

/**
 * Default bounded agentic loop (D19). Injects the terminal `submit` tool (its input_schema is the output schema),
 * drives the provider, dispatches the typed tools the model calls (decode -> execute -> encodeResult), resends the
 * results, and repeats until a schema-valid `submit` or the LoopPolicy budget is hit — then fails with
 * [[AgenticRunFailed]] (never returns an unstructured result). Performs no mutation itself; tools own any effects.
 */
final class DefaultAgenticLlmRunner[F[_]: Sync: UUIDGen](provider: LlmProvider[F]) extends AgenticLlmRunner[F] {

  def run[A](prompt: AssembledPrompt, tools: List[LlmTool[F]], policy: LoopPolicy)(using
    sc: StructuredCodec[A]
  ): F[AgenticResult[A]] = {
    val submit   = new SubmitTool[F, A](StructuredSchema.from[A])
    val allTools = submit :: tools
    val byName   = allTools.map(t => t.spec.name -> t).toMap
    val specs    = allTools.map(_.spec)
    UUIDGen[F].randomUUID.flatMap(cid => loop(cid, prompt, specs, byName, policy, 0, Vector.empty))
  }

  private def loop[A](
    correlationId: UUID,
    prompt: AssembledPrompt,
    specs: List[ToolSpec],
    byName: Map[String, LlmTool[F]],
    policy: LoopPolicy,
    iteration: Int,
    transcript: Vector[Turn],
  )(using sc: StructuredCodec[A]): F[AgenticResult[A]] =
    if (iteration >= policy.maxIterations) {
      Sync[F].raiseError(
        AgenticRunFailed(correlationId, iteration, "iteration budget exhausted without a valid submit")
      )
    } else {
      provider.chatWithTools(prompt, specs, policy).flatMap { turn =>
        val recorded = turn.copy(index = iteration)
        recorded.toolCalls.find(_.name == SubmitTool.Name) match {
          case Some(submitCall) =>
            sc.decoder.decodeJson(submitCall.arguments) match {
              case Right(a) =>
                Sync[F].pure(AgenticResult(a, iteration + 1, (transcript :+ recorded).toList, correlationId))
              case Left(failure) =>
                val next = prompt.appended(decodeFeedback(failure.getMessage))
                loop(correlationId, next, specs, byName, policy, iteration + 1, transcript :+ recorded)
            }
          case None =>
            recorded.toolCalls.traverse(dispatch(byName)).flatMap { results =>
              val withResults = recorded.copy(toolResults = results)
              loop(
                correlationId,
                appendResults(prompt, results),
                specs,
                byName,
                policy,
                iteration + 1,
                transcript :+ withResults,
              )
            }
        }
      }
    }

  private def dispatch(byName: Map[String, LlmTool[F]])(call: ToolCall): F[ToolResult] =
    byName.get(call.name) match {
      case None => Sync[F].pure(ToolResult(call.name, errorJson(s"unknown tool '${call.name}'"), isError = true))
      case Some(tool) =>
        tool.decode(call.arguments) match {
          case Left(err) => Sync[F].pure(ToolResult(call.name, err.asJson, isError = true))
          case Right(in) => tool.execute(in).map(out => ToolResult(call.name, tool.encodeResult(out), isError = false))
        }
    }

  private def appendResults(prompt: AssembledPrompt, results: List[ToolResult]): AssembledPrompt =
    results.foldLeft(prompt)((p, r) => p.appended(ChatMessage("tool", r.content.noSpaces)))

  private def decodeFeedback(message: String): ChatMessage =
    ChatMessage("user", s"Your submit did not match the required schema: $message. Re-emit a valid submit.")

  private def errorJson(message: String): Json = Json.obj("error" -> Json.fromString(message))
}
