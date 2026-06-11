package com.repcheck.llm.adapter.ollama

import java.net.ConnectException
import java.util.concurrent.TimeoutException

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.repcheck.utils.errors.ErrorClass

class OllamaErrorClassifierSpec extends AnyFlatSpec with Matchers {

  "the classifier" should "treat 429 and 5xx replies as transient" in {
    List(429, 500, 502, 503, 504).foreach { status =>
      OllamaErrorClassifier.classify(OllamaHttpError(status, "")) shouldBe ErrorClass.Transient
    }
  }

  it should "treat other HTTP statuses as systemic" in {
    List(400, 401, 404, 422).foreach { status =>
      OllamaErrorClassifier.classify(OllamaHttpError(status, "")) shouldBe ErrorClass.Systemic
    }
  }

  it should "treat timeouts and refused connections as transient" in {
    OllamaErrorClassifier.classify(new TimeoutException("slow")) shouldBe ErrorClass.Transient
    OllamaErrorClassifier.classify(new ConnectException("refused")) shouldBe ErrorClass.Transient
  }

  it should "treat parse failures and unknown errors as systemic" in {
    OllamaErrorClassifier.classify(OllamaResponseParseFailed("d", "b")) shouldBe ErrorClass.Systemic
    OllamaErrorClassifier.classify(new RuntimeException("boom")) shouldBe ErrorClass.Systemic
  }

}
