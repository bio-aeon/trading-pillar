package st.btu.trading.pillar.alerts

import cats.Monad
import cats.effect.{Ref, Sync}
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import tofu.logging.{Logging, Logs}
import tofu.syntax.logging.*

/** Consecutive-failure counter per key, in memory only: resets on process restart and is not shared
  * across process instances.
  */
trait FailureTracker[F[_]] {

  /** Increments and returns the new consecutive-failure count for `key`. */
  def failed(key: String): F[Int]

  /** Resets `key`'s count to zero, dropping it so a long-idle key doesn't linger. */
  def succeeded(key: String): F[Unit]
}

final class FailureTrackerImpl[F[_]: Logging] private (ref: Ref[F, Map[String, Int]])(implicit
  F: Monad[F]
) extends FailureTracker[F] {

  def failed(key: String): F[Int] =
    ref
      .modify { current =>
        val count = current.getOrElse(key, 0) + 1
        (current.updated(key, count), count)
      }
      .flatTap(count => F.whenA(count > 1)(debug"Consecutive failures for $key: $count"))

  def succeeded(key: String): F[Unit] =
    ref
      .modify(current => (current - key, current.contains(key)))
      .flatMap(wasFailing => F.whenA(wasFailing)(debug"Failure streak for $key cleared"))
}

object FailureTrackerImpl {

  /** Wires a [[FailureTracker]] with an empty in-memory count map. */
  def create[I[_]: Sync, F[_]: Sync](implicit logs: Logs[I, F]): I[FailureTracker[F]] =
    for {
      ref <- Ref.in[I, F, Map[String, Int]](Map.empty)
      tracker <- logs
        .forService[FailureTracker[F]]
        .map(implicit log => new FailureTrackerImpl[F](ref))
    } yield tracker
}
