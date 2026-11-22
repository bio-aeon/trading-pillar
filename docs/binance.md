# Binance

Packages `st.btu.trading.pillar.models.binance` (identifiers, keys, records, balances, errors),
`gateways.binance` (the REST clients, their pacing and the market data source), `services`
(`BinanceSyncService`, `BinanceSyncServiceImpl`, `BinanceSyncConfig`, `BinanceReconciler`),
`processes` (`BinanceSyncProcess`, `BinanceSyncProcessImpl`) and the storages
`BinanceRecordStorage`, `BinancePairStorage` and `BinanceMinutePriceStorage`.

A Binance spot account is read through Binance's REST API with a key that can read and do nothing
else. The library stores every record exactly as Binance sent it, translates the records into
`AccountOperation`s, and so the valuation, the tax services and `/export` cover the account like an
account at any other broker. It checks the recorded balances against the ones Binance reports, and
it prices the coins from Binance's own daily bars. The library has no call that places an order,
withdraws or transfers, and it refuses a key that could.

Binance's API is not a complete record of an account, which shapes the sync:

- No endpoint lists the pairs an account traded, and trades are read pair by pair. A pass over all
  of about 1,900 listed pairs costs about 38,000 request weight.
- The trades of a pair that Binance has delisted cannot be read once the pair leaves its exchange
  information, and Binance delists pairs in batches.
- Users report trades, deposits and withdrawals from before about September 2022 missing from the
  API; Binance documents no cutoff.
- Some operations have no source in the API at all: card spending and cashback, referral rebates,
  token swaps and renames, and the automatic stablecoin conversions of 2022 and 2023.
- Most history endpoints cap a request at 30 to 180 days, and silently return a default window when
  no times are given.

## Setting up the key

In the account's API Management page, create an API key of the system-generated (HMAC) kind and
give it a label. Then:

1. Tick **Enable Reading** and nothing else. Leave spot and margin trading, withdrawals, internal
   and universal transfers, margin, futures, options and portfolio margin unticked.
2. Restrict access to trusted IP addresses and add the address of the machine the sync runs on.
   The key then works from that address alone, so a leaked key is of no use elsewhere. A key with no
   restriction is accepted, and a warning is logged on every run.

Each run starts with `GET /sapi/v1/account/apiRestrictions` and fails with a `BinanceKeyError`,
before any other call, unless `enableReading` is true and every other permission flag is false or
absent. A permission flag is every boolean field of the answer whose name starts with `enable` or
`permits`, `enableFixReadOnly` aside, so a permission Binance adds later is refused too. The error
names the flags and never the key.

`ApiKey` and `SecretKey` are secrets: their `Show` and `Loggable` instances print a mask, and
`BinanceAccount.toString` prints neither. Only a request takes `value`.

The sync is written for binance.com, Binance's global entity. A regional entity has an API of its
own, and whether an account's entity serves the `/sapi` endpoints at all is not confirmed.

## What the sync reads

| Source | Endpoint | Window | Record id | Dated by |
|---|---|---|---|---|
| `trade` | `GET /api/v3/myTrades`, per pair | by trade id, 1,000 to a page | `<symbol>/<id>` | `time` |
| `deposit` | `GET /sapi/v1/capital/deposit/hisrec` | 89 days | `id` | `insertTime` |
| `withdrawal` | `GET /sapi/v1/capital/withdraw/history` | 89 days | `id` | `applyTime`, a UTC string |
| `fiat_deposit`, `fiat_withdrawal` | `GET /sapi/v1/fiat/orders` | 89 days | `orderNo` | `createTime` |
| `fiat_buy`, `fiat_sell` | `GET /sapi/v1/fiat/payments` | 89 days | `orderNo` | `createTime` |
| `convert` | `GET /sapi/v1/convert/tradeFlow` | 30 days | the digits of `orderId` | `createTime` |

A fiat order and a fiat payment each carry no direction of their own, so the direction asked for
decides the source. A Convert `orderId` exceeds what a double holds, so it is read as the digits
Binance sent.

