# Interactive Brokers

Packages `st.btu.trading.pillar.models.ibkr` (identifiers, statement rows, errors),
`gateways.ibkr` (the Flex Web Service client, the statement parser, the instrument directory),
`services` (`IbkrRowTranslation`, `IbkrSyncService`, `IbkrSyncServiceImpl`, `IbkrSyncConfig`),
`processes` (`IbkrSyncProcess`, `IbkrSyncProcessImpl`) and `storages.IbkrStatementStorage`.

An Interactive Brokers (IB) account is read from the statements of an **Activity Flex Query** that
its owner saves in IB's Client Portal. The library fetches each statement through IB's Flex Web
Service, stores every row exactly as IB sent it, and translates the rows into `AccountOperation`s,
so that the valuation, the tax services and `/export` cover the account like an account at any
other broker. Prices come from the market data sources; IB's statements carry none.

For each window of dates the Flex Web Service returns the statement as XML, `IbkrStatementParser`
reads it into rows, `IbkrRowTranslation` translates each row into operations, and
`IbkrStatementStorage.record` stores every row together with its operations.

IB updates statements once a day, after its close of business (for securities at 8:20 PM US
Eastern time), so an IB account's figures lag by a day. Intraday reports (Trade Confirmation
queries) are not read.

## Setting up the account at IB

### The Activity Flex Query

Create an Activity Flex Query in the Client Portal, under Performance & Reports, Flex Queries, with
these sections:

| Section | Level of detail |
|---|---|
| Trades | Execution only |
| Cash Transactions | Detail |
| Transfers | - |
| Corporate Actions | Detail |
| Open Positions | Summary (not read yet) |
| Financial Instrument Information | - |

