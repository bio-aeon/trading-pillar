package st.btu.trading.pillar.gateways.ibkr

import cats.effect.{Async, Ref, Sync, Temporal}
import cats.syntax.flatMap.*
import cats.syntax.functor.*

import scala.concurrent.duration.FiniteDuration

// Lets calls through at least `minSpacing` apart, in the order they ask for their turn. Each call
// reserves the next free slot before it waits for it, so calls that arrive together queue up
// instead of all waiting for the same moment.
private[ibkr] final class CallPacer[F[_]] private (
  minSpacing: FiniteDuration,
  nextCallAt: Ref[F, Option[FiniteDuration]]
)(implicit F: Temporal[F]) {

  def awaitTurn: F[Unit] =
    for {
      now <- F.monotonic
      wait <- nextCallAt.modify { reserved =>
        val start = reserved.fold(now)(_.max(now))
        (Some(start + minSpacing), start - now)
      }
      _ <- F.sleep(wait)
    } yield ()
}

private[ibkr] object CallPacer {

  def create[I[_]: Sync, F[_]: Async](minSpacing: FiniteDuration): I[CallPacer[F]] =
    Ref.in[I, F, Option[FiniteDuration]](None).map(new CallPacer[F](minSpacing, _))
}
