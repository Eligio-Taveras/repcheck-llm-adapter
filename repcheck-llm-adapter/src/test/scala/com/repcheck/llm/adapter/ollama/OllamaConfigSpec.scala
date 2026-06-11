package com.repcheck.llm.adapter.ollama

import scala.concurrent.duration._

import pureconfig.ConfigSource

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class OllamaConfigSpec extends AnyFlatSpec with Matchers {

  private def source(baseUri: String): ConfigSource =
    ConfigSource.string(
      s"""{
         |  base-uri = "$baseUri"
         |  model = "qwen3:0.6b"
         |  num-ctx = 8192
         |  keep-alive = 5m
         |  request-timeout = 30s
         |  retry { max-retries = 2, initial-backoff-ms = 10, max-backoff-ms = 1000, backoff-multiplier = 2.0 }
         |}""".stripMargin
    )

  "OllamaConfig" should "load from config, parsing the base URI" in {
    source("http://localhost:11434").load[OllamaConfig] match {
      case Right(config) =>
        config.baseUri.renderString shouldBe "http://localhost:11434"
        config.model shouldBe "qwen3:0.6b"
        config.numCtx shouldBe 8192
        config.keepAlive shouldBe 5.minutes
        config.requestTimeout shouldBe 30.seconds
        config.retry.maxRetries shouldBe 2
      case Left(failures) => fail(s"expected a loaded config, got $failures")
    }
  }

  it should "reject a malformed base URI" in {
    source("http://exa mple.com").load[OllamaConfig] match {
      case Left(failures) => failures.toList.mkString should include("Uri")
      case Right(config)  => fail(s"expected a load failure, got $config")
    }
  }

}
