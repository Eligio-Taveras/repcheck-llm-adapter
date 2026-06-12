package com.repcheck.llm.adapter.claude

/** Terminal wrapper raised by the retry layer: the Messages API call failed systemically or exhausted its retries. */
final case class ClaudeChatRequestFailed(detail: String, underlying: Throwable)
    extends Exception(s"Claude /v1/messages request failed: $detail", underlying)
