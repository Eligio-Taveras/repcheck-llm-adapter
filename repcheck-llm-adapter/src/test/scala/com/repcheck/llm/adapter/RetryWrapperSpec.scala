package com.repcheck.llm.adapter

import scala.concurrent.duration._

import cats.effect.testing.scalatest.AsyncIOSpec
import cats.effect.{IO, Ref}

import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

class RetryWrapperSpec extends AsyncFlatSpec with AsyncIOSpec with Matchers {

  private val fast = RetryConfig(maxRetries = 3, initialDelay = 1.milli, multiplier = 2.0, maxDelay = 5.millis)

  private val isTransient: Throwable => Boolean = {
    case _: RuntimeException => true
    case _                   => false
  }

  final private class Systemic extends Exception("systemic")

  "RetryWrapper" should "retry a transient failure until it succeeds" in {
    Ref[IO].of(0).flatMap { calls =>
      val fa =
        calls.updateAndGet(_ + 1).flatMap(n => if (n < 3) IO.raiseError(new RuntimeException("boom")) else IO.pure(n))
      RetryWrapper[IO](fast, isTransient).retry(fa).asserting(_ shouldBe 3)
    }
  }

  it should "give up after maxRetries on a persistent transient failure" in {
    Ref[IO].of(0).flatMap { calls =>
      val fa = calls.updateAndGet(_ + 1) *> IO.raiseError[Int](new RuntimeException("always"))
      RetryWrapper[IO](fast, isTransient).retry(fa).attempt.flatMap(_ => calls.get).asserting(_ shouldBe 4)
    }
  }

  it should "propagate a systemic failure without retrying" in {
    Ref[IO].of(0).flatMap { calls =>
      val fa = calls.updateAndGet(_ + 1) *> IO.raiseError[Int](new Systemic)
      RetryWrapper[IO](fast, isTransient).retry(fa).attempt.flatMap(res => calls.get.map(c => (res, c))).asserting {
        case (Left(_: Systemic), 1) => succeed
        case other                  => fail(s"expected one call + Systemic, got $other")
      }
    }
  }

  "RetryConfig.default" should "be 3 retries, 10ms initial, 2x, 60s cap" in {
    RetryConfig.default shouldBe RetryConfig(3, 10.millis, 2.0, 60.seconds)
  }

}
