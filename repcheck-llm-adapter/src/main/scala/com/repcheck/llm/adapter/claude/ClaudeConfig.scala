package com.repcheck.llm.adapter.claude

import scala.concurrent.duration.FiniteDuration

import pureconfig.ConfigReader

import com.repcheck.utils.errors.RetryConfig

/**
 * Provider-side Claude knobs. The injected `AnthropicClient` owns auth, base URL, connection pooling, and its own
 * transport timeout — and SHOULD be built with `maxRetries(0)`: retry policy belongs to the shared `RetryWrapper` here,
 * and the SDK's built-in retries would multiply attempts underneath it.
 */
final case class ClaudeConfig(
  model: String,
  maxTokens: Int,
  requestTimeout: FiniteDuration,
  retry: RetryConfig,
) derives ConfigReader
