package st.btu.trading.pillar.processes

import scala.concurrent.duration.FiniteDuration

final case class AnalyticsConfig(interval: FiniteDuration, recomputeDays: Int)
