package com.repcheck.llm.adapter.claude

import scala.concurrent.duration._

import pureconfig.ConfigSource

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ClaudeConfigSpec extends AnyFlatSpec with Matchers {

  "ClaudeConfig" should "load from config" in {
    val source = ConfigSource.string(
      """{
        |  model = "claude-haiku-4-5-20251001"
        |  max-tokens = 4096
        |  request-timeout = 120s
        |  retry { max-retries = 2, initial-backoff-ms = 10, max-backoff-ms = 1000, backoff-multiplier = 2.0 }
        |}""".stripMargin
    )
    source.load[ClaudeConfig] match {
      case Right(config) =>
        config.model shouldBe "claude-haiku-4-5-20251001"
        config.maxTokens shouldBe 4096
        config.requestTimeout shouldBe 120.seconds
        config.retry.maxRetries shouldBe 2
      case Left(failures) => fail(s"expected a loaded config, got $failures")
    }
  }

}
