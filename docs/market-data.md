# Market data

Package `st.btu.trading.pillar.models.market` (domain), `gateways.MarketDataSource` (contract),
`gateways.yahoo` (Yahoo Finance implementation), `gateways.tcs` (Tinkoff Invest implementation),
`gateways.binance` (Binance implementation, over the keyless market client of
[`binance.md`](binance.md)),
`gateways.MarketDataSources` (routes a ticker to the implementation its source setting selects,
and to the Yahoo symbol or Tinkoff board that setting names),
`gateways.InstrumentDirectory` (resolves the id a broker gives an instrument to the instrument),
`gateways.ibkr` (Interactive Brokers' instrument directory - see [`ibkr.md`](ibkr.md)),
`gateways.StoredInstrumentDirectory` (the instrument directory of a broker with no lookup, such
as Binance - see [`binance.md`](binance.md)),
`services`/`processes` (sync loop), `storages` (persistence contracts - see
[`storage-contracts.md`](storage-contracts.md)).

## Domain models

All in `st.btu.trading.pillar.models.market`:

| Type | Shape | Notes |
|---|---|---|
| `Ticker` | `opaque type Ticker = String` | Named `Ticker`, not `Symbol`, to avoid shadowing `scala.Symbol`. |
| `Source` | enum: `Yahoo`, `Tinkoff`, `Binance` (`enumeratum` `StringEnumEntry`, values `"yahoo"`/`"tinkoff"`/`"binance"`) | Each has a `MarketDataSource` implementation (see below). |
| `ClassCode` | `opaque type ClassCode = String` | A broker's code for a trading board, e.g. `"TQBR"`. |
| `YahooSymbol` | `opaque type YahooSymbol = String` | A symbol as Yahoo Finance knows an instrument, e.g. `"VOD.L"`; it can differ from the ticker the instrument goes by elsewhere (`VOD` at the broker). |
| `SourceSetting` | sealed trait with `def source: Source`: `SourceSetting.Yahoo(symbol: Option[YahooSymbol])`, `SourceSetting.Tinkoff(classCode: Option[ClassCode])`, `SourceSetting.Binance` (a case object) | Where one ticker's data comes from. `Yahoo(None)` looks the ticker up on Yahoo as given and `Yahoo(Some(symbol))` asks Yahoo for `symbol` instead; `Tinkoff(None)` means the Tinkoff adapter's default board; `Binance` reads the ticker as the Binance pair of the same symbol, such as `BTCUSDC`, with no board or Yahoo symbol to choose. |
| `TickerSource` | `TickerSource(ticker, setting: SourceSetting)` | The setting stored for a ticker. A ticker with none uses the router's default setting. |
| `SourcedTicker` | `SourcedTicker(ticker, source: Source)` | A ticker paired with the source its stored data is read from; the key `DividendStorage.upcoming` takes. |
| `BrokerInstrument` | `BrokerInstrument(instrument: InstrumentKey, ticker, classCode: Option[ClassCode], name: String, instrumentType: String, currency: Currency, updatedAt, futureTerms: Option[FutureTerms])` | A broker's own record of an instrument, found by its `InstrumentKey`: the broker and the id that broker gives it, a FIGI at Tinkoff. `classCode` is the board, for a broker that names one. `currency` is the currency the instrument settles in. `isFuture` tells whether it is a futures contract, which the valuation and the tax lots treat apart (see [`analytics.md`](analytics.md#futures)): at Tinkoff, a record whose `instrumentType` is `futures`. `futureTerms` is set only on a future's record and only when the broker gave the terms, so a future without terms is still a future; `expiredOn(date)` is the one rule for an expired contract. |
| `FutureTerms` | `FutureTerms(underlying: Underlying, lastTradeDate: LocalDate, expirationDate: LocalDate, priceStep: BigDecimal, priceStepValue: BigDecimal)` | What a point of a contract's price is worth and until when it exists. `pointValue` is `priceStepValue` over `priceStep`, in the currency of the record; `expiredOn(date)` is true once `date` is after `expirationDate`, so false on that day. The dates are exchange days. The settlement type is not kept: a deliverable contract is out of scope. |
| `Underlying` | `opaque type Underlying = String` | The asset a futures contract is on, as its broker names it. |
| `Bar` | `Bar(ticker, date: LocalDate, open, high, low, close, adjClose, volume: Long, source)` | One daily OHLCV bar. All prices are `BigDecimal`. |
| `Quote` | `Quote(ticker, price: BigDecimal, currency: Currency, asOf: ZonedDateTime, source)` | A single live/last price. |
| `InstrumentInfo` | `InstrumentInfo(ticker, origin: SourceSetting, name: Option[String], instrumentType, currency: Currency, exchange, timezone, figi: Option[String], updatedAt)` | `origin` is the setting the metadata was read under, with Tinkoff's board resolved: a Tinkoff adapter always reports `Tinkoff(Some(board))`, a Yahoo one `Yahoo(None)` or `Yahoo(Some(symbol))`, a Binance one `Binance`. Two origins can describe one ticker differently, in another currency for instance: one source against another, one board against another, one Yahoo symbol against another. `figi` is an optional cross-reference for Tinkoff; Yahoo has no FIGIs. |
| `WatchlistItem` | `WatchlistItem(accountId, ticker, enabled: Boolean, note: Option[String], createdAt)` | Drives the sync process; managed via the `/watchlist` command or the consumer's own tooling. It has no source of its own: which source serves its ticker is the ticker's `SourceSetting`. |
| `DividendEvent` | `DividendEvent(ticker, exDate: LocalDate, amount: BigDecimal, currency: Option[Currency], source)` | One dividend ex-date event. `currency` is `None` when the source doesn't report one distinct from the instrument's trading currency. |
| `MarketDataError` | sealed: `NotFound(ticker)`, `RateLimited(retryAfter: Option[FiniteDuration])`, `Transient(message, cause)`, `SourceNotConfigured(source: Source)`, `Other(message, cause)` | Raised (not returned as `Either`) by every `MarketDataSource` method. `SourceNotConfigured` is raised by the router, not by an adapter: a ticker's source has no adapter wired. |

Every price, whichever adapter read it, is a `BigDecimal` at 18 decimal places (`Prices.Scale`),
rounded half up, so that a coin priced far below a cent keeps its digits. Exports show a price
without trailing zeros, however many places it carries; replies show it at two decimals from 1 up
and at four significant digits below 1 (see [Telegram commands](telegram-commands.md)).

Every `currency` field is a `models.domain.Currency`, whose constructor normalises the code (see
[`analytics.md`](analytics.md#rules), rule 10). Each adapter builds it from what its source reports,
so Tinkoff's `rub` arrives as `RUB`. The Yahoo adapter reports a minor unit such as `GBp` in its major
currency, `GBP` (see [Minor units](#minor-units)).

The tickers of a stored series, a watchlist, an alert or a backtest are always the bot's own. A
Yahoo symbol only decides what is asked of Yahoo: every `Bar`, `Quote`, `DividendEvent` and
`InstrumentInfo` is reported under the ticker, whatever symbol it was fetched as.

## `MarketDataSource[F[_]]`

```scala
trait MarketDataSource[F[_]] {
  def origin: SourceSetting  // the setting this instance serves data under; callers read origin.source
  def dailyBars(ticker: Ticker, since: LocalDate, until: LocalDate): F[List[Bar]]  // chronological
  def latestQuote(ticker: Ticker): F[Option[Quote]]
  def instrumentInfo(ticker: Ticker): F[Option[InstrumentInfo]]
  def dividends(ticker: Ticker, since: LocalDate, until: LocalDate): F[List[DividendEvent]]
}
```

`origin` is the provider together with what the instance looks tickers up by: the resolved board of a
Tinkoff source, the Yahoo symbol of a Yahoo view (`None` when it looks the ticker up as given). The
`InstrumentInfo` an instance returns carries the same `origin`.

`dailyBars` and `dividends` raise a `MarketDataError` on failure (network, rate limit, unknown
ticker, ...) rather than encoding failure in the return type; `dailyBars` returns bars in
chronological order.

## Yahoo implementation

`gateways.yahoo.YahooApi[F[_]]` is a thin seam over `YFinanceClient.charts`, expressed in
library-owned DTOs (`YahooQuote`, `YahooChart`, `YahooMetadata`) so tests can fake it without
constructing yfinance4s's own models:

```scala
trait YahooApi[F[_]] {
  def dailyChart(ticker: String, since: ZonedDateTime, until: ZonedDateTime): F[Option[YahooChart]]
  def metadata(ticker: String): F[YahooMetadata]
}
object YahooApi {
  def fromClient[F[_]: Functor](client: YFinanceClient[F]): YahooApi[F]
}
```

`dailyChart` calls `charts.getChart(ticker, Interval.\`1Day\`, since, until)`; `metadata` calls
`charts.getHistoryMetadata(ticker)` - one request that also backs `latestQuote`
(`regularMarketPrice`/`regularMarketTime`) and `instrumentInfo`. The `ticker` argument of the three
calls is the symbol Yahoo is asked for, which is the bot's ticker unless the setting names a
[Yahoo symbol](#yahoo-symbols-yahoosymbols).

`YahooMarketDataSource[F[_]]` implements `MarketDataSource[F]` on top of `YahooApi[F]`, and
`YahooSymbols[F]` (see [Yahoo symbols](#yahoo-symbols-yahoosymbols)):

- **Metadata caching**: `YahooMetadata` is cached per Yahoo symbol, the one actually asked for, with
  a TTL (`DefaultMetadataTtl = 6.hours`); a cache miss or expiry triggers a fresh `api.metadata`
  call. `instrumentInfo` always serves from this cache (so its `figi`/`name` fields are always
  `None` - Yahoo metadata has neither) and carries `origin = SourceSetting.Yahoo(symbol)`, the same
  as `MarketDataSource.origin`.
- **Currency**: `Quote.currency` and `InstrumentInfo.currency` are the currency Yahoo reports for
  the instrument, with a minor unit replaced by its major currency (see
  [Minor units](#minor-units)).
- **Date derivation**: each `YahooQuote.datetime` (UTC) is converted to a `LocalDate` in the
  instrument's *exchange* timezone (`meta.exchangeTimezoneName`), not UTC or the caller's zone.
  When two quotes fall on the same exchange-local date, the later one in the source list wins.
- **Sessions in progress are skipped**: while a session is open, Yahoo's daily chart ends with a
  bar whose close is the latest price. yfinance4s marks that bar (`ChartResult.Quote.inProgress`,
  carried as `YahooQuote.inProgress`), and `dailyBars` leaves it out, so the session's bar arrives
  with the first request after the close, as with the Tinkoff source.
- **Quote validation**: a quote is dropped (and a single warning logged with the drop count) if
  `open`/`high`/`low`/`close` is `NaN`, infinite or `<= 0`, or `volume < 0`.
- **Numeric conversion**: `Double -> BigDecimal` via `java.math.BigDecimal.valueOf(d)`, then
  `setScale(18, RoundingMode.HALF_UP)`.
- **Error mapping** (`YFinanceError -> MarketDataError`):

  | `YFinanceError` | `MarketDataError` |
  |---|---|
  | `TickerNotFound`, `Delisted` | `NotFound(ticker)` |
  | `RateLimited(retryAfter)` | `RateLimited(retryAfter)` |
  | `NetworkError(message, cause)` | `Transient(message, cause)` |
  | `DataParseError(message, cause)` | `Other(message, cause)` |
  | anything else | `Other(message, Some(cause))` |

Construction:

```scala
object YahooMarketDataSource {
  final case class YahooConfig(
    connectTimeout: FiniteDuration,
    readTimeout: FiniteDuration,
    retries: Int,
    maxRequestsPerSecond: Int
  )
  def clientResource[F[_]: Async](config: YahooConfig): Resource[F, YFinanceClient[F]]
  def create[I[_]: Sync, F[_]: Sync: Clock](
    api: YahooApi[F],
    metadataTtl: FiniteDuration = DefaultMetadataTtl
  )(implicit logs: Logs[I, F]): I[YahooMarketDataSource[F]]
}
```

`clientResource` enables yfinance4s's own interval-based rate limiting
(`RateLimitConfig.Enabled(maxRequestsPerSecond)`).

### Yahoo dividends

`YahooApi.dividends` is backed by `client.charts.getDividends(ticker, Interval.\`1Day\`, since,
until)`, which returns `F[Option[List[DividendEvent]]]` (yfinance4s's own `DividendEvent`, not
trading-pillar's); `None` (no chart data at all for the ticker/range) maps to an empty list, so
`YahooApi.dividends` itself always returns `F[List[YahooDividend]]`:

```scala
final case class YahooDividend(exDate: ZonedDateTime, amount: Double, currency: Option[String])
trait YahooApi[F[_]] {
  def dividends(ticker: String, since: ZonedDateTime, until: ZonedDateTime): F[List[YahooDividend]]
}
```

`YahooMarketDataSource.dividends` maps `List[YahooDividend]` to `List[DividendEvent]` the same way
`dailyBars` maps `YahooChart`'s quotes to `Bar`s: `exDate` is derived from the UTC `ZonedDateTime`
in the instrument's *exchange* timezone (from the same cached `YahooMetadata` bars use), `amount`
is converted `Double -> BigDecimal` the same way as OHLC prices
(`java.math.BigDecimal.valueOf(d)`, scaled to 18 decimals), and errors are mapped through the same
`YFinanceError -> MarketDataError` table as `dailyBars`/`metadata`.

### Yahoo symbols (`YahooSymbols`)

A broker can list an instrument under a ticker that Yahoo does not know it by: `VOD` at the broker
is `VOD.L` on Yahoo. A ticker's setting can therefore name the symbol to ask Yahoo for, and the
source implements `gateways.YahooSymbols`, which is what the router asks for:

```scala
trait YahooSymbols[F[_]] {
  def onSymbol(symbol: Option[YahooSymbol]): MarketDataSource[F]
}
```

`onSymbol(Some(symbol))` returns a view of the source that asks Yahoo for `symbol`; `onSymbol(None)`
returns one that asks for the ticker it is given, as the source itself does. A view differs from
the source in the symbol it sends to `YahooApi` and in nothing else: it reports every bar, quote,
dividend and `InstrumentInfo` under the ticker it was given, maps a Yahoo error to that ticker
(`NotFound(ticker)`), and its `origin` is `SourceSetting.Yahoo(symbol)`. All views share the
source's metadata cache, which is keyed by the Yahoo symbol asked for: two tickers set to one symbol
share an entry, while one ticker looked up as given and again under a symbol keeps two. News and
screeners are not affected: `/news` and `/screen` query Yahoo with the ticker as given.

### Minor units

Yahoo quotes some exchanges in a minor unit of a currency and marks it by the case of the currency
code:

| Yahoo code | Unit | Reported as |
|---|---|---|
| `GBp` | pence (London) | `GBP` |
| `ZAc` | cents (Johannesburg) | `ZAR` |
| `ILA` | agorot (Tel Aviv) | `ILS` |

For an instrument whose currency Yahoo reports as one of these, the adapter reports the major
currency and divides by 100:

- bars: open, high, low, close and adjusted close; the volume is unchanged;
- the quote's price;
- dividend amounts. A dividend whose own currency is a minor unit is converted the same way. A
  dividend without a currency of its own is in the instrument's, so it is converted when that is a
  minor unit and then carries the major currency; otherwise it stays without one. A dividend with a
  major currency of its own is left alone.

Amounts keep the 18-decimal price scale after dividing, rounded half up. Valuation, positions and paper
trading therefore see the unit the broker's operations use (ISO 4217 has no code for pence), and an
alert threshold for such a ticker is written in the major currency. The case matters: `GBP` is
pounds and is not divided. The table is Yahoo's convention and lives in the adapter;
`models.domain.Currency` keeps any code as given.

## Tinkoff implementation (`TcsMarketDataSource`)

`gateways.tcs.TcsMarketDataSource` is a second `MarketDataSource[F[_]]` (`origin.source = Source.Tinkoff`),
built on the Tinkoff Invest gRPC API instead of yfinance4s. It is the source for MOEX-listed tickers
(e.g. `SBER`) that Yahoo Finance no longer serves - see "Known limitation" below.

It calls three Tinkoff Invest RPCs, all against `invest-public-api.tbank.ru:443` over TLS, with a
Bearer-token `Metadata` interceptor built from a `tcs.Token`. The API's certificates chain to the
Russian Trusted Root CA, which the JDK does not ship, so the channel trusts that root, bundled with
the library, besides the JDK's own roots. The trust applies to this channel only.

- **`InstrumentsService.GetInstrumentBy`** (`InstrumentIdType.INSTRUMENT_ID_TYPE_TICKER`, keyed by
  `Ticker.value` and the board's class code) resolves a ticker to its FIGI and the rest of the
  `Instrument` (currency, name, exchange, type). The result is cached per `(Ticker, ClassCode)` in
  an in-memory `Ref` for the lifetime of the source, since every other call needs the FIGI, not the
  ticker - a repeated `dailyBars`/`latestQuote`/`instrumentInfo` call for the same ticker on the
  same board resolves it only once (`InstrumentsService.GetInstrumentBy` is not called again).
- **`MarketDataService.GetCandles`** (`CandleInterval.CANDLE_INTERVAL_DAY`) backs `dailyBars`. A
  single request window is capped at one year, so a multi-year `since..until` range is chunked into
  at-most-one-year requests and the results concatenated and sorted chronologically. **Incomplete
  candles are skipped**: only `HistoricCandle`s with `isComplete = true` are considered at all, so
  the in-progress candle for a still-open trading session is never returned as a closed daily bar.
  Of those, `toBar` maps a candle to `Bar` only when it carries a timestamp (`Some(Bar)`); a
  candle with none is dropped and counted into a single warning log line for the call, the same
  "drop and count" treatment `YahooMarketDataSource` gives an invalid quote. `adjClose` is set
  equal to `close` - Tinkoff candles are not split/dividend-adjusted separately.
- **`MarketDataService.GetLastPrices`** (by FIGI) backs `latestQuote`; the quote's currency comes
  from the cached `Instrument`, and its timestamp falls back to "now" when Tinkoff doesn't return
  one on the price.

`instrumentInfo` is built entirely from the cached `Instrument` plus `config.zone.getId` as the
timezone (Tinkoff's API has no per-instrument timezone field); unlike the Yahoo source, `figi` is
always populated, and `origin` (here and on `MarketDataSource.origin`) is
`SourceSetting.Tinkoff(Some(board))` for the board the source or view looks tickers up on.

Prices arrive as Tinkoff's `Quotation` (`units` + `nano`, a fixed-point decimal split across two
integer fields) and are converted to `BigDecimal` as `units + nano / 1e9`, scaled to 18 decimals
(`RoundingMode.HALF_UP`) - the same target scale `YahooMarketDataSource` uses, which keeps every
nano.

Errors map from the gRPC `Status` code of a `StatusRuntimeException` (any other throwable becomes
`MarketDataError.Other`):

| gRPC `Status.Code` | `MarketDataError` |
|---|---|
| `NOT_FOUND` | `NotFound(ticker)` |
| `RESOURCE_EXHAUSTED` | `RateLimited(None)` |
| `UNAVAILABLE`, `DEADLINE_EXCEEDED` | `Transient(message, cause)` |
| anything else | `Other(message, cause)` |

### Tinkoff dividends

`TcsMarketDataSource.dividends` calls `InstrumentsService.GetDividends(instrument_id, from, to)`
with the cached FIGI as `instrument_id` (same resolution/caching as `dailyBars`/`latestQuote`).
Tinkoff's `Dividend` message has no explicit ex-dividend date field, so **`exDate` is approximated
as `last_buy_date + 1 day`** - the exchange convention that the ex-date is the first trading day on
which a purchase no longer settles in time to land the buyer on the dividend register for that
payment. This is a documented approximation, not an exact figure from the API. `amount` is
`dividend_net` (a `MoneyValue`: `units + nano / 1e9`, scaled to 18 decimals, same as prices) and
`currency` is `dividend_net`'s own `currency` field. Entries without a `last_buy_date`, or whose
`dividend_net` amount is zero, are skipped rather than guessed at.

### Boards (`TinkoffBoards`)

One ticker can be listed on several boards of the same exchange, each board with its own FIGI. The
source therefore also implements `gateways.TinkoffBoards`, which is what the router asks for:

```scala
trait TinkoffBoards[F[_]] {
  def onBoard(classCode: Option[ClassCode]): MarketDataSource[F]
}
```

`onBoard(Some(code))` returns a view of the source that looks tickers up on board `code`;
`onBoard(None)` returns one on `TcsMarketDataConfig.defaultClassCode`, and so does the source
itself. The view's `MarketDataSource.origin` is `SourceSetting.Tinkoff(Some(board))` for the board it
looks tickers up on, and so is the `origin` of the metadata it reads. A view differs from the source
in the board it sends to `GetInstrumentBy` and in nothing else: candles, quotes, dividends and error
mapping are the same. All views share the source's FIGI cache, which is keyed by
`(Ticker, ClassCode)`, so a ticker is resolved once per board however many views ask for it, and the
same ticker on two boards resolves to two instruments.

Construction:

```scala
object TcsMarketDataSource {
  final case class TcsMarketDataConfig(
    defaultClassCode: ClassCode = ClassCode("TQBR"),
    zone: ZoneId = ZoneId.of("Europe/Moscow")
  )
  def resource[I[_]: Sync, F[_]: Async](token: tcs.Token, config: TcsMarketDataConfig)(implicit
    logs: Logs[I, F],
    liftFI: Lift[F, I]
  ): Resource[I, TcsMarketDataSource[F]]
}
```

`TcsMarketDataConfig`'s defaults - `defaultClassCode = ClassCode("TQBR")` (MOEX's main equity board
class code) and `zone = ZoneId.of("Europe/Moscow")` (MOEX's trading timezone) - are exactly the
values a consumer wires for a MOEX ticker like `SBER`. A ticker listed on another board names it in
its own `SourceSetting.Tinkoff(Some(classCode))` instead of changing the default - see
["Routing multiple sources"](#routing-multiple-sources-marketdatasources). `resource` opens its own
Netty gRPC channel (`useTransportSecurity()`) and builds the
`InstrumentsServiceFs2Grpc`/`MarketDataServiceFs2Grpc` stubs with the Bearer-auth interceptor; `TcsMarketDataSource.create` is the lower-level constructor
for callers (tests, or an app that already manages the gRPC stubs) that supply the stubs directly -
see `TcsMarketDataSourceSpec`.

## Binance implementation (`BinanceMarketDataSource`)

`gateways.binance.BinanceMarketDataSource` is a third `MarketDataSource[F[_]]` (`origin =
SourceSetting.Binance`), over `BinanceMarket`, the keyless client of
[`binance.md`](binance.md#the-keyless-client). A ticker set to Binance is the symbol of a spot
pair, such as `BTCUSDC`, and its prices are quoted in the quote asset of the pair. It is the source
the [held instruments service](#held-instruments-on-the-watchlist) sets the ticker of a held
Binance coin to.

- **`dailyBars`** gives the daily bars of the UTC days from `since` to `until`, read 1,000 days to
  a call. Only days that have ended are returned: a bar whose close time, the last millisecond of
  its day, is not before the time the bars were asked for is left out, so the bar of a day arrives
  with the first request after it. `date` is the UTC day. Every price keeps the digits of the
  decimal string Binance wrote it as, at the price scale, `adjClose` is `close` (a coin has no
  splits or dividends to adjust for), `volume` is the volume traded in the base asset rounded down
  to whole units, and `source` is `Binance`. A pair that Binance does not know, such as a delisted
  one, raises `NotFound`.
- **`latestQuote`** is the price of the latest trade of the pair, in the quote asset of the pair,
  as of the time it was read. It is none for a pair Binance does not list, which is not asked for
  its price, and for a listed pair that has none.
- **`instrumentInfo`** describes a pair Binance lists: its name is `<base>/<quote>`, its type
  `crypto`, its currency the quote asset, its exchange `BINANCE`, its time zone `UTC`, and it has
  no FIGI. It is none for a pair Binance does not list.
- **`dividends`** are none, and Binance is not asked.
- **The pairs** come from `exchangeInfo`, read when first needed and again when the last reading is
  an hour old. The quote and the description of every ticker share them, and the bars need none.
- **Error mapping** (`BinanceError -> MarketDataError`):

  | `BinanceError` | `MarketDataError` |
  |---|---|
  | `Refused` with `-1121`, a pair Binance does not know | `NotFound(ticker)` |
  | `RetriesExhausted` on 429 | `RateLimited(None)`: Binance's `Retry-After` is not trusted, so there is no hint |
  | `RetriesExhausted` on a 5xx, `Unreachable` | `Transient(message, cause)` |
  | any other `Refused`, `Banned`, `UnexpectedResponse`, anything else | `Other(message, cause)` |

Construction takes the `Clock[F]` that decides which bars have ended and when a quote was read:

```scala
object BinanceMarketDataSource {
  def create[I[_]: Sync, F[_]: Sync](market: BinanceMarket[F])(implicit clock: Clock[F]): I[BinanceMarketDataSource[F]]
}
```

One source serves every Binance ticker, so the router takes it as it is, with no wrapper that
chooses by setting as Yahoo's and Tinkoff's have.

## Instrument directory (`InstrumentDirectory`)

A broker operation identifies its instrument by the id that broker gives it (a FIGI at Tinkoff),
while market data is keyed by ticker. `gateways.InstrumentDirectory` is the bridge, looking an
instrument up by its `InstrumentKey`, the broker together with that id:

```scala
final case class InstrumentKey(broker: Broker, id: BrokerInstrumentId)

trait InstrumentDirectory[F[_]] {
  def byKey(key: InstrumentKey): F[Option[BrokerInstrument]]
}
```

`byKey` returns `None` for an instrument the broker does not know and raises `MarketDataError` when
the lookup itself fails. The tax service maps every instrument on its operations through it, the
valuation service only those that executed operations changed the position of - see
[`tax.md`](tax.md) and [`analytics.md`](analytics.md#portfolio-valuation).

`gateways.RoutingInstrumentDirectory` is the directory a consumer hands the services. It holds one
directory per broker and answers each key from the directory of the key's broker, and with `None`
for a key of a broker it has no directory for, so supporting another broker is one more entry:

```scala
object RoutingInstrumentDirectory {
  def create[F[_]: Applicative](
    directories: Map[Broker, InstrumentDirectory[F]]
  ): RoutingInstrumentDirectory[F]
}
```

`gateways.tcs.TcsInstrumentDirectory` is Tinkoff's directory. Its instrument ids are FIGIs, and it
implements the lookup over the Tinkoff Invest API:

- A key that `BrokerInstrumentStorage` already holds is answered from it, without a gRPC call.
- Any other key is fetched with `InstrumentsService.GetInstrumentBy`
  (`InstrumentIdType.INSTRUMENT_ID_TYPE_FIGI`, with no class code: a FIGI identifies the instrument
  on its own), mapped to a `BrokerInstrument` whose `updatedAt` is the time of the lookup, stored
  through `BrokerInstrumentStorage.upsert` and returned. The next call finds it in the storage. A
  broker record that names no board gives a `classCode` of `None`.
- A gRPC `NOT_FOUND`, or an answer that carries no instrument, gives `None` and stores nothing, so
  an instrument the broker does not know is asked about again on every call, while one the broker
  knows is fetched once. Any other gRPC failure maps like the market data source above:
  `RESOURCE_EXHAUSTED` to `RateLimited(None)`, `UNAVAILABLE` and `DEADLINE_EXCEEDED` to
  `Transient`, anything else to `Other`.
- Stored records of any type but futures are never refreshed. A ticker the broker renames under the
  same FIGI keeps its old ticker until its stored record is removed.
- A key of another broker gives `None`, with no call and nothing stored.

A record of type `futures` also gets its `FutureTerms`, from `InstrumentsService.FutureBy` by FIGI,
fetched with the record. The directory is the only writer of terms:

- `basic_asset` is the underlying, `min_price_increment` the price step and
  `min_price_increment_amount` the money it is worth. `last_trade_date` and `expiration_date` are
  read as dates in the exchange zone the directory is created with, which gives the exchange's day
  whether Tinkoff's timestamp is the UTC or the Moscow midnight of that day.
- A price step or step value that is not positive, an empty underlying or a missing date gives no
  terms. `FutureBy` answering that it does not know the contract stores the record without terms.
  Any other failure fails the lookup, as one of `GetInstrumentBy` does.
- The exchange sets the money a step is worth at each day's clearing, and for a contract on a
  foreign underlying it moves with the exchange rate, so a stored future's record is asked of
  `FutureBy` again once it is a day old, until the contract has expired today in the exchange zone.
  A record stored without terms is asked on the same rule, so it gains its terms once `FutureBy`
  gives them.
- An answer with terms replaces the stored ones. An answer without terms, whether not found or
  unusable, is logged and keeps the stored terms. Either answer renews `updatedAt`, so the record is
  asked again a day later.
- A failed call, or one that takes over 10 seconds, is logged with its cause and keeps the stored
  record without renewing `updatedAt`, so the next lookup tries again. The lookup answers with the
  stored record either way. The refresh is the only Tinkoff call here with a deadline.

Construction mirrors `TcsMarketDataSource`: `zone` is the exchange's time zone,
`TcsMarketDataConfig.DefaultZone` (`Europe/Moscow`) unless the consumer's exchange differs, and
`resource` opens its own gRPC channel:

```scala
object TcsInstrumentDirectory {
  def create[I[_]: Functor, F[_]: Temporal](
    instruments: InstrumentsServiceFs2Grpc[F, Unit],
    storage: BrokerInstrumentStorage[F],
    zone: ZoneId
  )(implicit logs: Logs[I, F]): I[TcsInstrumentDirectory[F]]

  def resource[I[_]: Sync, F[_]: Async](
    token: tcs.Token,
    storage: BrokerInstrumentStorage[F],
    zone: ZoneId
  )(implicit logs: Logs[I, F], liftFI: Lift[F, I]): Resource[I, TcsInstrumentDirectory[F]]
}
```

`gateways.StoredInstrumentDirectory` is the directory of a broker whose API has no lookup of an
instrument by the id it gives it. It answers from `BrokerInstrumentStorage` alone: a key of its
broker that the storage holds is answered from it, and any other key, a key of another broker
included, with `None`. It raises no error of its own: only a failure of the storage reaches the
caller. Two brokers are like that:

- Interactive Brokers: its instrument ids are contract ids (`conid`), and the Flex Web Service,
  which IB's statements are read through, has no instrument lookup. The statement sync fills the
  storage with a record for each instrument a statement describes (the IB symbol as the ticker, the
  listing exchange as the board, the description as the name, the asset category as the type and
  the currency) - see [`ibkr.md`](ibkr.md#instruments).
- Binance: its instrument ids are asset codes such as `BTC`, and the Binance sync fills the storage
  with a record for each coin its translations name, whose ticker is the coin's pair with the first
  price quote that Binance lists, such as `BTCUSDC`, whose type is `crypto` and whose currency is
  that quote, with no board - see [`binance.md`](binance.md#recording).

```scala
object StoredInstrumentDirectory {
  def create[F[_]: Applicative](broker: Broker, storage: BrokerInstrumentStorage[F]): StoredInstrumentDirectory[F]
}

val binanceDirectory = StoredInstrumentDirectory.create[IO](Broker.Binance, brokerInstrumentStorage)
```

## Routing multiple sources (`MarketDataSources`)

Which source serves a ticker is a setting of the ticker, not of a watchlist entry, so every
consumer - `PriceHistorySyncService`, `LiveStrategyService`, `AlertProcess` and the command
handlers - goes through `gateways.MarketDataSources` instead of holding a single hard-coded source:

```scala
trait MarketDataSources[F[_]] {
  def settingFor(ticker: Ticker): F[SourceSetting]
  def forTicker(ticker: Ticker): F[MarketDataSource[F]]
  def serves(source: Source): Boolean
}
```

`MarketDataSourcesImpl(yahoo: Option[YahooSymbols[F]], tinkoff: Option[TinkoffBoards[F]], binance: Option[MarketDataSource[F]], settings: TickerSourceStorage[F], default: SourceSetting)`:

- `settingFor` is the setting `TickerSourceStorage.find(ticker)` holds for the ticker, or `default`
  when it holds none (the README quick start uses `SourceSetting.Yahoo(None)`).
- `forTicker` matches that setting exhaustively: `SourceSetting.Yahoo(symbol)` gives
  `yahoo.onSymbol(symbol)`, so a ticker set to a Yahoo symbol is looked up as that symbol, and
  `SourceSetting.Tinkoff(classCode)` gives `tinkoff.onBoard(classCode)`, so a ticker set to a board
  is served from that board, and `SourceSetting.Binance` gives the `binance` adapter. When the
  setting's source has no adapter wired (the option is `None`, e.g. no Tinkoff token is
  configured), it raises `MarketDataError.SourceNotConfigured(source)`, whose message names the
  source.
- `serves(source)` says whether the router has an adapter for the source: Yahoo while `yahoo` is
  defined, Tinkoff while `tinkoff` is defined and Binance while `binance` is defined. A caller that
  would write a setting asks it first, because a setting for a source the router cannot serve makes
  every sync of its ticker fail; the
  [held instruments service](#held-instruments-on-the-watchlist) does.

`forTicker` is for callers that need the adapter itself - `LiveStrategyServiceImpl.evaluate`,
`AlertProcessImpl`'s price-rule evaluation, `PriceHistorySyncServiceImpl.syncSymbol`,
`AnalysisServiceImpl` and the paper handler. Callers that only read stored data, with no adapter
involved - the valuation and tax services, the backtest and sweep handlers, and the dividend
reads of `/dividends` and `/export` - use `settingFor(ticker).source` instead (a ticker paired with
it is a `SourcedTicker`), so a source with no adapter wired does not stop them from reading what is
already stored. Bars and dividends of different sources coexist in storage, and one source's
series is never used in place of another's.

## Price-history sync

`services.PriceHistorySyncService[F[_]]`:

```scala
trait PriceHistorySyncService[F[_]] {
  def syncSymbol(item: WatchlistItem, today: LocalDate): F[SyncReport]
}
final case class SyncReport(ticker, source, fetched: Int, upserted: Int, dividends: Int, since: LocalDate, until: LocalDate)
final case class PriceHistorySyncConfig(
  interval: FiniteDuration,
  backfillYears: Int,
  overlapDays: Int,
  rateLimitBackoff: FiniteDuration,
  metadataRefresh: FiniteDuration
)
```

`PriceHistorySyncServiceImpl.syncSymbol` first resolves the ticker's adapter with
`MarketDataSources.forTicker(item.ticker)`; "source" below is that adapter's source, which
`SyncReport.source` reports. When the ticker's source has no adapter configured (the router raises
`MarketDataError.SourceNotConfigured`), a warning is logged and nothing is fetched: the report is
empty and carries the source the error names. Any other failure of the lookup, such as an
unreadable settings storage, fails the call. Then, per symbol:

1. Read the ticker's stored metadata once, with `InstrumentStorage.find`. A failing read is logged
   as a warning and the record counts as absent. The **origin** of the stored data has changed when
   a record exists and its `origin` differs from the adapter's (`InstrumentInfo.origin` against
   `MarketDataSource.origin`): another source, another board, another Yahoo symbol, or a Yahoo
   symbol added or dropped.
2. Unless the origin changed, look up `PriceHistoryStorage.lastBarDate(ticker, source)`.
3. `since = lastBarDate - overlapDays` if bars already exist and the origin did not change, else
   `today - backfillYears` (a full backfill). The `overlapDays` window re-fetches a few recent days
   on every run so a late provider-side correction to a recent bar is picked up. A changed origin
   refetches the whole window because bars and dividends are stored per source, not per board or
   Yahoo symbol, so the ones already stored may have been read from another board or symbol.
4. If `since` is after `today`, no fetch happens (an empty `SyncReport`, `dividends = 0`).
5. Otherwise `source.dailyBars(ticker, since, today)` is fetched and every bar upserted via
   `PriceHistoryStorage.upsertBars` (keyed by `(ticker, source, date)` - an upsert, so overlap days
   overwrite rather than duplicate).
6. `source.dividends(ticker, since, today)` is then fetched for the **same window** and upserted via
   `DividendStorage.upsert`; `SyncReport.dividends` is the count returned by that upsert. A
   dividends failure (the source raising, or the storage call failing) is logged as a warning and
   does **not** fail `syncSymbol` - the bar sync has already succeeded by that point, and
   `SyncReport.dividends` is `0` for that run.
7. Instrument metadata is refreshed if it is missing, older than `metadataRefresh`, or its origin
   changed, **after** the bar (and dividends) sync. The last condition makes switching a ticker's
   source, board or Yahoo symbol, and switching it back, refresh the metadata on the next sync,
   instead of leaving the old origin's currency, name and exchange in place until
   `metadataRefresh` passes.
   The refreshed record is also what marks the new origin as synced: a sync that fails before it
   sees the old origin again and refetches the window again, as does every sync of a ticker whose
   source returns no metadata. A metadata failure is logged as a warning and does not fail
   `syncSymbol`.

The refetch of a changed origin replaces only the days inside the window. Bars older than the
backfill window that a previous board or Yahoo symbol stored stay in the series, because bars are
keyed by `(ticker, source, date)` and nothing deletes them.

`processes.PriceHistorySyncProcessImpl` (mirrors `TcsPortfolioSyncProcessImpl`: `ProcessContext` /
`tofu.WithLocal`, tofu logging, self-restart on an uncaught loop failure):

- Every `config.interval`, first adds the instruments the accounts hold to their watchlists (see
  [Held instruments on the watchlist](#held-instruments-on-the-watchlist)), then loads
  `WatchlistStorage.allEnabled` and syncs each item. A failure of that first step is logged, and
  the cycle goes on with the watchlists as they are.
- Unlike the Tinkoff sync process, watchlist items are synced **sequentially, not in parallel** -
  so one rate-limited symbol backs off without racing the others against the same upstream limit.
- Per-item error isolation: a `MarketDataError.RateLimited(retryAfter)` logs a warning and sleeps
  `retryAfter` (falling back to `config.rateLimitBackoff`) before continuing to the next item; any
  other error is logged and the loop moves on.
- The README quick start configures this with `interval = 6h`, `backfillYears = 2`,
  `overlapDays = 5`, `rateLimitBackoff = 60s`, `metadataRefresh = 7d`.
- System alerts and the consecutive-failure threshold that drives them
  (`sync-failed:<ticker>` / `sync-recovered:<ticker>` / `process-crashed:price-history-sync`) are
  documented in [`alerts.md`](alerts.md#system-alerts).

## Held instruments on the watchlist

A ticker has bars, and so a price, only once it is on a watchlist, so a position nobody put on one
is unpriced in the valuation. `services.HeldInstrumentsService` puts the instruments every account
holds, at every broker, on that account's watchlist, with the [rate tickers](#rate-tickers-on-the-watchlist)
that convert its currencies:

```scala
trait HeldInstrumentsService[F[_]] {
  def addToWatchlists: F[Unit]
}

object HeldInstrumentsServiceImpl {
  def create[I[_]: Functor, F[_]: MonadThrow: Clock](
    accounts: AccountStorage[F], operations: AccountOperationStorage[F],
    transferCosts: TransferCostStorage[F], watchlist: WatchlistStorage[F],
    tickerSources: TickerSourceStorage[F], directory: InstrumentDirectory[F],
    sources: MarketDataSources[F], rates: RateTickerConfig
  )(implicit logs: Logs[I, F]): I[HeldInstrumentsService[F]]
}
```

`PriceHistorySyncProcessImpl` calls `addToWatchlists` at the start of every cycle, before it reads
the watchlist, so an instrument bought since the last cycle is synced in the cycle that adds it. For
every stored account, in turn:

1. **Holdings**: the instruments with a nonzero position, as [`Holdings`](analytics.md#holdings) of
   the account's executed operations as of now, read from `AccountOperationStorage` as far back as
   the valuation reads.
2. **Resolving**: each held instrument, in the order of its `InstrumentKey`, is resolved through the
   `InstrumentDirectory`. One the directory does not know is skipped. A lookup that fails is logged,
   naming the instrument and the account, and that instrument is skipped, so one failure does not
   stop the others. A futures contract that has expired, by its terms on today's UTC date, is not
   held: it is skipped like an unknown one, so it is neither watched nor given a source setting.
3. **Pricing**: a held ticker with no stored `TickerSource`, whether or not it is on the watchlist,
   gets the setting its broker's pricing rule gives (below). A stored setting is never changed. The
   setting is written before the ticker is added, so that a ticker is never synced from the default
   source in between. The rules give a setting only for a source the `sources` router serves, since
   a setting for a source it has no adapter for would only make every sync of the ticker fail.
4. **Adding**: a ticker the account's watchlist lacks, in any state (`WatchlistStorage.byAccount`
   lists disabled entries too), is added enabled, with the note `Added because the account holds it`.
   An entry that exists is left as it is, a disabled one included: `add` can re-enable an existing
   entry, so it is called only for a ticker the account lacks. Nothing is removed but the entry of an
   expired contract (step 6), so the entry of a sold holding stays. Two instruments of one ticker, such as one listed on two boards, add it
   once, and the first of them in key order sets its source. A ticker that has no setting and whose
   rule leaves it unwatched is not added.
5. **Rate tickers**: the watchlist is read again, and the rate tickers the account's currencies
   need are added to it (below).
6. **Expired contracts**: the entries this step added itself, those with the note above, whose
   ticker is a futures contract that the account traded and that has expired, held or not, are
   removed, so the price-history sync stops asking for bars that no longer come. The traded
   instruments are looked up only when the watchlist has an entry with that note whose ticker no
   instrument watched this cycle has, an expired contract being none of them. An entry with another
   note, the owner's or a rate ticker's, stays, and so does the entry of a contract sold before it
   expired, as any sold holding's does. A contract whose lookup fails is left as it is. The removal
   comes on the first cycle after the expiry, so the contract's last daily bar may be partial: the
   valuation does not need it, and a backtest of an expired contract takes its history from a source
   of its own.

The pricing rule is an exhaustive match on the instrument's `Broker`:

| Broker | Setting written |
|---|---|
| Tinkoff | `SourceSetting.Tinkoff` on the instrument's own board, `BrokerInstrument.classCode`, while the router serves Tinkoff; no setting otherwise, so the ticker keeps the default source |
| Interactive Brokers | `SourceSetting.Yahoo` under the symbol the [exchange rule](ibkr.md#yahoo-symbols) gives for the IB symbol and the listing exchange, written as `Yahoo(None)` when that symbol is the ticker itself, so that a deployment whose default source is not Yahoo still prices the instrument there; this while the router serves Yahoo, and no setting otherwise. An exchange without a rule, or an instrument that names none, gets no setting, and the service logs the ticker and its exchange |
| Binance | `SourceSetting.Binance` while the router serves Binance, so the coin joins the watchlist under its ticker, its pair such as `BTCUSDC`, and is priced from Binance's daily bars; while it does not, the outcome of the rule is `Unwatched`: the service writes no setting, leaves the ticker off the watchlist and logs it |

A storage that fails fails the call, and the price-history process logs it and goes on. An
instrument that has no setting after all of this is priced from the router's default source, except
one the rule leaves unwatched: a ticker that is `Unwatched` and has no setting of its own stays off
the watchlist, and one that has a setting of its own, such as a Binance coin set with
`/source BTCUSDC yahoo BTC-USD`, joins the watchlist as any other held ticker does. A Binance coin
whose ticker Binance does not list, such as one it has delisted, fails its price sync, which raises
the failure alert the sync has for any ticker.

### Rate tickers on the watchlist

The valuation and the tax estimate convert an amount in another currency with the close of that
currency's rate ticker, which is `<currency><base>=X` unless the deployment names another one for the
currency (see [`analytics.md`](analytics.md#rules) and [`tax.md`](tax.md#fx-conversion)). A rate
ticker has bars only once it is on a watchlist too, so the step adds them. `RateTickerConfig(baseCurrencies, fxTickers)` gives it
the base currencies to convert into, such as the valuation's and the tax estimate's, and the rate
tickers the deployment names:

```scala
final case class RateTickerConfig(baseCurrencies: Set[Currency], fxTickers: Map[Currency, Ticker] = Map.empty)
```

For each account the step collects the currencies that its executed operations are in, whatever
their kind, the currency each held instrument is priced in, which is the currency of its broker
record, and the currency of each cost entered for a transfer in (see
[`tax.md`](tax.md#transfers-in-with-an-entered-cost)), read from `TransferCostStorage`, since a gain
converts that cost on the day of the sale. For each of those currencies and each base currency other
than itself, `FxRates.rateTicker` gives the ticker. A ticker the account's watchlist lacks, in any state, is added enabled with the
note `Added for the exchange rate of <currency>`, and logged. An entry that exists is left as it is,
a disabled one included, and no source setting is written for a rate ticker: it is read from the
source its own setting names, else from the default source. Two currencies that share a ticker add it
once. A currency whose rate ticker has no bars, because its source cannot serve it, stays unpriced in
the valuation and in the tax estimate, and the sync of that ticker fails, which raises the failure
alert the sync has for any ticker.

Known limits:

- Two brokers' instruments under one ticker, such as an IB `VOD` on the LSE and a Tinkoff `VOD`,
  share the ticker's one setting: whichever is written first stays.
- A held ticker that is removed from a watchlist, with `/watchlist rm`, is added again at the next
  cycle. To keep a held ticker out of the sync, disable its entry instead of removing it.
- Removing a held ticker's setting with `/source SYM default` is undone at the next cycle, since a
  held ticker without a setting gets its broker's again. To keep a held ticker on the default
  source, set that source explicitly.

## Dividends

`models.market.DividendEvent(ticker, exDate: LocalDate, amount: BigDecimal, currency: Option[Currency], source)`
is the one dividend-event shape both `MarketDataSource` implementations produce and
`DividendStorage`/`/dividends` consume. `MarketDataSource.dividends(ticker, since, until)` raises
`MarketDataError` on failure, same as `dailyBars`; see the Yahoo and Tinkoff subsections above for
each implementation's own mapping (date derivation, amount conversion, error mapping and, for
Tinkoff, the skipped-entry rules).

```scala
trait DividendStorage[F[_]] {
  def upsert(events: List[DividendEvent]): F[Int]
  def byTicker(ticker: Ticker, source: Source, from: LocalDate, to: LocalDate): F[List[DividendEvent]]
  def upcoming(tickers: List[SourcedTicker], from: LocalDate): F[List[DividendEvent]]   // exDate >= from
}
```

Events of different sources for the same ticker coexist (the key includes the source), so every
read names the source, and `upcoming` takes each ticker paired with the one source to read it from.
`/dividends`, `/export dividends` and the analysis service read each ticker's events from the source
the ticker is configured to use (`MarketDataSources.settingFor`), so events another source stored
for it are left out.

`PriceHistorySyncServiceImpl` is the only writer (see "Price-history sync" above); the readers in
trading-pillar are `/dividends`, `/export` (see [`telegram-commands.md`](telegram-commands.md)) and
the analysis service. See [`storage-contracts.md`](storage-contracts.md) for a suggested relational
layout.

## Stock analysis

Package `services.analysis`. Combines a ticker's routed quote/instrument info
(`MarketDataSources.forTicker`, above), its stored daily bars (`PriceHistoryStorage`) and its
trailing-year dividends (`DividendStorage`, read from the source `forTicker` resolved) into one flat
`StockAnalysis` - the data behind the
[`/analyze` and `/compare`](telegram-commands.md#analyze) commands.

```scala
trait AnalysisService[F[_]] {
  def analyze(ticker: Ticker): F[StockAnalysis]
}
object AnalysisServiceImpl {
  def create[F[_]: MonadThrow: Clock](
    marketDataSources: MarketDataSources[F],
    priceHistory: PriceHistoryStorage[F],
    dividends: DividendStorage[F]
  ): AnalysisService[F]
}
```

**Ticker not found**: the quote/instrument-info lookup (`latestQuote` then `instrumentInfo`, both
on the source `forTicker` resolves) is the only step that can raise `MarketDataError.NotFound`.
When it does, `analyze` returns `StockAnalysis.empty(ticker)` - every `Option` field `None`,
`dividends12m` zero - **without** querying `PriceHistoryStorage` or `DividendStorage` at all. Every
other error (`RateLimited`, `Transient`, `SourceNotConfigured`, `Other`, or a storage failure)
propagates instead of being caught.

Fields split into two independent groups, plus one that spans both:

- **Quote/instrument-info fields** (`price`, `currency`, `asOf`, `instrumentType`, `exchange`) come
  straight from that lookup. `currency` prefers the quote's own currency, falling back to the
  instrument info's when the quote itself is `None` (a ticker can resolve with instrument info but
  no live price right now).
- **Bar-derived fields** (every return/SMA/RSI/ATR/52-week field) all come from one fetch of
  `PriceHistoryStorage.bars(ticker, source, today - 400 days, today)` - 400 calendar days,
  comfortably covering both the 200-bar SMA and the 364-day/52-week lookback with several weeks of
  margin for gaps and holidays - sorted by date (regardless of the order the storage returns them
  in) before use. They compare against that series' own latest stored close, **not** the live quote
  price, so they stay populated even when the quote has no price right now, as long as bars are
  stored.
- **`dividendYieldPct`** spans both: `dividends12m` (summed from `DividendStorage.byTicker(ticker,
  source, today - 1 year, today)`) divided by the quote's `price` - `None` whenever there's no live price,
  even if `dividends12m` itself is known.

| Field | Definition |
|---|---|
| `return1m`, `return3m`, `return1y` | `(latestClose - baseClose) / baseClose * 100`, where `baseClose` is the close of the stored bar nearest **on or before** `today - 30/91/365 days`. `None` when no stored bar is that old. |
| `sma50`, `sma200` | `BarHistory.sma(50)` / `.sma(200)` over the fetched bars. |
| `aboveSma50`, `aboveSma200` | `latestClose > sma50`/`sma200`; `None` unless both are defined. |
| `rsi14`, `atr14Pct` | `BarHistory.rsi(14)` (Wilder's RSI), and `BarHistory.atr(14)` (Wilder's ATR) as a percentage of `latestClose`. |
| `high52w`, `low52w` | The highest `Bar.high` / lowest `Bar.low` among the fetched bars dated within the trailing 52 weeks (364 days) of `today`. |
| `distanceToHigh52wPct`, `distanceToLow52wPct` | `(latestClose - high52w) / high52w * 100` (≤ 0) and `(latestClose - low52w) / low52w * 100` (≥ 0). |
| `dividends12m` | Sum of trailing-year `DividendEvent.amount`. Never `None` - zero when there were none. |
| `dividendYieldPct` | `dividends12m / price * 100`. |

Every division is `Indicators.divide` (`MathContext.DECIMAL64`); a field is `None` whenever its
inputs aren't available, the same convention `strategy.indicators.Indicators` already uses.

## News

Package `gateways.news`: `NewsItem(title: String, publisher: Option[String], link: String,
publishedAt: Option[ZonedDateTime])` and `trait NewsSource[F[_]] { def news(ticker: Ticker, count:
Int): F[List[NewsItem]] }`. Unlike `MarketDataSource`, there's only one implementation so far
(Yahoo); the trait exists so `/news` depends on an interface, not yfinance4s directly.

`gateways.news.YahooNewsApi[F[_]]` is a thin seam over `YFinanceClient.search` (news only):

```scala
trait YahooNewsApi[F[_]] {
  def search(query: String, newsCount: Int): F[List[NewsItem]]
}
object YahooNewsApi {
  def fromClient[F[_]: Functor](client: YFinanceClient[F]): YahooNewsApi[F]
}
```

`fromClient` calls `client.search(query, newsCount = newsCount)` (the library's own defaults for
`maxResults` and `enableFuzzyQuery` are left as-is; the quote/list parts of the result are discarded
- only `.news` is kept) and maps each yfinance4s `NewsItem` to trading-pillar's own: `title` and
`link` copy across directly; `publisher` becomes `None` for a blank string (yfinance4s's own field
is a required `String`, never null, but can in principle be empty); `publishedAt` is always `Some`
today (yfinance4s always derives a `publishTime` from the article's epoch timestamp) - it's
`Option` in the domain type because the source contract shouldn't assume every future producer can
supply one.

`gateways.news.YahooNewsSource[F[_]]` implements `NewsSource[F]` over `YahooNewsApi[F]`:
`news(ticker, count)` calls `api.search(ticker.value, count)` and maps `YFinanceError ->
MarketDataError` with the same table `YahooMarketDataSource` uses (`TickerNotFound`/`Delisted ->
NotFound`, `RateLimited -> RateLimited`, `NetworkError -> Transient`, `DataParseError -> Other`,
anything else -> `Other`).

`/news` searches Yahoo for the ticker as given; a Yahoo symbol in the ticker's setting is not
applied there. See [`telegram-commands.md`](telegram-commands.md#news) for `/news`.

## Screener

Package `gateways.screener`: trading-pillar's own 3-value `PredefinedScreen` enum (`Gainers`,
`Losers`, `Active` - `enumeratum` `StringEnumEntry`, values `"gainers"`/`"losers"`/`"active"`, same
shape as `Source`/`Command`), `ScreenerRow(ticker, name: Option[String], price: Option[BigDecimal],
changePct: Option[BigDecimal])` and `trait Screener[F[_]] { def predefined(screen: PredefinedScreen,
count: Int): F[List[ScreenerRow]] }`. `changePct` is Yahoo's own `regularMarketChangePercent`
carried through as-is - **already a percentage value** (e.g. `3.5` means `+3.5%`), not a `0..1`
fraction like `Allocation`/`Rebalancer` weights elsewhere in this codebase.

`gateways.screener.YahooScreenerApi[F[_]]` is a thin seam over
`YFinanceClient.screener.screenPredefined`:

```scala
trait YahooScreenerApi[F[_]] {
  def predefined(
    screen: io.github.coinductive.yfinance4s.models.PredefinedScreen,
    count: Int
  ): F[List[ScreenerRow]]
}
object YahooScreenerApi {
  def fromClient[F[_]: Functor](client: YFinanceClient[F]): YahooScreenerApi[F]
}
```

The seam takes yfinance4s's *own* `PredefinedScreen` (its full 15-value enum) rather than a
parallel type, since it's already a small closed enumeration - trivially fakeable in tests without
constructing any other yfinance4s model. `fromClient` calls `client.screener.screenPredefined(screen,
count)` and maps each yfinance4s `ScreenerQuote` to `ScreenerRow`: `ticker` from `symbol`, `name`
from `longName orElse shortName`, `price` from `regularMarketPrice`, `changePct` from
`regularMarketChangePercent` (`Double -> BigDecimal` the same way `YahooMarketDataSource` converts
prices: `java.math.BigDecimal.valueOf`, scaled to 18 decimals).

`gateways.screener.YahooScreener[F[_]]` implements `Screener[F]` over `YahooScreenerApi[F]`, and is
the one place trading-pillar's 3 screens map onto yfinance4s's 15:

| trading-pillar `PredefinedScreen` | yfinance4s `PredefinedScreen` |
|---|---|
| `Gainers` | `DayGainers` |
| `Losers` | `DayLosers` |
| `Active` | `MostActives` |

(yfinance4s also has `AggressiveSmallCaps`, `GrowthTechnologyStocks`, `MostShortedStocks`,
`SmallCapGainers`, `UndervaluedGrowthStocks`/`UndervaluedLargeCaps` and six fund screens - none
exposed by `/screen` today.) Errors map through the same `YFinanceError -> MarketDataError` table as
`YahooMarketDataSource`, minus the ticker-specific cases (`TickerNotFound`/`Delisted`): a screener
call has no single ticker to attach `NotFound` to.

See [`telegram-commands.md`](telegram-commands.md#screen) for `/screen`.

## Storage contracts

`PriceHistoryStorage[F[_]]`, `InstrumentStorage[F[_]]`, `WatchlistStorage[F[_]]`,
`DividendStorage[F[_]]`, `TickerSourceStorage[F[_]]` and `BrokerInstrumentStorage[F[_]]` are the
traits a consumer implements. See [`storage-contracts.md`](storage-contracts.md) for every method
and a suggested relational layout.

## Known limitation: Yahoo does not serve MOEX

Yahoo Finance no longer serves data for MOEX (Russian exchange) instruments, so symbols like
`SBER` cannot be synced through `YahooMarketDataSource`. Consequences:

- `InstrumentStorage` is keyed by ticker, not FIGI (Yahoo has no FIGIs); `figi` is an optional
  cross-reference field instead.
- The scrape universe is an explicit watchlist (`WatchlistStorage`, managed from the `/watchlist`
  command and whatever tooling the consumer provides), not derived from the Tinkoff portfolio.
- Examples in these docs use a Yahoo-served ticker such as `AAPL`; a MOEX ticker such as `SBER`
  needs the Tinkoff source described next.
- MOEX symbols are served instead through `TcsMarketDataSource` (see above): the ticker is set to
  Tinkoff (`/source SBER tinkoff`, `/watchlist add SBER tinkoff`, or a `TickerSource` stored through
  the consumer's own tooling), and `MarketDataSources.forTicker` (also above) routes the consumers
  that fetch data - sync, live strategy evaluation, the alert process - to the right
  `MarketDataSource` per ticker. `Source.Tinkoff` bars co-exist with `Source.Yahoo` bars in
  `PriceHistoryStorage`, since a bar is keyed by `(ticker, date, source)`; `/backtest` and the
  valuation read the bars stored for the ticker's source.
