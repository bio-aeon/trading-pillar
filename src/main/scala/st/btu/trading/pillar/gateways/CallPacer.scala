package st.btu.trading.pillar.gateways

import cats.effect.{Async, Ref, Sync, Temporal}
import cats.syntax.flatMap.*
import cats.syntax.functor.*

import scala.concurrent.duration.FiniteDuration

// Lets calls through in the order they ask for their turn, each call keeping the next one waiting
// for the spacing it asked for: a call reserves the next free slot before it waits for it, so calls
// that arrive together queue up instead of all waiting for the same moment. A service whose limit is
// a call every so often asks for the same spacing each time; one whose limit counts the weight of
// its calls asks for the share of the limit that the call's weight takes.
private[gateways] final class CallPacer[F[_]] private (nextCallAt: Ref[F, Option[FiniteDuration]])(
  implicit F: Temporal[F]
) {

  /** Waits until it is this call's turn, and keeps the next call at least `spacing` after it. */
  def awaitTurn(spacing: FiniteDuration): F[Unit] =
    for {
      now <- F.monotonic
      wait <- nextCallAt.modify { reserved =>
        val start = reserved.fold(now)(_.max(now))
        (Some(start + spacing), start - now)
      }
      _ <- F.sleep(wait)
    } yield ()
}

private[gateways] object CallPacer {

  def create[I[_]: Sync, F[_]: Async]: I[CallPacer[F]] =
    Ref.in[I, F, Option[FiniteDuration]](None).map(new CallPacer[F](_))
}
