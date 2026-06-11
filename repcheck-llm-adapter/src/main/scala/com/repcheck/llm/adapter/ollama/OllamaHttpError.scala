package com.repcheck.llm.adapter.ollama

/** A non-2xx reply from `/api/chat`. The status code drives retry classification (429/5xx are transient). */
final case class OllamaHttpError(status: Int, body: String)
    extends Exception(s"Ollama /api/chat returned HTTP $status: $body")
