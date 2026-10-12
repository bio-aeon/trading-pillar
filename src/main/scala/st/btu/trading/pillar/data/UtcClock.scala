package st.btu.trading.pillar.data

import cats.Functor
import cats.effect.Clock
import cats.syntax.functor.*

import java.time.{LocalDate, ZoneOffset, ZonedDateTime}

// The "now"/"today" convention every process and command handler in this library uses for its own
// tick or snapshot: the current instant (or date) read from Clock[F], fixed to UTC rather than the
// host's own zone.
private[pillar] object UtcClock {

  def nowUtc[F[_]: Functor: Clock]: F[ZonedDateTime] =
    Clock[F].realTimeInstant.map(ZonedDateTime.ofInstant(_, ZoneOffset.UTC))

  def todayUtc[F[_]: Functor: Clock]: F[LocalDate] =
    Clock[F].realTimeInstant.map(LocalDate.ofInstant(_, ZoneOffset.UTC))
}
