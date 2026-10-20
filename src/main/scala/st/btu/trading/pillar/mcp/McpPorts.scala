package st.btu.trading.pillar.mcp

import st.btu.trading.pillar.gateways.MarketDataSources
import st.btu.trading.pillar.models.domain.AccountId
import st.btu.trading.pillar.services.commands.CommandRunner
import st.btu.trading.pillar.storages.{
  InstrumentStorage,
  PriceHistoryStorage,
  TickerSourceStorage,
  WatchlistStorage
}

/** Everything the bot tools of [[McpTools]] read from or run commands through.
  *
  * @param marketDataSources
  *   resolves a ticker's source setting and the adapter serving it
  * @param tickerSources
  *   the per-ticker source settings, to tell a stored setting from the default
  * @param watchlist
  *   the watchlists, of which only `accountId`'s is read
  * @param commands
  *   runs command lines through the handlers behind a
  *   [[st.btu.trading.pillar.gateways.CapturingTgGateway]]
  * @param accountId
  *   the account whose watchlist entry a ticker report shows
  */
final case class McpPorts[F[_]](
  marketDataSources: MarketDataSources[F],
  tickerSources: TickerSourceStorage[F],
  watchlist: WatchlistStorage[F],
  priceHistory: PriceHistoryStorage[F],
  instruments: InstrumentStorage[F],
  commands: CommandRunner[F],
  accountId: AccountId
)
