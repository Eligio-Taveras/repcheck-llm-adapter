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

  import DefaultAgenticLlmRunner.{LoopState, RunContext}

  def run[A](prompt: AssembledPrompt, tools: List[LlmTool[F]], policy: LoopPolicy)(using
    sc: StructuredCodec[A]
  ): F[AgenticResult[A]] = {
    val submit   = new SubmitTool[F, A](StructuredSchema.from[A])
    val allTools = submit :: tools
    val byName   = allTools.map(t => t.spec.name -> t).toMap
    val specs    = allTools.map(_.spec)
    UUIDGen[F].randomUUID.flatMap { correlationId =>
      loop(RunContext(correlationId, specs, byName, policy), LoopState(prompt, 0, Vector.empty))
    }
  }

  private def loop[A](ctx: RunContext[F], state: LoopState)(using sc: StructuredCodec[A]): F[AgenticResult[A]] =
    if (state.iteration >= ctx.policy.maxIterations) {
      Sync[F].raiseError(
        AgenticRunFailed(ctx.correlationId, state.iteration, "iteration budget exhausted without a valid submit")
      )
    } else {
      provider.chatWithTools(state.prompt, ctx.specs, ctx.policy).flatMap { turn =>
        val recorded = turn.copy(index = state.iteration)
        recorded.toolCalls.find(_.name == SubmitTool.Name) match {
          case Some(submitCall) => completeOrRetry(ctx, state, recorded, submitCall)
          case None             => dispatchThenContinue(ctx, state, recorded)
        }
      }
    }

  /** A `submit` ends the loop when its arguments satisfy the output schema; otherwise re-prompt and keep going. */
  private def completeOrRetry[A](
    ctx: RunContext[F],
    state: LoopState,
    recorded: Turn,
    submitCall: ToolCall,
  )(using sc: StructuredCodec[A]): F[AgenticResult[A]] =
    sc.decoder.decodeJson(submitCall.arguments) match {
      case Right(output) =>
        Sync[F].pure(
          AgenticResult(output, state.iteration + 1, (state.transcript :+ recorded).toList, ctx.correlationId)
        )
      case Left(failure) =>
        loop(ctx, state.advanced(state.prompt.appended(schemaRetryMessage(failure.getMessage)), recorded))
    }

  /** No `submit` yet: run every tool the model called, feed the results back, and continue the loop. */
  private def dispatchThenContinue[A](
    ctx: RunContext[F],
    state: LoopState,
    recorded: Turn,
  )(using sc: StructuredCodec[A]): F[AgenticResult[A]] =
    recorded.toolCalls.traverse(dispatchToolCall(ctx.byName)).flatMap { results =>
      loop(ctx, state.advanced(appendToolResults(state.prompt, results), recorded.copy(toolResults = results)))
    }

  private def dispatchToolCall(byName: Map[String, LlmTool[F]])(call: ToolCall): F[ToolResult] =
    byName.get(call.name) match {
      case None => Sync[F].pure(ToolResult(call.name, unknownToolError(call.name), isError = true))
      case Some(tool) =>
        tool.decode(call.arguments) match {
          case Left(err) => Sync[F].pure(ToolResult(call.name, err.asJson, isError = true))
          case Right(in) => tool.execute(in).map(out => ToolResult(call.name, tool.encodeResult(out), isError = false))
        }
    }

  private def appendToolResults(prompt: AssembledPrompt, results: List[ToolResult]): AssembledPrompt =
    results.foldLeft(prompt)((p, r) => p.appended(ChatMessage("tool", r.content.noSpaces)))

  private def schemaRetryMessage(message: String): ChatMessage =
    ChatMessage("user", s"Your submit did not match the required schema: $message. Re-emit a valid submit.")

  private def unknownToolError(name: String): Json = Json.obj("error" -> Json.fromString(s"unknown tool '$name'"))
}

object DefaultAgenticLlmRunner {

  /** Immutable context for one `run`: the correlation id, the advertised tool specs, the tool registry, the policy. */
  final private case class RunContext[F[_]](
    correlationId: UUID,
    specs: List[ToolSpec],
    byName: Map[String, LlmTool[F]],
    policy: LoopPolicy,
  )

  /** Per-iteration loop state: the running prompt, how many turns have elapsed, and the transcript so far. */
  final private case class LoopState(prompt: AssembledPrompt, iteration: Int, transcript: Vector[Turn]) {

    def advanced(nextPrompt: AssembledPrompt, completed: Turn): LoopState =
      LoopState(nextPrompt, iteration + 1, transcript :+ completed)

  }

}
