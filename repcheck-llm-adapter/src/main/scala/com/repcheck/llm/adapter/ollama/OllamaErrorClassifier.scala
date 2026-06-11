package com.repcheck.llm.adapter.ollama

import java.net.ConnectException
import java.util.concurrent.TimeoutException

import com.repcheck.utils.errors.{ErrorClass, ErrorClassifier, HttpErrorClassifier}

/**
 * Transient: request timeouts, connection refusals (server restarting / model loading), and the standard transient HTTP
 * statuses (429/5xx) carried by [[OllamaHttpError]]. Everything else is systemic — fail fast.
 */
object OllamaErrorClassifier extends ErrorClassifier {

  private val httpStatus = new HttpErrorClassifier({
    case OllamaHttpError(status, _) => Some(status)
    case _                          => None
  })

  def classify(error: Throwable): ErrorClass =
    error match {
      case _: TimeoutException => ErrorClass.Transient
      case _: ConnectException => ErrorClass.Transient
      case other               => httpStatus.classify(other)
    }

}
