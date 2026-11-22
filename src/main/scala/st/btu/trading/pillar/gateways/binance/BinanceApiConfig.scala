package st.btu.trading.pillar.gateways.binance

import scala.concurrent.duration.*

/** How the clients of Binance's REST API sign, pace and retry their calls.
  *
  * @param recvWindow
  *   how long after its timestamp Binance still accepts a signed request
  * @param limitWindow
  *   the length of the window Binance's request weight limits are given per, a minute
  * @param ipBudget
  *   the share of a limit that is counted per IP address that the client uses, because every other
  *   process on the address draws on the same limit. Between 0 exclusive and 1.
  * @param accountBudget
  *   the share of a limit that is counted per account that the client uses
  * @param retryDelay
  *   how long to wait before trying again after a server error and after a call that got no answer
  * @param rateLimitDelay
  *   how long to wait before trying again after Binance answers that a rate limit is exceeded
  * @param maxAttempts
  *   how many times one call is made, the first call included, before it is given up
  */
final case class BinanceApiConfig(
  recvWindow: FiniteDuration = 10.seconds,
  limitWindow: FiniteDuration = 1.minute,
  ipBudget: Double = 0.5,
  accountBudget: Double = 0.9,
  retryDelay: FiniteDuration = 10.seconds,
  rateLimitDelay: FiniteDuration = 1.minute,
  maxAttempts: Int = 5
) {
  require(recvWindow > Duration.Zero, "recvWindow must be positive")
  require(limitWindow > Duration.Zero, "limitWindow must be positive")
  require(ipBudget > 0 && ipBudget <= 1, "ipBudget must be above 0 and at most 1")
  require(accountBudget > 0 && accountBudget <= 1, "accountBudget must be above 0 and at most 1")
  require(maxAttempts >= 1, "maxAttempts must be at least 1")
}
