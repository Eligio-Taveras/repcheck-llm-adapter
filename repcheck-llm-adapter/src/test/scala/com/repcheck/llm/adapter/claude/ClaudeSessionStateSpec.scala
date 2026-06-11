package com.repcheck.llm.adapter.claude

import io.circe.Json

import com.anthropic.models.messages.MessageParam
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import repcheck.shared.models.llm.prompt.ChatMessage
import repcheck.shared.models.llm.tool.ToolCall

class ClaudeSessionStateSpec extends AnyFlatSpec with Matchers {

  private val userParam: MessageParam      = ClaudeWire.userMessage(List(ChatMessage("user", "go")), Nil)
  private val assistantParam: MessageParam = ClaudeWire.userMessage(List(ChatMessage("user", "stand-in")), Nil)

  private val reply = ClaudeChatReply(
    assistantMessage = assistantParam,
    toolCalls = List(ToolCall("echo", Json.obj())),
    toolUseIds = List("tu_7"),
    spentTokens = 15L,
    cacheCreationTokens = 0L,
    cacheReadTokens = 0L,
  )

  "advancedBy" should "retain sent user + assistant params, adopt the new pending ids, accumulate spend, count the turn" in {
    val advanced = ClaudeSessionState(0, Vector.empty, List("tu_old"), 7L, 2).advancedBy(1, userParam, reply)
    advanced shouldBe ClaudeSessionState(
      consumed = 1,
      transcript = Vector(userParam, assistantParam),
      pendingToolUseIds = List("tu_7"),
      spentTokens = 22L,
      completedTurns = 3,
    )
  }

}
