package com.repcheck.llm.adapter.claude

import java.util.concurrent.TimeoutException

import com.anthropic.errors.{AnthropicIoException, AnthropicRetryableException, AnthropicServiceException}

import com.repcheck.utils.errors.{ErrorClass, ErrorClassifier, HttpErrorClassifier}

/**
 * Transient: request timeouts, transport-level failures (the SDK's IO / retryable exceptions), and the standard
 * transient HTTP statuses (429/5xx) carried by [[AnthropicServiceException]]. Everything else — auth, bad request,
 * invalid data — is systemic; fail fast.
 */
object ClaudeErrorClassifier extends ErrorClassifier {

  private val httpStatus = new HttpErrorClassifier({
    case e: AnthropicServiceException => Some(e.statusCode())
    case _                            => None
  })

  def classify(error: Throwable): ErrorClass =
    error match {
      case _: TimeoutException            => ErrorClass.Transient
      case _: AnthropicIoException        => ErrorClass.Transient
      case _: AnthropicRetryableException => ErrorClass.Transient
      case other                          => httpStatus.classify(other)
    }

}
