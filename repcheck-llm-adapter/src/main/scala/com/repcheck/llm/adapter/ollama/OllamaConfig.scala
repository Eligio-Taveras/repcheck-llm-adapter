package com.repcheck.llm.adapter.ollama

import scala.concurrent.duration.FiniteDuration

import org.http4s.Uri

import pureconfig.ConfigReader
import pureconfig.error.CannotConvert

import com.repcheck.utils.errors.RetryConfig

/**
 * Provider-side Ollama knobs (§7c — every throughput knob is config, not code). Connect timeout is owned by whoever
 * builds the injected http4s `Client`, so it is deliberately absent here.
 *
 * @param keepAlive
 *   sent on every request so the model stays warm between turns — the server keeps the KV prefix cache alive, which is
 *   what makes the session's append-only transcript cheap on the wire
 */
final case class OllamaConfig(
  baseUri: Uri,
  model: String,
  numCtx: Int,
  keepAlive: FiniteDuration,
  requestTimeout: FiniteDuration,
  retry: RetryConfig,
) derives ConfigReader

object OllamaConfig {

  given ConfigReader[Uri] =
    ConfigReader[String].emap(raw => Uri.fromString(raw).left.map(e => CannotConvert(raw, "Uri", e.message)))

}
