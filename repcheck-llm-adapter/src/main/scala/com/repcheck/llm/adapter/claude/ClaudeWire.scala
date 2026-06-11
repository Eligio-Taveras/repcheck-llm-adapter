package com.repcheck.llm.adapter.claude

import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._

import com.anthropic.models.messages.{
  CacheControlEphemeral,
  ContentBlockParam,
  Message,
  MessageCreateParams,
  MessageParam,
  TextBlockParam,
  Tool,
  ToolChoiceAny,
  ToolResultBlockParam,
}
import repcheck.shared.models.llm.prompt.ChatMessage
import repcheck.shared.models.llm.tool.{ToolCall, ToolSpec}

import com.repcheck.llm.adapter.ToolDescriptions

/**
 * Messages API request/response mapping — kept side-effect free so every wire branch is directly testable.
 *
 * Caching strategy (the F2c half of the context-efficiency law): one `cache_control` breakpoint on the system block
 * (covers tools + system, the static prefix) and one MOVING breakpoint on the last block of each request's new user
 * message — the documented incremental multi-turn pattern. The transcript itself stores params WITHOUT markers; the
 * breakpoint is applied only at request-build time, so old turns never accumulate markers (Anthropic caps 4 per
 * request) and the server keeps matching the longest previously cached prefix.
 */
private[claude] object ClaudeWire {

  private val breakpoint = CacheControlEphemeral.builder().build()

  def requestParams(
    config: ClaudeConfig,
    system: String,
    tools: List[ToolSpec],
    transcript: Vector[MessageParam],
    newUser: MessageParam,
  ): MessageCreateParams = {
    val builder = MessageCreateParams
      .builder()
      .model(config.model)
      .maxTokens(config.maxTokens.toLong)
      .systemOfTextBlockParams(List(TextBlockParam.builder().text(system).cacheControl(breakpoint).build()).asJava)
    // the API rejects tool_choice without tools; with tools present, force a tool call (structured output, D21)
    if (tools.nonEmpty) {
      builder.toolChoice(ToolChoiceAny.builder().build())
      ()
    }
    tools.foreach(spec => builder.addTool(toolOf(spec)))
    (transcript :+ withBreakpointOnLastBlock(newUser)).foreach(builder.addMessage)
    builder.build()
  }

  /** Tool defs carry name/description/input_schema only — the D21 example/result shapes ride in the description. */
  def toolOf(spec: ToolSpec): Tool = {
    val schema = Tool.InputSchema
      .builder()
      .`type`(ClaudeJson.toJsonValue(io.circe.Json.fromString("object")))
    spec.parametersSchema.asObject.toList
      .flatMap(_.toList)
      .filterNot { case (key, _) => key == "type" }
      .foreach { case (key, value) => schema.putAdditionalProperty(key, ClaudeJson.toJsonValue(value)) }
    Tool
      .builder()
      .name(spec.name)
      .description(ToolDescriptions.withShapes(spec))
      .inputSchema(schema.build())
      .build()
  }

  /**
   * Packs the delta into ONE user message. The pairing invariant: the runner answers the last assistant turn's tool_use
   * blocks in order, so the nth delta message answers `pendingToolUseIds(n)` — a role-"tool" message as a normal
   * tool_result, anything else (the runner's schema-retry feedback after a bad submit) as an is_error tool_result. With
   * no ids left (or none pending — the opening prompt), messages become plain text blocks.
   */
  def userMessage(newMessages: List[ChatMessage], pendingToolUseIds: List[String]): MessageParam = {
    val blocks = newMessages.zipWithIndex.map {
      case (message, index) =>
        pendingToolUseIds.lift(index) match {
          case Some(toolUseId) => toolResultBlock(toolUseId, message)
          case None            => ContentBlockParam.ofText(TextBlockParam.builder().text(message.content).build())
        }
    }
    MessageParam
      .builder()
      .role(MessageParam.Role.USER)
      .contentOfBlockParams(blocks.asJava)
      .build()
  }

  def decode(message: Message): ClaudeChatReply = {
    val toolUses      = message.content().asScala.toList.flatMap(_.toolUse().toScala)
    val usage         = message.usage()
    val cacheCreation = usage.cacheCreationInputTokens().toScala.map(Long.unbox).getOrElse(0L)
    val cacheRead     = usage.cacheReadInputTokens().toScala.map(Long.unbox).getOrElse(0L)
    ClaudeChatReply(
      assistantMessage = message.toParam(),
      toolCalls = toolUses.map(block => ToolCall(block.name(), ClaudeJson.toCirce(block._input()))),
      toolUseIds = toolUses.map(_.id()),
      spentTokens = usage.inputTokens() + usage.outputTokens() + cacheCreation + cacheRead,
      cacheCreationTokens = cacheCreation,
      cacheReadTokens = cacheRead,
    )
  }

  private def toolResultBlock(toolUseId: String, message: ChatMessage): ContentBlockParam = {
    val builder = ToolResultBlockParam.builder().toolUseId(toolUseId).content(message.content)
    ContentBlockParam.ofToolResult(
      (if (message.role == "tool") builder else builder.isError(true)).build()
    )
  }

  /** The moving cache breakpoint: re-create the new user message with `cache_control` on its final block. */
  private[claude] def withBreakpointOnLastBlock(user: MessageParam): MessageParam = {
    val blocks = user.content().blockParams().toScala.map(_.asScala.toList).getOrElse(Nil)
    blocks.reverse match {
      case last :: earlier =>
        MessageParam
          .builder()
          .role(MessageParam.Role.USER)
          .contentOfBlockParams((earlier.reverse :+ markBlock(last)).asJava)
          .build()
      case Nil => user
    }
  }

  private def markBlock(block: ContentBlockParam): ContentBlockParam = {
    val asToolResult = block
      .toolResult()
      .toScala
      .map(tr => ContentBlockParam.ofToolResult(tr.toBuilder().cacheControl(breakpoint).build()))
    val asText = block
      .text()
      .toScala
      .map(t => ContentBlockParam.ofText(t.toBuilder().cacheControl(breakpoint).build()))
    asToolResult.orElse(asText).getOrElse(block)
  }

}