Besides the records, each run reads the balances Binance reports, in the spot wallet
(`GET /api/v3/account`) and the funding wallet (`POST /sapi/v1/asset/get-funding-asset`), to
compare them with what the records leave (see [Balances](#balances-and-the-reconciliation)).

What the sync does not read: Simple Earn, staking, Binance Pay and P2P, transfers between the
account's wallets (its spot and funding wallets count as one pool), margin, futures, options, loans
and the other products, the dust conversions and the distributions of the asset dividend log, and
the website's statement export. Only what changes the spot wallet through the sources above is
recorded.

## The client

Two ports, over sttp (`SttpBackend[F, Any]`): `gateways.binance.BinanceMarket[F]`, the calls that
need no key, and `BinanceApi[F]`, which extends it with the calls that read an account.
`BinanceMarketImpl` is the keyless implementation, one for the process, and `BinanceApiImpl` the
keyed one, one for each key. Both make their calls through one shared transport, so that pacing,
retries, the classification of answers and the redaction of secrets exist once. An endpoint is
public or signed by its type, and the transport sends a signed one only with a signer, so only the
keyed client, which holds the key, can make a signed call.

```scala
trait BinanceMarket[F[_]] {
  def exchangeInfo: F[List[BinancePair]]
  def minuteClose(symbol: BinanceSymbol, minute: Instant): F[Option[BigDecimal]]
  def dailyBars(symbol: BinanceSymbol, from: LocalDate, to: LocalDate): F[List[BinanceDailyBar]]
  def lastPrice(symbol: BinanceSymbol): F[Option[BigDecimal]]
}

trait BinanceApi[F[_]] extends BinanceMarket[F] {
  def apiRestrictions: F[BinanceKeyPermissions]
  def delistSchedule: F[List[BinanceSymbol]]
  def coins: F[Map[BinanceAsset, String]]
  def myTrades(symbol: BinanceSymbol, fromId: BinanceTradeId): F[List[JsonObject]]
  def deposits(from: Instant, to: Instant): F[List[JsonObject]]
  def withdrawals(from: Instant, to: Instant): F[List[JsonObject]]
  def fiatOrders(direction: FiatOrderDirection, from: Instant, to: Instant): F[List[JsonObject]]
  def fiatPayments(direction: FiatPaymentDirection, from: Instant, to: Instant): F[List[JsonObject]]
  def convertTradeFlow(from: Instant, to: Instant): F[List[JsonObject]]
  def spotBalances: F[Map[BinanceAsset, BigDecimal]]
  def fundingBalances: F[Map[BinanceAsset, BigDecimal]]
}

object BinanceMarketImpl {
  def create[I[_]: Sync, F[_]: Async](config: BinanceApiConfig, backend: SttpBackend[F, Any],
                                      ipBuckets: BinanceIpBuckets[F])(implicit logs: Logs[I, F]): I[BinanceMarket[F]]
}

object BinanceApiImpl {
  def create[I[_]: Sync, F[_]: Async](apiKey: ApiKey, secretKey: SecretKey, config: BinanceApiConfig,
                                      crypto: Crypto[F], backend: SttpBackend[F, Any],
                                      ipBuckets: BinanceIpBuckets[F])(implicit logs: Logs[I, F]): I[BinanceApi[F]]
}
```

Records come back as the JSON objects Binance sent, so that they are stored as received; the other
answers come back as typed values. The windows are inclusive at both ends, to the millisecond.
`BinanceDailyBar` is one daily bar of a pair, with the UTC day it opens on, its open, high, low and
close, its volume in the base asset, all as Binance wrote them, and its close time.

The consumer builds one `BinanceIpBuckets` for the process and gives it to `BinanceMarketImpl.create`
and to `BinanceSyncProcessImpl.resource`, so that every client calling from the address is paced
together.

| Call | Endpoint | Weight | Notes |
|---|---|---|---|
| `apiRestrictions` | `/sapi/v1/account/apiRestrictions` | 1 | |
| `exchangeInfo` | `/api/v3/exchangeInfo` | 20 | every pair open to spot, margin or leveraged trading, in any trading status; needs no key |
| `delistSchedule` | `/sapi/v1/spot/delist-schedule` | 100 | the symbols announced for delisting |
| `coins` | `/sapi/v1/capital/config/getall` | 10 | the name of each coin |
| `myTrades` | `/api/v3/myTrades` | 20 | one page of at most `BinanceApi.TradesPageSize` (1,000) trades from an id; `-1121` (a pair Binance does not know) gives no trades |
| `deposits` | `/sapi/v1/capital/deposit/hisrec` | 1 | paged by `offset`, 1,000 to a page, up to a short page |
| `withdrawals` | `/sapi/v1/capital/withdraw/history` | 18,000 per account | paged like deposits |
| `fiatOrders` | `/sapi/v1/fiat/orders` | 45,000 per account | by `transactionType` (0 deposit, 1 withdrawal), paged by `page`, 500 rows to a page; the envelope's `data` is absent when there is none |
| `fiatPayments` | `/sapi/v1/fiat/payments` | 1 | by `transactionType` (0 buy, 1 sell), paged like fiat orders |
| `convertTradeFlow` | `/sapi/v1/convert/tradeFlow` | 3,000 per account | `limit` 1,000; a window that answers `moreData` is read again as two halves, down to a single millisecond, which fails |
| `minuteClose` | `/api/v3/klines` on `data-api.binance.vision` | 2 | the close of the one-minute bar that starts at the minute, none when the pair had no bar or is unknown; needs no key |
| `dailyBars` | `/api/v3/klines` on `data-api.binance.vision`, interval `1d` | 2 per page | the bars that open on the UTC days from the first to the last, both included, from the start of the first to the last millisecond of the last, 1,000 to a page; the next page starts the day after the last bar, and the reading ends at a page of fewer, or one that brings no day past where it started. A pair Binance does not know fails with `Refused` and code `-1121`; needs no key |
| `lastPrice` | `/api/v3/ticker/price` on `data-api.binance.vision` | 2 | the price of the latest trade, none for a pair Binance does not know; needs no key |
| `spotBalances` | `/api/v3/account` | 20 | `omitZeroBalances=true`; the `free` and `locked` amount of each asset added up, an asset with none left out |
| `fundingBalances` | `POST /sapi/v1/asset/get-funding-asset` | 1, in a bucket of its own per IP address | the `free`, `locked`, `freeze` and `withdrawing` amount of each asset added up, an asset with none left out |

### Signing

`https://api.binance.com` serves everything signed and `exchangeInfo`; `klines` and the ticker price
go to `https://data-api.binance.vision`, which needs no key. A signed request carries the key in
`X-MBX-APIKEY` and a query of its parameters, then `recvWindow` (10 seconds by default) and
`timestamp`, then `signature`. A POST, such as the funding wallet's, is signed like a GET and sends
its signed query string as the query, with no body.

- The query string is built once. Each name and value is percent-encoded from its UTF-8 bytes, with
  RFC 3986's unreserved characters left as they are, as Binance has required of signed requests
  since January 2026. The HMAC-SHA256 of that exact string, in lower-case hexadecimal, is the
  signature, and the request is sent with the string unchanged: sttp would encode a query given in
  pieces its own way, and Binance refuses a signature over another encoding.
- The `timestamp` is the local time moved by the offset to Binance's clock, read from
  `GET /api/v3/time` before the first signed call and again when Binance refuses a timestamp with
  `-1021`, after which the call is tried again once. Pacing happens before the timestamp is taken,
  so a call that waits for its turn is not stamped before it waits.

### Pacing

Binance counts request weight per minute, and the client keeps each call's share of a limit before
the next call of the same bucket:

- `/api/v3` has one bucket of 6,000 a minute per IP address, shared by the pairs, the trades and the
  spot account, and `data-api.binance.vision`, the bars and the ticker price, one of its own.
- Each `/sapi` endpoint has a bucket of its own, "independent" of the others: 12,000 a minute per IP
  address for the endpoints weighed by IP (the funding wallet among them), 180,000 a minute per
  account for the ones weighed by account (withdrawals, fiat orders and Convert).
- A call keeps the next call of its bucket waiting for `limitWindow * weight / (limit * budget)`:
  the share of the limit its weight takes, divided by a budget that leaves room for every other
  process on the address. The budgets are 0.5 per IP address and 0.9 per account. A `myTrades` call
  therefore holds its bucket for 0.4 seconds, a withdrawal history for about 6.7 seconds and a fiat
  order history for about 16.7 seconds.
- The buckets counted per IP address are shared by every client, the market client and the client of
  every account, so build one `BinanceIpBuckets` with `BinanceIpBuckets.create` and give it to each
  client. Each keyed client has its own buckets counted per account.

The client does not read Binance's used-weight headers, and it does not read `Retry-After`, which
has been seen to say 0.

### Errors and retries

`BinanceError` is sealed:

| Error | When |
|---|---|
| `Refused(code, reason)` | Binance refused the request, with its own code and message, such as `-2015` for a key that is invalid, not allowed from this IP or lacks the permission, and `-1022` for a bad signature. An error body that comes with a success status counts too, and so does a fiat answer whose `code` is not `000000`. |
| `RetriesExhausted(status, attempts)` | the answer was 429 or a 5xx on every attempt |
| `Banned(endpoint)` | HTTP 418, the ban of the IP address, which waiting a little does not end: it fails the call at once |
| `UnexpectedResponse(detail)` | an answer that is not of the documented shape, or a status that waiting does not change, such as 403 |
| `Unreachable(detail)` | no answer came on any attempt |

A 429 is tried again after `rateLimitDelay`, a 5xx and a call that got no answer after `retryDelay`,
up to `maxAttempts` calls in all. `-1121` on `myTrades`, on `minuteClose` and on `lastPrice` is a pair
Binance does not know, such as a delisted one, and is an answer and not an error; on `dailyBars` it
is raised, as the refusal it is.

The query string, the signature and the keys are never logged or put in an error: they are taken
out of Binance's message and out of the description of a failure, which is built from the cause
alone, because the exception of the HTTP client carries the request.

`BinanceApiConfig` holds what differs between deployments, with the defaults of a real
deployment: `recvWindow` (10 s), `limitWindow` (1 minute), `ipBudget` (0.5), `accountBudget` (0.9),
`retryDelay` (10 s), `rateLimitDelay` (1 minute) and `maxAttempts` (5).

## Market data

`BinanceMarketDataSource` makes the keyless client a `MarketDataSource` (see
[`market-data.md`](market-data.md#binance-implementation-binancemarketdatasource)): a ticker set to
`SourceSetting.Binance` is read as the Binance pair of the same symbol, its daily bars, its last
price and its description come from `BinanceMarket`, and its prices are in the quote asset of the
pair. A consumer wires it as the router's Binance adapter:

```scala
for {
  market <- BinanceMarketImpl.create[IO, F](config, backend, ipBuckets)
  binance <- BinanceMarketDataSource.create[IO, F](market)
  sources = new MarketDataSourcesImpl[F](yahoo, tinkoff, Some(binance), tickerSources, SourceSetting.Yahoo(None))
} yield sources
```

While the router serves Binance, the held instruments service sets the ticker of every coin an
account holds, `BTCUSDC`, to Binance and puts it on the account's watchlist, so that the
price-history sync reads its bars and the valuation, the tax estimate and `/positions` can price the
position. The ticker of a coin is its pair with the first price quote that Binance lists (see
[Recording](#recording)), and the bars are in that quote. A coin whose ticker Binance does not list,
such as one it has delisted, fails its price sync. The quote is a stablecoin, valued by its own rate
ticker, which the same service puts on the watchlist (see
[`market-data.md`](market-data.md#rate-tickers-on-the-watchlist)).

## The sync

`BinanceSyncService[F]` brings the records of one Binance account up to date;
`BinanceSyncServiceImpl.create` builds it for a `BinanceAccount(accountId, apiKey, secretKey)`.

```scala
final case class BinanceSyncConfig(
  cashAssets: Set[BinanceAsset] = Set(USDT, USDC, FDUSD, BUSD),
  priceQuotes: NonEmptyList[BinanceAsset] = NonEmptyList.of(USDC, USDT),
  historyStart: LocalDate = LocalDate.of(2017, 7, 14),
  rereadDays: Int = 7,
  pairsRescanInterval: FiniteDuration = 1.day)

trait BinanceSyncService[F[_]] {
  def sync: F[Unit]
  def rebuildTranslations: F[Unit]
}
```

`cashAssets` are the assets that are currencies: the stablecoins the account holds, and the fiat
currencies it trades against, such as `EUR`. Every other asset is a coin, an instrument with tax
lots. `priceQuotes` are the cash assets a coin is priced in, in order of preference, each a cash
asset. `historyStart` is the day a source with no stored record is first read from, at midnight
UTC; set it to the day the account opened to skip the years before. The construction fails for a
price quote that is not a cash asset and for a negative `rereadDays`.

### One run

1. Check the key (see [Setting up the key](#setting-up-the-key)).
2. Read `exchangeInfo`, the pairs Binance lists.
3. Read the clock, and then the balances of the spot and the funding wallet. The comparison of
   those balances with the records is made as of that instant. The coins whose balance differs
   from what the records stored so far leave, in a way the last comparison did not find, have
   their pairs read in this run (see
   [Pairs](#pairs)). Write the instruments of the coins the rebuild named (see
   [Rebuilding the translations](#rebuilding-the-translations)).
4. Read each source other than trades, in the order of the table above, window by window, oldest
   first, recording each window before the next is read.
5. Read the trades.
6. Compare the balances with what the records leave now, and raise the alerts (see
   [Balances and the reconciliation](#balances-and-the-reconciliation)).

Anything that fails ends the run, and the process starts a new one after its interval.

### Windows

A source other than trades is read from `rereadDays` before where this process last read it, kept in
memory, so that a record Binance completes or changes late is stored again. After a restart, with
nothing in memory, it is `rereadDays` before the latest record stored for the source; with no
record stored, it is the start of `historyStart`. A window is 89 days (30 for Convert) and ends a
millisecond before the next one starts; the last ends at the start of the run. A failed run leaves
the memory as it was after the last window that was recorded, and the service that a crashed loop
starts again with keeps it.

A first import is slow, because Binance's limits are low next to the history of an account. Each
call of a bucket waits for its share, so the weights above add up: an account opened in 2021 takes
about half an hour (the withdrawal and fiat order histories, 25 windows each, and the trade scan of
about 13 minutes), and reading from Binance's launch takes about 40 minutes. A source with no
stored record is read from `historyStart` again after each restart.

### Pairs

Trades are read pair by pair, from the id after the last trade stored for the pair, or from 0 for a
pair with none, a page at a time until a page brings fewer than 1,000 trades. A pair is read only
while `exchangeInfo` lists it, since a pair Binance has dropped answers `-1121`.

- **A full read** of every listed pair is made by the first run of a process, and then once every
  `pairsRescanInterval`, which finds a pair the account traded for the first time. The time of the
  last full read is kept in memory, and a full read that fails is made again by the next run.
- **Any other run** reads the pairs that have stored trades, the pairs of every coin that another
  record has just brought into the account, the pairs of every coin whose balance differs from the
  records in a new way, and the pairs `delist-schedule` announces, so that a pair is read before
  Binance drops it.
- **A coin that arrived**: an executed deposit, Convert or card purchase of a coin brings every
  listed pair with that coin as its base or quote into the run. A cash asset brings none: reading
  every USDT pair after a USDT deposit would cost minutes. A record brings its pairs in once for
  as long as the process runs, though the record is read again for `rereadDays`, and a record that
  was pending brings them in once it is executed.
- **A coin whose balance differs**: a coin, not a cash asset, whose balance at Binance is not what
  the records stored before the run leave as of the instant the balances were read brings every
  listed pair with that coin as its base or quote into the run, so that a coin bought on a pair
  new to the account appears within one interval and not at the next read of every pair. It does so
  once for a difference: a coin whose difference is the one the last comparison found brings no
  pair in, and a new trade of the coin changes its balance and so its difference. A difference that
  no trade explains, such as dust converted into BNB or a reward, therefore costs the coin's pairs
  once, at 20 request weight each, and once more after a restart, which forgets the last comparison.

### Prices

A trade between two coins and a fee paid in BNB have no cash amount, so the translation values them
by the close of a one-minute bar, found among candidates in order of preference (see
[Translation](#translation)). The sync resolves them before it translates a batch:

1. The stored closes of every bar the batch might use are loaded from `BinanceMinutePriceStorage`.
2. A record's candidates are taken in order: the first one stored is used, and each one before it
   that is not stored is read with `minuteClose` first, so that a close stored for a later
   candidate, by another record, does not stand in for an earlier one Binance has. A candidate
   pair that `exchangeInfo` does not list is never asked for.
3. The closes read are stored, once for the batch, before the batch is recorded.

The translation takes the first candidate that has a stored close. Since the earlier candidates had
no bar when the sync looked, translating a record again reads the price it was first translated
with. Every bar is of the minute that holds the record's time. A bar of the minute that is still
running holds the close so far.

### Recording

Records are translated and recorded in batches of at most 500, in this order: the prices, then the
pairs of the batch's trades in `BinancePairStorage`, then the instruments of the coins its
translations name, then the notes below, and the batch last. A failure before the batch is
recorded leaves its records to be read again, which a failure after it would not. Records that a
read gives twice, which paging a history that grows can do, are recorded once.

The instruments are `BrokerInstrument`s upserted in `BrokerInstrumentStorage`, one for each coin,
ordered by asset code: the key is `InstrumentKey(Broker.Binance, <asset>)`; the ticker is the coin's
pair with the first price quote that `exchangeInfo` lists, `BTCUSDC`, or its pair with the first
price quote when none is listed; there is no board; the name is the coin's, read once in a run with
`coins`, or the asset code when Binance gives none or the names cannot be read; the type is
`crypto`; and the currency is that quote. A ticker named by a pair keeps a coin apart from a stock
of the same code. Binance has no lookup of a coin by its code, so the coins' directory,
`StoredInstrumentDirectory.create(Broker.Binance, storage)`, answers from these records alone.

### Notes

What no price could value is noted, and the sync raises the system alert
`binance-unvalued:<account id>` for the notes it has not raised yet. Each note names the record by
its `ref` and says what the translation did instead: the two legs of a trade or a Convert between
coins that no price values become a conversion that carries their cost, and a commission or a
network fee that no price values leaves as units at their cost. The alert never names a key. The
notes raised are kept in memory, so a restart raises the ones that still stand once more, and an
alert that failed to go out is tried again at the next run.

### Balances and the reconciliation

The API is not a complete record of an account, so each run compares what the records leave with the
balances Binance reports, and raises the difference as a system alert, as `IbkrReconciler` does for
the positions at Interactive Brokers. `BinanceReconciler` does it; `BinanceSyncProcessImpl.resource`
builds one from the sync configuration's cash assets and the `AccountOperationStorage` it is given,
and the services of all the accounts share it.

```scala
final class BinanceReconciler[F[_]] {
  def differingCoins(accountId: AccountId, balances: BinanceBalances,
                     listed: Iterable[BinancePair], asOf: Instant): F[Set[BinanceAsset]]
  def reconcile(accountId: AccountId, balances: BinanceBalances,
                listed: Iterable[BinancePair], asOf: Instant): F[Unit]
}

object BinanceReconciler {
  def create[I[_]: Sync, F[_]: Sync](operations: AccountOperationStorage[F], systemAlerts: SystemAlerts[F],
                                     cashAssets: Set[BinanceAsset])(implicit logs: Logs[I, F]): I[BinanceReconciler[F]]
}
```

**What is compared.** The balance of an asset is its amount in the spot wallet and in the funding
wallet added up (`BinanceBalances(spot, funding)`). What the account recorded is what its executed
operations that occurred before `asOf` leave: for a coin, its position, and for a cash asset, the sum
of the `amount` of the operations in that currency. An asset is cash when it is in
`BinanceSyncConfig.cashAssets`, so a fiat currency the account holds has to be there, or it is
compared with a position it never has. The comparison is exact, an asset on one side only counts as
zero on the other, and the differences come ordered by asset.

The `LD` tokens that Simple Earn shows in the spot wallet are left out. A spot asset named `LD` and
another asset is one when no listed pair trades it and a listed pair trades the other asset:
`LDBTC` is, and `LDO`, a coin of that name, is not.

**Two comparisons in a run.** A run reads `asOf` from the clock and then both balances, right after
`exchangeInfo`, and compares them twice as of `asOf`, so that a record dated at or after it, which
the balances may not hold yet, is never counted:

1. Before it reads any record, `differingCoins` gives the coins, never a cash asset, whose balance
   differs from what the records stored so far leave, by a difference the last `reconcile` of the
   account did not find. It raises nothing. The run reads the listed pairs of those coins with the
   other pairs (see [Pairs](#pairs)).
2. After the trades, `reconcile` compares again with what the run recorded.

A failure to read either wallet ends the run before any record is read.

**Alerts**, raised by `reconcile` and kept in memory per account, so that a restart raises the
differences that are still there once more:

| Alert | When |
|---|---|
| `binance-reconciliation:<account id>` | the balances differ from what the records leave, and the differences are not the ones raised last. The title is `Binance balances differ: account <id>`, and the body names each asset as `<asset>: recorded <amount>, Binance <amount>`, in plain digits without trailing zeros, and the instant `asOf` |
| `binance-reconciled:<account id>` | the first comparison that finds the balances equal after a `binance-reconciliation` was raised, once. The title is `Binance balances match: account <id>` |

An alert is raised before it is remembered, so one that fails to go out is tried again at the next
run.

**Expected differences**, until the dust conversions, the rewards and airdrops and the costs of
coins deposited from elsewhere are read, or the statement is imported:

- dust converted into BNB, and rewards and airdrops credited to the spot wallet;
- card spending and cashback from the funding wallet, and assets moved into Simple Earn or staking,
  which no longer show in the spot wallet;
- trades on a pair Binance delisted before the sync read them;
- a deposit credited, or a withdrawal still processing, around `asOf`, until the next run.

The funding wallet's call lists the business assets Binance names (Binance Pay, Binance Card, gift
cards, stock tokens), and whether it shows the balances of P2P is not stated, so an asset held there
may differ too.

### Rebuilding the translations

`rebuildTranslations` translates every stored record again with the stored pairs and the stored
closes, which replaces the stored translations of each record as a whole and so removes one a
record no longer produces. It leaves the stored records as they are, asks Binance for nothing, and
raises the alert for the notes it has not raised yet. A trade whose pair was never stored fails
it. It writes no instrument, since a coin's ticker is chosen from the pairs Binance lists: the
next run writes the instruments of the coins its translations name, so a coin that only a change
to the translation brought in is described too. The process runs it once before the first run of
each account, which fills the shared operations on the first run and brings them up to date after
a change to the translation.

### The process

`BinanceSyncProcessImpl` runs one loop per `BinanceAccount` it is given, named
`binance-sync:<account id>`: first the rebuild, until it succeeds once, then a run every
`interval`. The loops run in a `ProcessContext` that names the account. A failure, a refused key
and an unreadable storage included, is logged and raised as the system alert
`process-crashed:binance-sync:<account id>` by `ProcessLoop.restarting`, and the loop starts again
after `interval`; the other accounts go on. `resource` builds a client and a sync service for each
account, one reconciler that they share, and the process. The `BinanceIpBuckets` it is given is the
one the market client and the market data source are paced by too.

```scala
object BinanceSyncProcessImpl {
  def resource[I[_]: Sync, F[_]: Async: GenUUID](
    accounts: List[BinanceAccount], config: BinanceSyncConfig, interval: FiniteDuration,
    recordStorage: BinanceRecordStorage[F], pairStorage: BinancePairStorage[F],
    priceStorage: BinanceMinutePriceStorage[F], instrumentStorage: BrokerInstrumentStorage[F],
    operationStorage: AccountOperationStorage[F], systemAlerts: SystemAlerts[F], crypto: Crypto[F],
    backend: SttpBackend[F, Any], ipBuckets: BinanceIpBuckets[F],
    apiConfig: BinanceApiConfig = BinanceApiConfig()
  )(implicit logs: Logs[I, F], WL: WithLocal[F, ProcessContext]): Resource[I, BinanceSyncProcessImpl[F]]
}
```

## Translation

The translation of a record is pure: it depends on the record, which assets count as cash, the
pairs trades were made on, and the closes of the bars. One record gives one or more operations.

The `ref` of the first is the record's own, `BinanceRecord.ref`: its source and its record id, such
as `trade/BTCUSDC/28457` or `deposit/9876`, unique within the account. The others add a suffix after
a slash, so that the storage replaces a record's operations as a whole by that prefix
(`BinanceRecord.owns`):

| Suffix | The operation |
|---|---|
| none | the record's own operation, or the side of an exchange that gives up |
| `/received` | the side of an exchange that receives |
| `/fee` | a fee |
| `/fee-sale` | the sale of the coin that paid a fee |
| `/deposit` | the deposit that comes with a purchase paid by card |

`occurredAt` is the record's time, and every operation of a record shares it. The instrument of an
operation is `InstrumentKey(Broker.Binance, <asset>)` for an asset that is not cash. An operation
that moves a coin and no cash carries the first price quote as its currency.

### Trades and Convert orders

A trade gives up one asset and receives the other: on a buy (`isBuyer`) the base is received and the
quote given up, on a sell the other way. A Convert gives up `fromAsset` and receives `toAsset`, with
no commission. Both read the same way, by what the two assets are:

| Given up | Received | Operations |
|---|---|---|
| cash | a coin | `buy` of the coin at the amount of cash, at the trade's price (derived from the amounts for a Convert) |
| a coin | cash | `sell` of the coin for the cash received |
| cash | cash | two `exchange` operations, one for each currency |
| a coin | a coin | a `sell` of the coin given up and a `buy` of the coin received, valued at one amount in one currency, so that the cash amounts cancel and the coin given up realizes its gain |

A trade whose base is cash and whose quote is a coin, such as `USDTTRY` with `TRY` not a cash
asset, is read the same way, the price derived from the amounts.

**Valuing two coins**: the quantity given up at its close, else the quantity received at its own.
The candidates are, in order, the coin given up against each price quote and then the coin received
against each price quote, so a coin is valued by its USDC pair before its USDT pair. The currency of
the operations is the quote of the candidate found. When no candidate has a close, the two legs are
`conversion` operations of one group (the record's `ref`) with no cash and no price, which carry
their cost over, and the record is noted.

**A commission** (`commission` in `commissionAsset`, when it is not zero):

- in a cash asset: a `fee` in that asset, naming the coin traded (the coin received when both are
  coins, none when both are cash);
- in the coin received: the quantity received is reduced by it, so the coin costs more per unit;
- in any other coin, BNB as a rule: a `sell` of it at the close of its bar, and a `fee` of the same
  value in the same currency naming the coin traded, so that the coin realizes its gain and the fee
  goes into the trade's cost or comes off its proceeds. The candidates are the coin against the
  currency of the trade first (the cash side of the trade, or the currency a trade between coins was
  valued in), then each price quote, so that the fee joins the trade's fee pool. A commission no
  close values leaves as a `security_out` of the coin, at its cost, and the record is noted.

A trade is always `executed`.

### Deposits and withdrawals

| Record | Operations |
|---|---|
| deposit of a coin | `security_in` of `amount`, with no price |
| deposit of a cash asset | `deposit` of `amount` |
| withdrawal of a coin | `security_out` of `amount`, and its network fee `transactionFee` paid like a commission in another coin: a `sell` of the coin at its close and a `fee` naming no instrument, or a `security_out` of the fee units when no close values them |
| withdrawal of a cash asset | `withdrawal` of `amount`, and a `fee` of `transactionFee` |

A transfer between Binance users (`transferType` 1) reads the same. A deposit is `executed` when
its status is 1 or 6, `canceled` when it is 2 or 7, and `pending` otherwise; a withdrawal is
`executed` at 6, `canceled` at 1, 3 and 5, and `pending` otherwise.

A coin deposited from elsewhere opens its lot at no cost, since the API says nothing of what it
cost, so its sale counts the whole proceeds as gain.

### Fiat money

| Record | Operations |
|---|---|
| fiat deposit | `deposit` of `indicatedAmount` and a `fee` of `totalFee`, in `fiatCurrency` |
| fiat withdrawal | `withdrawal` of `amount` and a `fee` of `totalFee` |
| purchase by card or another fiat channel | a `deposit` of `sourceAmount` of the fiat (`/deposit`), unless `paymentMethod` is `Cash Balance`, which is paid from the account's own fiat; a `buy` of `obtainAmount` of `cryptoCurrency` for `sourceAmount` less `totalFee` at `price`; and a `fee` of `totalFee` naming the coin |
| sale for fiat money | a `sell` of `sourceAmount` of the coin for `obtainAmount` plus `totalFee`, and the `fee`; the fiat stays in the account |

A coin that counts as cash, a stablecoin bought or sold with fiat money, takes two `exchange`
operations in place of the buy or the sale, and its fee names no instrument. A fiat order is
`executed` when its status is `Successful` or `Finished`, `canceled` when `Failed`, `Refunded` or
`Expired`, and `pending` otherwise; a fiat payment is `executed` when `Completed`, `canceled` when
`Failed` or `Refunded`, and `pending` otherwise. A Convert order is `executed` at `SUCCESS`,
`canceled` at `FAIL`, and `pending` otherwise.

### Broker types

An operation's `brokerType` is `Buy` or `Sell` for a trade and its legs, `Convert` for a Convert,
`Commission` for a commission and the sale that pays it, `Deposit`, `Withdrawal` and `Network fee`
for a crypto transfer, `Fiat deposit`, `Fiat withdrawal` and `Fiat fee` for fiat orders and the fee
of a purchase or sale, and `Buy crypto` and `Sell crypto` for the other operations of a purchase or a
sale with fiat money.

## Storage

The three storages keep what the sync records; see
[`storage-contracts.md`](storage-contracts.md#binancerecordstoragef_). `BinanceRecordStorage` also
stores each record's translations, which every reader reads through `AccountOperationStorage`.

## Limits and unconfirmed points

- **History** is whatever Binance's API returns. Trades, deposits and withdrawals from before about
  2022-09 are reported missing, and the trades of a pair that was delisted before the sync read it
  cannot be read. The reconciliation shows the gap as a difference between the recorded and the
  reported balance (see [Balances](#balances-and-the-reconciliation)).
- **Operations with no source in the API** are not recorded: card spending and cashback, referral
  rebates, token swaps, renames such as LUNA to LUNC, and the stablecoin conversions of 2022 and
  2023, among others.
- **Whether a withdrawal's `amount` includes its `transactionFee`** is not stated. The translation
  takes the amount to leave the account besides the fee.
- **Whether `fiat/orders` reaches back more than 90 days**, and its real window and weight, are not
  confirmed. The sync reads it in 89-day windows from `historyStart`.
- **Whether Convert's limit orders** appear in `convert/tradeFlow` is not confirmed. One that does is
  translated like any Convert order, by its status.
- **Where a card purchase lands**, in the spot or the funding wallet, and whether a deposit record
  accompanies it, are not documented. The translation pays the fiat in with a deposit unless
  `paymentMethod` is `Cash Balance`.
- **The spot and funding wallets count as one pool**: a transfer between them is not recorded. A coin
  held in Simple Earn or staking stays in the account until another source takes it out.
- **Whether `delist-schedule` needs a signature** is not confirmed. The client signs it, as its
  `timestamp` parameter suggests.
- **Lots carry no currency**, so a coin bought in one currency, such as EUR, and sold in another,
  such as USDT, has its cost counted as if it were in the currency of the sale (see
  [`tax.md`](tax.md#trades-between-coins-and-fees-paid-in-a-coin)).
- **A coin priced by another source**: a held coin joins the watchlist as a Binance ticker only while
  the router serves Binance (see
  [`market-data.md`](market-data.md#held-instruments-on-the-watchlist)); without it the coin stays
  off the watchlists, and its position is priced only where a price history is stored for the
  ticker.
- **Whether the funding wallet's call covers every balance** is not confirmed: Binance lists the
  business assets it supports (Pay, Card, gift cards, stock tokens) and does not say whether P2P
  balances show.

## System alerts

| Alert | When |
|---|---|
| `binance-unvalued:<account id>` | records that no price could value, once each in a process |
| `binance-reconciliation:<account id>` | the balances Binance reports differ from what the records leave, and the differences changed since the last alert |
| `binance-reconciled:<account id>` | the balances matched again after a `binance-reconciliation`, once |
| `process-crashed:binance-sync:<account id>` | the account's loop failed, a refused key included |

See [`alerts.md`](alerts.md#hooks-that-raise-system-alerts).
