package com.repcheck.llm.adapter

import scala.concurrent.duration._

import cats.effect.Temporal
import cats.syntax.all._

/** Per-subsystem retry tuning. Default: 3 retries, 10ms initial, 2x backoff, 60s cap (repo convention). */
final case class RetryConfig(
  maxRetries: Int,
  initialDelay: FiniteDuration,
  multiplier: Double,
  maxDelay: FiniteDuration,
)

object RetryConfig {

  val default: RetryConfig =
    RetryConfig(maxRetries = 3, initialDelay = 10.millis, multiplier = 2.0, maxDelay = 60.seconds)

}

/** Retries an effect on TRANSIENT failures with capped exponential backoff; systemic failures propagate immediately. */
trait RetryWrapper[F[_]] {
  def retry[A](fa: F[A]): F[A]
}

object RetryWrapper {

  def apply[F[_]: Temporal](cfg: RetryConfig, isTransient: Throwable => Boolean): RetryWrapper[F] =
    new RetryWrapper[F] {
      def retry[A](fa: F[A]): F[A] = attempt(fa, cfg.maxRetries, cfg.initialDelay)

      private def attempt[A](fa: F[A], remaining: Int, delay: FiniteDuration): F[A] =
        fa.handleErrorWith {
          case t if remaining > 0 && isTransient(t) =>
            Temporal[F].sleep(delay) *> attempt(fa, remaining - 1, nextDelay(delay))
          case t => Temporal[F].raiseError(t)
        }

      private def nextDelay(d: FiniteDuration): FiniteDuration = {
        val scaled = (d.toMillis * cfg.multiplier).toLong.millis
        if (scaled > cfg.maxDelay) cfg.maxDelay else scaled
      }
    }

}
