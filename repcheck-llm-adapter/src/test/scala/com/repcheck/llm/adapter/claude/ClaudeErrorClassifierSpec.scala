package com.repcheck.llm.adapter.claude

import java.util.concurrent.TimeoutException

import com.anthropic.errors.{AnthropicIoException, AnthropicRetryableException}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.repcheck.utils.errors.ErrorClass

/** Status-code classes (429/5xx vs 4xx) are exercised end-to-end in [[ClaudeLlmProviderSpec]] via real SDK errors. */
class ClaudeErrorClassifierSpec extends AnyFlatSpec with Matchers {

  "the classifier" should "treat timeouts and SDK transport failures as transient" in {
    ClaudeErrorClassifier.classify(new TimeoutException("slow")) shouldBe ErrorClass.Transient
    ClaudeErrorClassifier.classify(new AnthropicIoException("io", new RuntimeException("net"))) shouldBe
      ErrorClass.Transient
    ClaudeErrorClassifier.classify(new AnthropicRetryableException("retry", new RuntimeException("net"))) shouldBe
      ErrorClass.Transient
  }

  it should "treat unknown errors as systemic" in {
    ClaudeErrorClassifier.classify(new RuntimeException("boom")) shouldBe ErrorClass.Systemic
    ClaudeErrorClassifier.classify(ClaudeChatRequestFailed("d", new RuntimeException("x"))) shouldBe
      ErrorClass.Systemic
  }

}
