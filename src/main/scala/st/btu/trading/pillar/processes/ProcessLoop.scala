package st.btu.trading.pillar.processes

import cats.effect.Temporal
import cats.syntax.flatMap.*
import fs2.Stream
import st.btu.trading.pillar.alerts.SystemAlerts
import tofu.logging.Logging
import tofu.syntax.logging.*

import scala.concurrent.duration.FiniteDuration

// Shared self-restarting loop shape for AnalyticsProcessImpl/PriceHistorySyncProcessImpl/
// TcsPortfolioSyncProcessImpl: run `body`, sleep `backoff`, repeat forever. An uncaught error from
// `body` is logged and raised as a "process-crashed:$name" SystemAlerts.raise, then - after the
// same `backoff` sleep - the whole loop restarts from scratch, so one crash delays the next
// attempt by one backoff rather than ending the process.
private[pillar] object ProcessLoop {

  def restarting[F[_]: Temporal: Logging](
    name: String,
    backoff: FiniteDuration,
    systemAlerts: SystemAlerts[F]
  )(body: F[Unit]): Stream[F, Unit] = {
    def tick: Stream[F, Unit] =
      Stream
        .eval(body)
        .flatMap(_ => Stream.sleep(backoff))
        .repeat
        .handleErrorWith { e =>
          Stream.eval(
            errorCause"${displayName(name)} process loop failure" (e) >>
              systemAlerts.raise(
                s"process-crashed:$name",
                s"${displayName(name)} process crashed",
                s"Loop failure: ${Option(e.getMessage).getOrElse(e.toString)}"
              )
          ) >> Stream.sleep(backoff) >> tick
        }
    tick
  }

  // "price-history-sync" -> "Price history sync": hyphens become spaces, and only the leading
  // letter is capitalized, matching this library's own "Tcs portfolio sync" (not "TCS...") wording.
  private def displayName(name: String): String = {
    val spaced = name.replace('-', ' ')
    spaced.headOption.fold(spaced)(h => h.toUpper.toString + spaced.tail)
  }
}
