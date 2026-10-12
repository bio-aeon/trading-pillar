package st.btu.trading.pillar.alerts

/** A destination an [[Alert]] can be delivered to, such as Telegram. */
trait AlertChannel[F[_]] {

  /** This channel's name, as configured in a rule's or policy's channel list. */
  def name: String

  /** Delivers `alert` to this channel. */
  def send(alert: Alert): F[Unit]
}
