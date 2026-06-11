package com.repcheck.llm.adapter.ollama

/** A 2xx reply whose body is not the expected `/api/chat` shape (bad JSON or a missing `message`). */
final case class OllamaResponseParseFailed(detail: String, rawBody: String)
    extends Exception(s"Ollama /api/chat response could not be parsed: $detail; body: $rawBody")
