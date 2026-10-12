package st.btu.trading.pillar.alerts

import scala.concurrent.duration.FiniteDuration

final case class AlertProcessConfig(pollInterval: FiniteDuration, rateLimitBackoff: FiniteDuration)
