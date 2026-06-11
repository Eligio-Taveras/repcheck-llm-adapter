package com.repcheck.llm.adapter.ollama

/** Terminal wrapper raised by the retry layer: the wire call failed systemically or exhausted its retries. */
final case class OllamaChatRequestFailed(detail: String, underlying: Throwable)
    extends Exception(s"Ollama chat request failed: $detail", underlying)