Select every field of each section. The library keeps all the attributes of a row as IB sent them,
so that a translation can be done again later without fetching the statement again, and it reads
the ones listed under ["Fields the library reads"](#fields-the-library-reads). A row that lacks an
attribute that the translation needs makes the sync fail with a message naming the row and the
attribute, so that a field left out of the query is found at once.

In the query's delivery and general configuration:

- Format: XML.
- Date format: `yyyyMMdd`.
- Time format: `HHmmss`.
- Date/time separator: `;` (semicolon).
- Include canceled trades: yes. A cancelled trade comes back as a row that cancels it, and the
  library needs that row to tell the trade is void.
- Display account alias in place of account ID: no. The library matches a statement to an account by
  its IB account id.
- Breakout by day: no.

The period of the query does not matter: the library asks for the dates it needs.

### The token

In the Client Portal, under Flex Web Service Configuration, enable the service and generate a
token. A token is valid for 6 hours unless a longer validity is chosen, up to a year; a year is
the practical choice for a sync that runs unattended. Generating a new token invalidates the old
one. The token can be limited to an IP address, such as the machine the sync runs on. It is a
secret: `FlexToken` prints a mask in `Show` and in logs, and the client never logs a request URL,
because the token travels in its query string.

One token reads every account of one IB login. Each account needs the id of its own query.

## Fields the library reads

| Section | Needed | Used when present |
|---|---|---|
| Trades | `transactionID`, `reportDate`, `assetCategory`, `currency`, `quantity`, `proceeds`, and `conid` of a stock trade | `levelOfDetail`, `dateTime`, `buySell`, `tradePrice`, `ibCommission`, `ibCommissionCurrency`, `taxes`, `transactionType`, `origTransactionID`, `notes`, `symbol` (a currency pair), `conid` (any other trade), and for the instrument `listingExchange` and `description` |
| Cash Transactions | `transactionID`, `reportDate`, `type`, `amount`, `currency` | `levelOfDetail`, `dateTime`, `conid` |
| Transfers | `transactionID`, `reportDate`, `currency`, and `assetCategory`, `quantity` and `conid` of a stock transfer | `dateTime` (or `date`), `type`, `direction`, `transferPrice`, `cashTransfer`, `conid` |
| Corporate Actions | `transactionID`, `reportDate`, `currency`, and `conid` of a leg that has a `quantity` | `levelOfDetail`, `dateTime`, `type`, `quantity`, `proceeds`, `conid` |
| Financial Instrument Information | `conid`, `symbol` | `assetCategory`, `listingExchange`, `description`, `isin`, `currency` |

A row is dated by its `dateTime`, else by its `date` or `tradeDate`, else by its `reportDate`, the
booking date, so select `dateTime` to date a trade by when it was made. The `levelOfDetail` of a row
is read when it is there; a trade or a detail row that names none is kept.

## The Flex Web Service client

`gateways.ibkr.FlexWebService[F]` is the port; `FlexWebServiceImpl` is its HTTP implementation.

```scala
trait FlexWebService[F[_]] {
  def statement(queryId: FlexQueryId, from: LocalDate, to: LocalDate): F[String]
}

final case class FlexWebServiceConfig(
  minSpacing: FiniteDuration = 6.seconds, retryDelay: FiniteDuration = 10.seconds,
  rateLimitDelay: FiniteDuration = 1.minute, maxAttempts: Int = 10)

object FlexWebServiceImpl {
  def create[I[_]: Sync, F[_]: Async](token: FlexToken, config: FlexWebServiceConfig,
                                      backend: SttpBackend[F, Any])(implicit logs: Logs[I, F]): I[FlexWebService[F]]
}
```

`statement` returns the XML of the statement for the report dates `from` to `to`, both included.
The service works in two steps, on `https://ndcdyn.interactivebrokers.com/AccountManagement/FlexWebService`,
each call with `v=3` and a `User-Agent` header:

1. `SendRequest?t=<token>&q=<query id>&fd=yyyyMMdd&td=yyyyMMdd` answers a reference code.
2. `GetStatement?t=<token>&q=<reference code>` answers the statement, or an error while IB is still
   generating it.

A statement covers at most 365 days of report dates, and IB keeps about four calendar years of
them. The older `gdcdyn` endpoints are not used.

### Errors

An answer that is not a statement carries a numeric `ErrorCode` and an `ErrorMessage`. The client
goes by the code and not by the `Status`.

| Codes | Meaning | The client |
|---|---|---|
| 1001, 1004-1009, 1019, 1021 | Statement could not be generated, is incomplete or still being generated, data is not ready, the server is busy, or the statement could not be retrieved | waits `retryDelay` and calls again |
| 1018 | Rate limit exceeded | waits `rateLimitDelay` and calls again |
| every other code, among them 1003, 1010-1017 and 1020 | The request is refused: 1012 is an expired token, 1015 an invalid one, 1014 an invalid query | fails at once |

A call can also get no answer from IB at all. The client treats it like an answer that asks to be
tried again, and waits `retryDelay` before it calls again, when:

- the HTTP client fails on the way, by a refused connection, a timeout or a dropped connection;
- the service answers with an HTTP status of 429 or 5xx.

Any other HTTP status that is not a success, such as 403 or 404, fails at once.

Each step is called at most `maxAttempts` times. The client fails with a `FlexError`:

- `Permanent(code, reason)` for a refusal that waiting does not change;
- `RetriesExhausted(code, reason, attempts)` when the attempts ran out on IB's answers, with the
  last code;
- `Unreachable(detail)` when the attempts ran out on calls that got no answer, with the last
  failure;
- `UnexpectedResponse(detail)` for a body that is not one of the documented answers, a failure
  status of the HTTP call that waiting does not change, or a body that is not XML.

The exception an HTTP client raises carries the request in its message, and so a URL with the
token. The client never passes it on: `Unreachable` is described by the cause of the failure alone
(its class and its message, with the values of the request's parameters taken out), and by the
class of the failure when it has no cause. The error carries no cause of its own.

IB also answers 1001 for a window of dates it finds too long, so `FlexError.StatementNotGenerated`
names the code, and the sync reads a shorter window when it gets `RetriesExhausted` with it.

### Pacing

IB allows one request per second and ten per minute per token. Whether the polls of `GetStatement`
count is not documented, so every call of a token goes through one limiter that keeps the calls at
least `minSpacing` apart, six seconds by default. The calls of statements read at the same time
queue up in the order they ask. Build one `FlexWebServiceImpl` per token and share it between the
accounts that read with it.

## The statement parser

`IbkrStatementParser.parse(xml)` reads the response of a query, `<FlexQueryResponse>` with a
`<FlexStatement>` per account, into `IbkrStatement`s: the IB account id, the dates, the rows and the
instruments. It uses the JDK's StAX with document type declarations and external entities
switched off. It keeps:

| Element | Kept when |
|---|---|
| `Trade` | its `levelOfDetail` is `EXECUTION` or it names none. A row with no `transactionID` fails the parse |
| `CashTransaction` | its `levelOfDetail` is `DETAIL` or it names none, and it has a `transactionID` |
| `Transfer` | it has a `transactionID`, without which the parse fails |
| `CorporateAction` | its `levelOfDetail` is `DETAIL` or it names none, and it has a `transactionID` |
| `SecurityInfo` | it has a `conid` and a `symbol` |

IB reports the fills of a trade again as `Order`, `SymbolSummary` and `AssetSummary` elements, and a
summary row of a cash transaction or a corporate action repeats a detail row under the account `-`
with no id; none of those is kept. Every kept row also needs a `reportDate`. Other elements and
attributes are ignored. Open Positions is not read.

An attribute value of `""`, `-`, `--` or `N/A` stands for no value. Dates are `yyyyMMdd`, a time is
`yyyyMMdd;HHmmss` or a date alone, which stands for the start of that day, and a number may contain
thousands separators.

A statement or a row that cannot be used fails with an `IbkrStatementError`: `Malformed` for text
that is not a query response, `MissingAttribute` and `InvalidAttribute` for a row that lacks an
attribute the library needs or holds a value of the wrong form (both name the section, the
transaction id and the attribute), and `AccountNotInStatement` for a response with no statement of
the configured account. The message of `AccountNotInStatement` names no IB account: a sync that
fails sends its message to the logs and the system alerts.

## The sync

`IbkrSyncService[F]` brings the rows of one IB account up to date; `IbkrSyncServiceImpl.create`
builds it for an `IbkrAccount(accountId, ibkrAccountId, queryId)`.

```scala
final case class IbkrSyncConfig(historyStart: Option[LocalDate] = None, rereadDays: Int = 30,
                                windowDays: Int = 30,
                                statementZone: ZoneId = ZoneId.of("America/New_York"))

trait IbkrSyncService[F[_]] {
  def sync: F[Unit]
  def rebuildTranslations: F[Unit]
}
```

### Windows

`sync` reads the statements window by window, oldest first, recording each before it reads the next:

- With no row stored for the account, the first window begins on `historyStart`. Without one it
  begins on 1 January four calendar years before the current year, the earliest date IB keeps for
  saved Flex Queries, and a `historyStart` before that date is read from there anyway. Set
  `historyStart` to the day the account opened to start there, since what IB answers for dates
  before an account existed is not documented. With rows stored, the first window begins
  `rereadDays` before the latest stored report date, so that a row IB reports late or corrects is
  stored again, and `historyStart` counts no more.
- The last window ends on the day before today in `statementZone`. IB books a day's rows after its
  close, so today's statement is not complete. That zone also decides which day is today.
- A window is at most `windowDays` days of report dates. When IB cannot generate one (the client
  fails with `RetriesExhausted` and the code 1001), the window is read again as two halves, and a
  half that fails in the same way is halved again, down to a single day. A single day that cannot
  be generated fails the sync.
- A window of any other failure is not halved: the sync stops with the error. The windows recorded
  before it stay recorded, and the next run begins again from the latest stored report date.

A first import that reaches back four years takes about sixty windows of two calls each, so at
least twelve minutes at the default pace.

### One window

1. Fetch the statement and parse it. A response holds the statements of every account the query
   covers; the sync takes the one of the configured `ibkrAccountId`, and fails with
   `IbkrStatementError.AccountNotInStatement` when there is none.
2. Attribute the rows to the account, keeping the last of rows that share a section and a
   transaction id.
3. Translate them with the cancellations (below). A row that cannot be translated fails the sync
   before anything of the window is recorded.
4. Record the rows with their translations, at most 500 rows per `IbkrStatementStorage.record`
   call.
5. Record the instruments the statement describes.

### Cancellations

A cancelling row (a trade of type `TradeCancel`, or noted `Ca`) names the trade it cancels by
`origTransactionID`, and arrives one to three days after it. `IbkrRowTranslation.translate` takes the
set of cancelled transaction ids: the operations of a cancelling row and of a trade it names are
`canceled`. The sync computes the set from the rows of the window, and loads the named trades that
the window does not repeat from the storage, so that a trade stored earlier is recorded again as
cancelled. A correction is a cancel plus a new trade, and the new trade counts.

### Rebuilding the translations

`rebuildTranslations` translates every stored row again, with the cancellations computed from all of
them, and records the result, which replaces each row's operations as a whole and so removes one a
row no longer produces. It leaves the stored rows as they are. The process runs it once
before the first sync of each account, which fills the shared operations on the first run and
brings them up to date after a change to the translation.

### The process

`IbkrSyncProcessImpl` runs one loop per `IbkrAccount` it is given, named `ibkr-sync:<account id>`:
first the rebuild, until it succeeds once, then a sync every `interval`. The loops run in a
`ProcessContext` that names the account. A failure, an expired token or an invalid query included,
is logged and raised as the system alert `process-crashed:ibkr-sync:<account id>` by
`ProcessLoop.restarting`, and the loop starts again after `interval`; the other accounts go on.
`resource` builds one `FlexWebServiceImpl` for the token and a sync service for each account.

```scala
object IbkrSyncProcessImpl {
  def resource[I[_]: Sync, F[_]: Async: GenUUID](
    accounts: List[IbkrAccount], token: FlexToken, config: IbkrSyncConfig, interval: FiniteDuration,
    statementStorage: IbkrStatementStorage[F], instrumentStorage: BrokerInstrumentStorage[F],
    systemAlerts: SystemAlerts[F], backend: SttpBackend[F, Any],
    flexConfig: FlexWebServiceConfig = FlexWebServiceConfig()
  )(implicit logs: Logs[I, F], WL: WithLocal[F, ProcessContext]): Resource[I, IbkrSyncProcessImpl[F]]
}
```

## Translation

`IbkrRowTranslation.translate(row, zone, canceled)` is pure. One row gives one or more operations.
The `ref` of the first is the row's own, `IbkrStatementRow.ref`: its section's persisted value and
its transaction id, such as `trade/123456`, which is unique within the account. The others add a
suffix, such as `trade/123456/commission`. Every `ref` of a row therefore starts with the row's
own, and the storage replaces a row's operations as a whole by that prefix. The instrument of an
operation is `InstrumentKey(Broker.Ibkr, <conid>)`. `occurredAt` is the `dateTime` of the row in
the statement zone, else its `date`, else its `tradeDate`, else its report date. The `state` is
`executed`, except for a cancelling row and a cancelled trade, whose operations are `canceled`.
`brokerType` is IB's own name, for display.

| Row | Operations |
|---|---|
| Trade, `assetCategory` `STK` | `buy` for a positive `quantity`, `sell` for a negative one, `other` for none: `ref` `trade/<id>`, quantity as given, `price` is `tradePrice`, `amount` is `proceeds` (negative on a buy) in `currency`; `brokerType` is `buySell` |
| Trade, `CASH`, a symbol such as `EUR.USD` whose second currency is the trade's | two `exchange` operations with `ref` `trade/<id>/EUR` and `trade/<id>/USD`: the first currency's amount is `quantity`, the second's is `proceeds`; no instrument, quantity 0; `brokerType` is `Currency exchange` |
| Trade, any other asset category (or a `CASH` row that does not read as a pair) | `other`, quantity 0, with `proceeds` as the cash and no position |
| Trade, its commission when `ibCommission` is not zero | `fee`, `ref` `trade/<id>/commission`, `amount` `ibCommission` in `ibCommissionCurrency` (the trade's currency when it names none), naming the instrument unless the trade is a conversion; `brokerType` is `Commission` |
| Trade, its `taxes` when not zero | `fee`, `ref` `trade/<id>/taxes`, in the trade's currency; `brokerType` is `Transaction tax` |
| Cash transaction `Deposits/Withdrawals` or `Deposits & Withdrawals` | `deposit` for a positive `amount`, `withdrawal` for a negative one |
| Cash transaction `Dividends`, `Payment In Lieu Of Dividends` | `dividend`; a negative amount is a reversal |
| Cash transaction `Withholding Tax` | `tax`; a positive amount is a refund |
| Cash transaction `Broker Interest Received`, `Broker Interest Paid` | `interest` |
| Cash transaction `Bond Interest Received`, `Bond Interest Paid` | `coupon` |
| Cash transaction `Other Fees`, `Advisor Fees`, `Commission Adjustments` | `fee` |
| Cash transaction of any other type | `other` |
| Transfer of stock (`assetCategory` `STK`) | `security_in` or `security_out` with the quantity signed by the `direction`, at `transferPrice` when there is one; `brokerType` is the `type` and the `direction`, such as `ACATS IN` |
| Transfer, its `cashTransfer` when it comes with stock | `other`, `ref` `transfer/<id>/cash`, carrying the cash amount |
| Transfer that moves no stock | `other` carrying the `cashTransfer` |
| Corporate action leg | `security_in` for a positive `quantity`, `security_out` for a negative one, `other` for none, with `proceeds` as the cash; `brokerType` is the action's `type` code |

The `ref` of a cash transaction is `cash_transaction/<id>`, that of a transfer `transfer/<id>` and
that of a corporate action leg `corporate_action/<id>`. A cash transaction keeps IB's sign of the
amount, names its instrument when the row has a `conid`, and has the row's `type` as its
`brokerType`. A commission or a tax is a `fee` of its own, naming the instrument, so that the tax
lots add it to the cost of the trade.

A corporate action is translated by its legs for now: the old instrument leaves with
`security_out` and the new one arrives with `security_in`, which keeps the positions right. The tax
lots see a `security_in` with no price as a lot at no cost, and see the `security_out` as a
transfer, which consumes lots without realizing a gain; the cost basis through a split or a merger
is therefore not carried over yet.

## Instruments

The Flex Web Service has no lookup call, so the sync records the instruments each statement
describes in `BrokerInstrumentStorage`, and `gateways.ibkr.IbkrInstrumentDirectory` answers
`Broker.Ibkr` keys from that storage alone (and `None` for a key of another broker).

For each `SecurityInfo` row the sync upserts a `BrokerInstrument`: the IB symbol as the ticker, the
listing exchange as the board (`classCode`), the description as the name (the symbol when there is
none), the asset category as the type (`STK`) and the currency. What a `SecurityInfo` lacks, and the
whole record of an instrument it does not list, is taken from the attributes of a trade of the
instrument in the statement. A currency conversion describes no instrument, and an instrument
for which no symbol, asset category or currency is known is left out. The records are written on
every window, so a ticker IB renames under the same contract id is updated.

## Storage

`storages.IbkrStatementStorage[F]` keeps the rows with their translations; see
[`storage-contracts.md`](storage-contracts.md#ibkrstatementstoragef_).

## Limits and unconfirmed points

- **The time zone of IB's times** is not stated in the statement. `statementZone` defaults to
  `America/New_York`; a wrong zone shifts the `occurredAt` of the operations, and so which day they
  count for.
- **Scope**: stocks and ETFs, which IB files as `STK`, and currency conversions. A trade of another
  asset category (options, futures, bonds) is kept as `other` with its cash, and has no position.
- **Which transfers carry cash**, and in which attribute, is not confirmed. A securities transfer
  is translated by its `direction`, or by the sign of its quantity when the row names none, but the
  cash of a transfer (`cashTransfer`) is `other`, since nothing in a row says what it is for. Such a
  cash amount is not counted as invested capital.
- **A commission in another currency than its trade's** is recorded as a fee in its own currency.
  The tax lots share a fee only among the trades in its currency, so such a commission is in no
  lot's cost and the estimate leaves it out.
- **Corporate actions** carry no cost basis through a split or a merger yet (see above).
- **History** is limited to what IB keeps available through Flex, 1 January four calendar years
  back. What IB answers for dates before the account existed is not documented, so `historyStart`
  lets an owner start on the day the account opened.
- **A failure of the HTTP call** is retried by the client only up to `maxAttempts`; the sync then
  fails, and its process starts again after its interval.
