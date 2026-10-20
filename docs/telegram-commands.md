# Telegram commands

Package `st.btu.trading.pillar.services.commands` (parsing, router, handlers) and
`processes.TgUpdatesProcessImpl` (the polling loop that wires them up).

## How a message becomes a reply

1. `TgUpdatesProcessImpl` long-polls `TgGateway.getUpdates`, sleeping 200ms between polls (10s
   after an uncaught loop failure, then it restarts).
2. For each update's message text, `CommandParser.parse` runs: text must start with `/`; the token
   up to the first whitespace or `@` is the command name (so both `/ping` and `/ping@MyBot` work),
   the rest is whitespace-split into `args`. Text **not** starting with `/` is not a command and is
   silently ignored - the bot never replies to plain chat messages. An unrecognized command name
   parses to `Command.Unknown` rather than failing.
3. `CommandHandleService` looks up a registered `CommandHandler` by `Command`; `Command.Unknown` or
   any command with no registered handler gets `"Unknown command. Try /help"`. If a handler raises,
   the error is logged and the chat gets `"Command failed: <exception message>"` - one bad command
   never kills the update loop.
4. Every reply is passed through `CommandReply.prepare` before sending: underscores become spaces
   (they show up in our own identifiers, e.g. `strategy_buy`) and `*`, `` ` ``, `[`, `]` are
   stripped (Telegram's legacy `parse_mode=Markdown` would otherwise silently reformat the message
   or reject it with "can't parse entities"), then the text is capped at 4000 characters.

## Built-in vs. registered handlers

`TgUpdatesProcessImpl.resource` always builds `PingHandler` and `InfoHandler` (they need a
`TcsFacade`, constructed internally); every other handler below is a plain `CommandHandler[F]` a
consumer constructs and passes in as `extraHandlers`:

```scala
def resource[I[_]: Sync, F[_]: Async: Throws](
  tgToken: tg.Token,
  brokerAccountId: tcs.BrokerAccountId,
  tcsToken: tcs.Token,
  backend: SttpBackend[F, Any],
  extraHandlers: List[CommandHandler[F]] = Nil
)(implicit logs: Logs[I, F], liftFI: Lift[F, I]): Resource[I, TgUpdatesProcessImpl[F]]
```

`CommandHandlers.standard(tgGateway, tcsFacade, extraHandlers)` assembles that list, and
`TgUpdatesProcessImpl.resource` uses it: `PingHandler`, `InfoHandler`, `extraHandlers`, then a
`HelpHandler` built from `(pingHandler :: infoHandler :: extraHandlers).map(_.usage) :+
HelpHandler.Usage` - so `/help`'s reply always lists exactly the handlers actually wired in, in
that order. The same function serves the [MCP server](mcp.md), which builds the handlers a second
time over a gateway that keeps the replies instead of sending them. A handler you don't pass as
`extraHandlers` simply doesn't exist for that deployment: its command falls through to
`"Unknown command. Try /help"`, and it's absent from `/help`'s output.

## `TgGateway.sendDocument`

`/export` (below) is the one command that replies with a file instead of a text message, via
`TgGateway.sendDocument(chatId, filename, content: Array[Byte], mimeType, caption)`:
`TgGatewayImpl` POSTs it to Telegram's `sendDocument` endpoint as `multipart/form-data` (sttp3's
`multipart`/`.fileName`/`.contentType`, not JSON) with fields `chat_id`, `document` (the bytes,
carrying `filename` and `mimeType`) and, only when provided, `caption`. Unlike `sendMessage`, there
is no `CommandReply.prepare`/Markdown concern - a document's caption is sent as plain text, and
`ExportHandler` doesn't set one.

## `TgGateway.sendPhoto`

`/backtest` (above) is the one command that sends an image alongside its text reply, via
`TgGateway.sendPhoto(chatId, filename, content: Array[Byte], caption)`: the same multipart-upload
shape as `sendDocument` above, except the field name is `photo` (not `document`), the content type
is always `image/png`, and Telegram renders it inline instead of offering it as a file attachment.
See [`backtesting.md`](backtesting.md#equity-curve-images-chartsequitycurveimage-tggatewaysendphoto)
for what the image contains and when it's sent.

## Inline keyboards and callback queries

`/menu` (below) is the one command that replies with an inline keyboard instead of (or alongside)
plain text, via `TgGateway.sendMessageWithKeyboard(chatId, text, rows: List[List[tg.InlineButton]])`:
`TgGatewayImpl` sends it the same way as `sendMessage` (a `sendMessage` request with
`parse_mode=Markdown`), with one extra field, `reply_markup`, a URL-encoded JSON
`{"inline_keyboard": [[{"text":..., "callback_data":...}, ...], ...]}` built from `rows` - one row
per outer list, left to right within a row. `tg.InlineButton(text, callbackData)` is the pair
behind each button; nothing enforces that `callbackData` looks like a command, but every handler
that builds a keyboard today sets it to the command text itself (e.g. `"/positions"`).

Tapping a button sends Telegram a `callback_query` update instead of a `message` one - `getUpdates`
requests both (`allowed_updates=["message","callback_query"]`), and `tg.Update` decodes either into
`tg.Update.callback_query: Option[CallbackQuery(id, from: Option[tg.User], message: Option[Message],
data: Option[String])]`. `TgUpdatesProcessImpl` routes it:

- A `callback_query` whose `data` starts with `/` is answered first
  (`TgGateway.answerCallbackQuery(callbackQuery.id)` - Telegram requires this or the tapped button
  stays in its "loading" state on the user's client) and then `data` is parsed and dispatched
  exactly like a typed message, for the chat of `callbackQuery.message` (the message the keyboard
  was attached to) - not whatever chat the bot might otherwise associate with the tap.
- A `callback_query` with no message (deleted, or too old for Telegram to retain), or whose `data`
  doesn't start with `/` (or is absent), is ignored - not answered, not routed.

## Commands

### `/ping`

Usage: `/ping - health check`

```
/ping
```
```
Working normally. Time is: 2026-09-28 14:32:07.
```

### `/info`

Usage: `/info - portfolio positions`

```
/info
```
```
Sberbank - 15000
Gazprom - 3200
```

One line per Tinkoff portfolio position, `"<name> - <balance rounded to an Int>"`.

### `/watchlist`

Usage: `/watchlist, /watchlist add SYM (yahoo|tinkoff) (note), or /watchlist rm SYM`

Single-account app: rows are written under a fixed `AccountId` the handler is constructed with, not
derived from the chat. The ticker is always upper-cased. A watchlist entry has no source of its
own: which source serves a ticker is a setting of the ticker (see [`/source`](#source)), and the
listing shows it.

```
/watchlist
```
```
AAPL (yahoo) - core position
SBER (tinkoff, board TQBR)
MSFT (yahoo) (disabled)
```
(`Watchlist is empty.` if there are none.) Each line names the source the ticker effectively uses:
the setting it has of its own, or the default source when it has none. For a Tinkoff setting the
board follows, `default board` when the setting names none.

```
/watchlist add aapl core position
```
```
Added AAPL (yahoo) to the watchlist.
```

The token right after the symbol is optional and sets the ticker's source: `yahoo` or `tinkoff`,
matched regardless of case. Without it the ticker's source setting is left as it is, and the reply
names the source the ticker effectively uses. A token that names neither source is treated as the
first word of the note instead of being dropped. `yahoo` replaces whatever setting the ticker had;
`tinkoff` puts a ticker that is not on Tinkoff on the default board and keeps the board of one that
already is.

```
/watchlist add sber tinkoff
```
```
Added SBER (tinkoff, default board) to the watchlist.
```

```
/watchlist add sber tinkoff MOEX blue chip
```
```
Added SBER (tinkoff, default board) to the watchlist.
```
(note is `MOEX blue chip`; `/watchlist` then lists `SBER (tinkoff, default board) - MOEX blue chip`.)

```
/watchlist rm AAPL
```
```
Removed AAPL from the watchlist.
```
(`AAPL was not on the watchlist.` if it wasn't there; `Usage: ...` if `add`/`rm` is missing its
symbol.) Removing a ticker leaves its source setting in place.

### `/source`

Usage: `/source, /source SYM, /source SYM yahoo, /source SYM tinkoff (BOARD), or /source SYM default`

Shows or changes the market data source of a ticker. The setting belongs to the ticker, not to an
account or a watchlist, so it applies everywhere the ticker is used: price-history sync, backtests,
alerts, valuation and dividends. A ticker with no setting of its own uses the default source the
router is configured with. A board is the broker's class code for a trading venue, such as `TQBR`
or `CETS`; only Tinkoff has boards.

```
/source
```
```
AAPL - yahoo
GAZP - tinkoff, default board
SBER - tinkoff, board TQBR
```
(One line per ticker that has a setting, ordered by ticker. `No ticker has a source of its own; every
ticker uses the default.` if there are none.)

```
/source sber
```
```
SBER uses tinkoff, board TQBR.
```
```
/source aapl
```
```
AAPL uses the default: yahoo.
```
(The second form is the reply for a ticker with no setting; it names the default source.)

```
/source sber yahoo
```
```
SBER now uses yahoo.
```

```
/source usd000utstom tinkoff cets
```
```
USD000UTSTOM now uses tinkoff, board CETS.
```

`/source SYM tinkoff` without a board puts the ticker on the Tinkoff adapter's default board
(`TcsMarketDataConfig.defaultClassCode`) and drops a board it had; the board is upper-cased.

```
/source sber default
```
```
SBER now uses the default source.
```
(`SBER already uses the default source.` if it had no setting.)

The ticker is upper-cased, and `yahoo`, `tinkoff` and `default` are matched regardless of case. Any
other form - an unknown word, a board after `yahoo`, more than one board, words after `default` -
replies `Usage: ...` and changes nothing. Not tied to an account, unlike `/watchlist`.

### `/strategies`

Usage: `/strategies - list registered strategies`

```
/strategies
```
```
ma-cross-50-200 - Moving Average Crossover (50/200) (fast=50, slow=200)
```
(`No strategies registered.` if the `StrategyRegistry` is empty.) One line per strategy:
`"<id> - <name> (<param>=<value>, ...)"`, parameters omitted entirely when there are none.

### `/backtest`

Usage: `/backtest strategyId SYM[,SYM2,...] (years, default 2) (params=key:value,key:value)`

```
/backtest ma-cross-50-200 AAPL 2
```
```
Backtest #7: ma-cross-50-200 on AAPL
Period: 2024-09-28 to 2026-09-28
Bars: 502
Parameters: fast=50, slow=200
Trades: 6
Total return: 18.42%
Annualized: 8.77%
Max drawdown: 12.05%
Sharpe: 0.91
Win rate: 66.67%
Buy and hold: 24.10%
```
```
[a photo: backtest-7-equity.png]
```

Whenever the run has at least 2 equity points, the text reply above is immediately followed by its
equity curve as a PNG image (`charts.EquityCurveImage.render`, sent via
[`TgGateway.sendPhoto`](backtesting.md#equity-curve-images-chartsequitycurveimage-tggatewaysendphoto)) -
a rendering or sending failure is logged and swallowed rather than failing the command, since the
text reply has already gone out by that point.

`years` defaults to 2 if omitted or not a positive integer; `from = today - years`. The handler
doesn't take a source argument itself: the stored bars are read from the source the symbol is set
to use, or the default source if it has no setting (`MarketDataSources.settingFor`; see
[`/source`](#source) and
[`market-data.md`](market-data.md#routing-multiple-sources-marketdatasources)). A backtest only
reads stored bars, so it needs no market data adapter for that source to be wired. Percentages and
the Sharpe ratio are rounded to 2 decimals. The `Parameters:` line is shown whenever the run's
`BacktestResult.parameters` is non-empty (true for every reference strategy) - `key=value`, sorted
by key, comma-separated; it's omitted only for a strategy with no parameters at all. Failure
replies:

```
/backtest bogus-id AAPL
```
```
Unknown strategy: bogus-id. Try /strategies for the list.
```

```
/backtest ma-cross-50-200 NEWSYM
```
```
Not enough price history for NEWSYM: need 201 bars, have 40. Try /watchlist add NEWSYM to start collecting data.
```

#### `params=` - ad hoc parameter overrides

A trailing `params=key:value,key:value` token (comma-separated `key:value` pairs, anywhere after
`strategyId`/`SYM`, in any order relative to `years`) runs one ad hoc backtest instead of the
registered strategy as-is: the registered strategy must implement
[`ParameterizedStrategy`](strategies.md#parameterizedstrategy) (both reference strategies do), its
`withParameters` is called with the parsed `Map[String, String]`, and the resulting strategy is run
via `BacktestService.runStrategy` - still persisted like any other backtest run
(`BacktestRunStorage.save`), just not tied to any `StrategyRegistry` entry beyond the one it was
built from.

```
/backtest ma-cross-50-200 AAPL params=fast:10,slow:40
```
```
Backtest #9: ma-cross-10-40 on AAPL
Period: 2024-09-28 to 2026-09-28
Bars: 502
Parameters: fast=10, slow=40
Trades: 11
...
```

Note the header's strategy id reflects the *overridden* parameters (`ma-cross-10-40`, not
`ma-cross-50-200`) - `ParameterizedStrategy.withParameters` returns a genuinely new strategy
instance, and its `id`/`parameters` are computed from the new constructor arguments like any other
instance. Failure replies specific to this token:

| Situation | Reply |
|---|---|
| The `params=` token itself is malformed (a pair without `:`, or an empty key/value) | `Usage: ...` |
| `strategyId` isn't registered | `Unknown strategy: <id>. Try /strategies for the list.` (same message the plain path uses) |
| The registered strategy doesn't implement `ParameterizedStrategy` | `Strategy <id> does not support parameters.` |
| `withParameters` returns a `Left` (unknown key, unparsable value, or a violated invariant like `fast >= slow`) | `Invalid parameters: <that Left's own message>` |

#### Multi-ticker backtests

A comma in the ticker argument (no space around it) runs a portfolio backtest
(`BacktestService.runPortfolio` - see
[`backtesting.md`](backtesting.md#multi-ticker-backtests-backtestenginerunportfolio)) instead of a
single-symbol one: one shared cash balance across every ticker, a combined equity curve, and
`params=` is **not** supported together with it (`params=` is a single-strategy/single-ticker ad
hoc rebuild via `ParameterizedStrategy.withParameters`, which has no portfolio counterpart).

```
/backtest ma-cross-50-200 AAPL,MSFT,TSLA 2
```
```
Backtest #15: ma-cross-50-200 on AAPL,MSFT,TSLA
Period: 2024-09-28 to 2026-09-28
Bars: 502
Parameters: fast=50, slow=200
Trades: 14
Total return: 11.08%
Annualized: 5.32%
Max drawdown: 9.87%
Sharpe: 0.74
Win rate: 57.14%
Buy and hold: 15.63%
```
```
[a photo: backtest-15-equity.png]
```

Each ticker's bars are read from the source that ticker is set to use (see [`/source`](#source)),
so one portfolio can mix sources; the reply's header and the persisted run's ticker both show the
full ticker list joined by `,`, in the order given. `Buy and hold` is the equal-weight definition
(split the initial cash evenly, one share lot per ticker bought at its own first open), not any one
ticker's own buy-and-hold. `Trades` still counts every individual fill across every ticker
(`metrics.tradeCount`), same meaning as the single-ticker reply.

```
/backtest ma-cross-50-200 AAPL,MSFT params=fast:10,slow:40
```
```
params= is not supported with multiple tickers.
```

### `/sweep`

Usage: `/sweep strategyId SYM [years=2] key=from:to:step [key=from:to:step ...] - parameter sweep, top 5 by Sharpe`

Runs a whole grid of parameter combinations for one `ParameterizedStrategy` against the same stored
bars (those of the source the symbol is set to use, read without needing an adapter for it, as in
`/backtest`) and replies with the top 5 by Sharpe ratio - see
[`backtesting.md`](backtesting.md#parameter-sweeps-backtestsweep) for `BacktestSweep`/`Grid.expand`
themselves. Unlike `/backtest`, **a sweep's points are never persisted** - nothing is written to
`BacktestRunStorage`, and there is no run id in the reply.

```
/sweep ma-cross-50-200 AAPL fast=10:50:10 slow=100:200:50
```
```
Grid: 15 combination(s)
1. fast=40, slow=150 - return 22.10%, Sharpe 1.12, max drawdown 9.40%, trades 5
2. fast=10, slow=100 - return 18.42%, Sharpe 0.91, max drawdown 12.05%, trades 6
3. fast=20, slow=150 - return 15.00%, Sharpe 0.80, max drawdown 10.00%, trades 4
4. fast=40, slow=100 - return 12.30%, Sharpe 0.65, max drawdown 14.20%, trades 7
5. fast=10, slow=200 - return 9.10%, Sharpe 0.40, max drawdown 11.00%, trades 3
```

At least one `key=from:to:step` grid axis token is required (each one a `Grid.expand` range: `from`,
`to` and `step` are inclusive/exclusive exactly as documented there); `years` (default 2, same
meaning as `/backtest`'s) is an optional `years=N` token among them, recognized by its key name and
not counted as a grid axis. `strategyId` is looked up the same way `/backtest`'s `params=` path does
- unregistered or non-`ParameterizedStrategy` get the same two replies as above. An invalid grid
(a non-positive `step`, or more than 100 combinations) replies `Invalid grid: <reason>`. Each
result line is `"<rank>. <params, sorted by key> - return <pct>, Sharpe <scaled>, max drawdown
<pct>, trades <tradeCount>"`; `No successful sweep points.` in place of the list when every point in
the grid failed (e.g. not enough stored bars for any combination's `warmupBars`) - the `Grid: N
combination(s)` line is still shown in that case, since the grid itself was built successfully.

```
/sweep ma-cross-50-200 AAPL fast=1:20:1 slow=1:10:1
```
```
Invalid grid: Grid too large: 200 combinations (max 100)
```

### `/alerts`

Usage: `/alerts (n, default 10) - recent alert history`

```
/alerts 5
```
```
2026-09-28 09:15:03 strategy_buy AAPL: delivered
2026-09-28 08:10:00 pre_target AAPL: suppressed (cooldown)
```
(`No alerts recorded yet.` if `AlertHistoryStorage` is empty.) Most recent first, both delivered
and suppressed records, format `"<deliveredAt> <kind.value> <ticker|-> : delivered"` or `"...:
suppressed (<reason>)"`.

### `/alert`

Usage: `/alert price SYM >|< threshold [proximity=1%|2.5|0.5atr14] [cooldown=15m] [max=N], /alert
rules, or /alert delete id`

Creates, lists and deletes this account's `PriceCrossed` rules (`AlertRuleStorage.insertPriceRule`/
`byAccount`/`delete` - see [`alerts.md`](alerts.md#rule-types)), the same rule type `AlertProcess`
evaluates on every poll. Single-account app, same as `/watchlist`: the handler is constructed with a
fixed `AccountId` and a fixed list of channels every rule it creates gets - there is no per-rule
channel syntax yet.

```
/alert price aapl > 300 proximity=1% cooldown=15m max=5
```
```
Created price rule #12: AAPL above 300.00 (proximity 1.00%, cooldown 15 minutes, max 5/day)
```

The ticker is upper-cased like the other handlers. The comparison token is `>` or `<` (also
`above`/`below`, case-insensitive), followed by the threshold. Trailing options, in any order and
all optional:

| Option | Example | Parses to |
|---|---|---|
| `proximity=` | `1%` | `Proximity.Percent(1)` |
| `proximity=` | `2.5` | `Proximity.Absolute(2.5)` |
| `proximity=` | `0.5atr14` | `Proximity.Atr(0.5, 14)` |
| `cooldown=` | `15m`, `2h`, `1d` | `DeliveryPolicy.cooldown` (default 1 hour when omitted) |
| `max=` | `5` | `DeliveryPolicy.maxPerDay` (unlimited when omitted) |

`hysteresisPct` and quiet hours are not settable from this command; a rule created this way always
gets `DeliveryPolicy`'s default `hysteresisPct` and no quiet hours. Any parsing failure (an
unrecognized comparison token, a non-numeric threshold, a malformed or unrecognized `proximity=`/
`cooldown=` value, a non-positive or non-numeric `max=`, or an unknown option key) replies with the
one-line `Usage: ...` string above instead of a specific error.

```
/alert rules
```
```
#12 AAPL above 300.00 (proximity 1.00%, cooldown 15 minutes, max 5/day)
#13 MSFT below 150.00 (cooldown 1 hour)
```
(`No alert rules.` if the account has none.) One line per rule: `"#<id> <SYM> above|below
<threshold> (<options>)"` - `cooldown` is always shown (the effective value, default or custom),
`proximity`/`max` only when the rule has them.

```
/alert delete 12
```
```
Deleted alert rule #12.
```
(`Alert rule #12 not found.` if there is no such rule for this account - including a rule id that
belongs to a different account, which `AlertRuleStorage.delete` treats the same as not found.)

### `/mute`

Usage: `/mute (minutes, default 60) - pause alert delivery`

```
/mute 30
```
```
Alerts muted for 30 minute(s).
```

Calls `AlertDispatcher.mute(now + minutes)` - suppresses **every** alert, from any rule, until
then; a suppressed alert during mute is still recorded with `suppressedBy = "mute"`.

### `/unmute`

Usage: `/unmute - resume alert delivery`

```
/unmute
```
```
Alerts unmuted.
```

### `/dividends`

Usage: `/dividends - upcoming ex-dates and trailing 12-month totals for the watchlist`

```
/dividends
```
```
Upcoming ex-dates:
AAPL 2026-10-15 0.25 USD

Trailing 12 months:
AAPL: 1.00
MSFT: 3.00
```

Two sections, always in this order: upcoming ex-dividend dates (`exDate >= today`) across the
account's watchlist tickers, each `"<ticker> <exDate> <amount> <currency>"` (amount rounded to 2
decimals, `currency` omitted when the event has none); then trailing-12-month totals per ticker
that had any dividend in that window, each `"<ticker>: <total>"`. Either section reads
`No upcoming ex-dividend dates.` / `No dividends in the trailing 12 months.` when empty - including
when the account's watchlist itself is empty. Each ticker's events are read from the source it is
set to use (see [`/source`](#source)), so dividends another source stored for the ticker are not
shown or counted. Single-account app, same as `/watchlist`: the handler is constructed with a fixed
`AccountId`, not derived from the chat.

### `/performance`

Usage: `/performance [1m|3m|6m|1y|all] (default 1y) - portfolio performance`

```
/performance 3m
```
```
Period: 2026-06-30 to 2026-09-28
Total return: 4.21%
TWR: 4.05%
MWR: 8.92%
Volatility: 12.30%
Sharpe: 0.85
Max drawdown: 3.10%
```

Reads the *stored* valuation series (`PortfolioValuationStorage.series`, written by
`AnalyticsProcess` - see [`analytics.md`](analytics.md#portfolio-valuation)), not live data; `from`
is `today` minus the period token (`1m`/`3m`/`6m`/`1y`, default `1y`, or `today - 50y` for `all`,
a bound no real account history predates). `MWR: n/a` when `Performance.moneyWeighted` has fewer
than two points or its solver finds no root in range (see [`analytics.md`](analytics.md#moneyweighted--xirr)).
`No valuation history yet.` when the account has no stored points in the window. Single-account
app, same as `/watchlist`: the handler is constructed with a fixed `AccountId`, not derived from
the chat.

### `/portfolio`

Usage: `/portfolio - latest portfolio valuation`

```
/portfolio
```
```
Date: 2026-09-28
Value: 125430.50
Invested: 100000.00
Profit: 25430.50
History: 187 day(s)
```

The latest stored point (`PortfolioValuationStorage.latest`): `value`, the cumulative `invested`,
`profit = value - invested`, and how many days of history precede it (a `series` lookback over the
same 50-year bound `/performance all` uses - there is no dedicated count method).
`No valuation history yet.` when nothing is stored. Single-account app, same as `/performance`.

### `/positions`

Usage: `/positions - current positions by value`

```
/positions
```
```
SBER 100 @ 285.50 = 28550.00 (62.18%)
GAZP 50 (unpriced)
Cash: 17366.00
Total: 45916.00
```

One line per open position (a fully closed position - net quantity 0 - is omitted), computed live
from `PortfolioValuationService.positionsAsOf(accountId, today)` - see
[`analytics.md`](analytics.md#positions-and-allocation), not read from storage. Priced
positions come first, largest `value` first; unpriced ones (no known close, no FX rate, or a FIGI
the `InstrumentDirectory` does not know) follow, marked `(unpriced)`, ordered by ticker (or FIGI
when there's no ticker). `price` is the instrument's own last known close in its own currency,
unconverted; `value` and the trailing `Cash`/`Total` lines are all in the account's base currency.
`No positions.` in place of the list when the snapshot has none. Single-account app, same as
`/performance`: the handler is constructed with a fixed `AccountId`, not derived from the chat, and
uses today's date (UTC).

### `/allocation`

Usage: `/allocation - allocation by instrument type, currency and exchange`

```
/allocation
```
```
By instrument type:
share: 85.30%
bond: 14.70%

By currency:
RUB: 70.00%
USD: 30.00%

By exchange:
MOEX: 70.00%
NASDAQ: 30.00%

Cash: 12.50%
```

Three breakdowns of `Allocation.of(positionsAsOf(accountId, today))` - see
[`analytics.md`](analytics.md#positions-and-allocation) - each largest weight first. Only priced
positions appear in them, so a FIGI the `InstrumentDirectory` does not know (an unpriced position)
is in none of the three. A priced position whose ticker has no stored instrument metadata from its
configured source falls under `unknown` in `By exchange` only, since its type and currency fall back
to the broker's record. An empty breakdown prints `(none)`. `Cash` is always its own trailing line,
never folded into `By currency`. When any position is unpriced, one more trailing line reads
`Unpriced positions (weight unknown): N`. Single-account app, same as `/positions`.

### `/drift`

Usage: `/drift - allocation targets vs. actual, with drift`

```
/drift
```
```
instrument_type/share: actual 85.30%, target 80.00%, drift 5.30%
instrument_type/bond: actual 14.70%, target 20.00%, drift -5.30% [BREACHED]
```

One line per enabled `AllocationTarget` (`AllocationTargetStorage.byAccount`), in the storage's own
order (dimension then key): `"<dimension>/<key>: actual X%, target Y%, drift Z%"`, with a trailing
`[BREACHED]` marker when `|drift| > driftThresholdPct` - see
[`analytics.md`](analytics.md#allocation-targets-drift-and-rebalancing) for the exact actual/
drift rules (shared with the `AnalyticsProcessImpl` alert hook - see
[`alerts.md`](alerts.md#allocation-drift-alerts)). `drift` is signed: positive is overweight,
negative underweight. `No allocation targets configured.` when the account has none.
`CommandReply.prepare` turns the dimension's own `_` into a space and strips `[]` from the final
Telegram message, same as every other reply. Single-account app, same as `/allocation`: the handler
is constructed with a fixed `AccountId`, not derived from the chat, and uses today's date (UTC).

### `/rebalance`

Usage: `/rebalance [cash=<amount>] - rebalancing suggestions`

```
/rebalance
```
```
Sell instrument_type/share: 530.00
Buy instrument_type/bond: 530.00
```

```
/rebalance cash=1000
```
```
Buy instrument_type/bond: 1000.00
```

Without `cash=`, rebalances the *current* total: sells every overweight target down to its target
amount and buys every underweight one up to it. With `cash=<amount>`, spreads that deposit across
the underweight targets only, proportionally to their shortfall against the post-deposit total -
**never selling** - see
[`analytics.md`](analytics.md#allocation-targets-drift-and-rebalancing) for the exact formula.
`Already within thresholds.` when there is nothing to suggest; `Usage: ...` when the argument isn't
`cash=<a parseable number>`. Single-account app, same as `/drift`.

### `/paper`

Usage: `/paper - virtual paper-trading account`

```
/paper
```
```
Cash: 9382.15
AAPL 10 @ 150.00 (avg 140.00) = 1500.00, P&L 100.00
MSFT 5 (avg 300.00, unpriced)
Equity: 10882.15

Recent orders:
filled buy 10 @ 140.00 (fast crossed above slow)
rejected sell 3 @ - (Sell without a position (fast crossed below slow))
```

Virtual account fed by `StrategyBinding`s with `mode = paper` (see
[`paper-trading.md`](paper-trading.md) and [`alerts.md`](alerts.md#paper-trading)) - `AccountId` is
a fixed constructor argument, same as every other single-account handler. `Cash` is the stored
`PaperAccount.cash`; each `PaperPosition` is priced with the ticker's own routed latest quote
(`MarketDataSources.forTicker`, so it needs the ticker's adapter, unlike `/backtest` and
`/positions`, which read only stored data) - `price` is shown unconverted in the position's own
currency, `value = quantity * price` and `P&L = (price - averageCost) * quantity`; a position with
no quote available right now is marked `(unpriced)` instead and excluded from `Equity`.
`Equity = cash + sum(priced positions' value)`.
`No open positions.` / `No orders yet.` in place of either section when empty; `No paper account
configured.` instead of the whole reply when `PaperAccount` doesn't exist for this account (the
consumer provisions one, and its starting cash, outside the library). The last 5 orders
(`PaperStorage.recentOrders`, most recent first) print as `"<status> <side> <quantity> @
<fillPrice|-> (<reason>)"`.

### `/news`

Usage: `/news SYM (n, default 5, max 10) - recent news for a ticker`

```
/news aapl
```
```
Apple reports record quarterly revenue - Reuters (2026-09-28 13:05:00) https://example.com/a
Apple unveils new product line - Bloomberg (2026-09-27 09:00:00) https://example.com/b
```

One line per article, in the order `NewsSource` returns them (Yahoo's own search relevance ranking,
not re-sorted): `"<title> - <publisher> (<publishedAt>) <link>"`, with `publisher`/`publishedAt`
shown as `?` when the source didn't report one. The ticker is upper-cased like the other handlers.
`n` is a trailing positional count, default 5, silently capped at 10 (not rejected) when the caller
asks for more; a non-numeric or non-positive `n` falls back to the default rather than erroring,
the same rule `/alerts`'/`/backtest`'s own trailing count arguments use. `No news found.` when the
source returns nothing. `Usage: ...` only when the ticker itself is missing.

```
/news
```
```
Usage: /news SYM (n, default 5, max 10) - recent news for a ticker
```

### `/screen`

Usage: `/screen gainers|losers|active (n, default 10, max 25) - predefined screener`

```
/screen gainers
```
```
AAPL Apple Inc. 150.50 2.34%
TSLA Tesla Inc. 245.10 5.12%
```

One line per result, in the order the Yahoo screen itself returns (not re-sorted):
`"<ticker> <name> <price> <changePct>"`, with `name`/`price`/`changePct` shown as `?` when the
screen didn't report one. `changePct` is Yahoo's own percentage figure printed directly (`2.34%`
means +2.34%, not a 0..1 fraction) - see
[`market-data.md`](market-data.md#screener). The screen name (`gainers`, `losers` or `active`) is
matched case-insensitively; `n` is a trailing positional count, default 10, silently capped at 25,
the same rule `/news`'s `n` uses. `No results.` when the screen returns nothing. `Usage: ...` when
the screen name is missing or unrecognized.

```
/screen bogus
```
```
Usage: /screen gainers|losers|active (n, default 10, max 25) - predefined screener
```

### `/tax`

Usage: `/tax [year] (default: current year) - estimated annual tax report`

```
/tax 2025
```
```
Tax estimate 2025 (RUB) - not tax advice
Realized gains: 54200.00
Long-term exempt: 12000.00
Taxable gains: 42200.00
Dividend income: 3100.00
Coupon income: 800.00
Taxes withheld: 403.00
Estimated tax: 5486.00
```

Built from `TaxService.annualReport` (FIFO lots via `TaxLots.fifo`, the estimate via
`TaxReport.annual` - see [`tax.md`](tax.md)). `year` defaults to the current year (UTC) when
omitted or not an integer. A trailing line, `"N amount(s) could not be converted to RUB and were
excluded."`, appears only when `unconverted > 0`. Single-account app, same as every other handler:
the handler is constructed with a fixed `AccountId`, not derived from the chat.

### `/lots`

Usage: `/lots SYM - open FIFO tax lots for a ticker`

```
/lots AAPL
```
```
10 @ 140.00 acquired 2023-03-01 (945 day(s), long-term)
5 @ 165.00 acquired 2025-06-10 (115 day(s))
```

One line per open lot for that ticker (`TaxService.openLots`, oldest first): quantity, unit cost,
acquisition date, holding days as of today (UTC) and a trailing `, long-term` when `holdingDays >=
TaxConfig.longTermYears * 365`. `No open lots for SYM.` when there are none (including an unknown
ticker). `Usage: ...` when the ticker is missing.

### `/export`

Usage: `/export operations|valuations|dividends [year] - download a CSV file`

```
/export operations 2025
```
```
[a file: operations-2025.csv]
```

Builds a CSV ([`Csv.write`](tax.md), a tiny header-plus-rows writer with RFC 4180-style quoting)
and sends it with [`TgGateway.sendDocument`](#tggatewaysenddocument) as `<kind>-<year>.csv`:

| `kind` | Rows | Source |
|---|---|---|
| `operations` | every executed-or-not operation of the account in `year`, oldest first | `TcsPortfolioOperationStorage.operationsByDtRange` |
| `valuations` | the stored daily valuation series for `year` | `PortfolioValuationStorage.series` |
| `dividends` | dividend events of `year`, across the account's watchlist tickers, by ex-date | `DividendStorage.byTicker` per `WatchlistStorage.byAccount` ticker, from the source the ticker is set to use |

`year` defaults to the current year (UTC) when omitted or not an integer; the `kind` token is
matched case-insensitively. When there is nothing to export, the reply is a short text instead of a
file: `"No <kind> to export for <year>."`. `Usage: ...` when `kind` is missing or unrecognized.
Single-account app, same as every other handler.

### `/analyze`

Usage: `/analyze SYM - price, returns, technicals and dividends for a ticker`

```
/analyze AAPL
```
```
AAPL
Price: 150.20 USD
As of: 2026-09-28 20:00:00
Type: EQUITY
Exchange: NASDAQ
1m return: 2.50%
3m return: 5.10%
1y return: 18.40%
SMA50: 145.30
Above SMA50: yes
SMA200: 130.00
Above SMA200: yes
RSI14: 62.10
ATR14: 1.85%
52w high: 160.00
Dist. to 52w high: -6.09%
52w low: 120.00
Dist. to 52w low: 25.21%
Dividends (12m): 1.00
Dividend yield: 0.67%
```

Built from `AnalysisService.analyze` (see [`market-data.md`](market-data.md#stock-analysis) for
every field's exact definition) and printed by the shared `AnalysisFormat.single` helper: one line
per field, in the order above, `n/a` wherever the field is empty. The ticker is upper-cased like
the other handlers. `Usage: ...` when the ticker is missing. A ticker the routed `MarketDataSource`
doesn't recognize at all is not an error - every field prints `n/a` (`Dividends (12m): 0.00`
instead, since that field is never empty), the same reply a real-but-dataless ticker would get.

### `/compare`

Usage: `/compare SYM1 SYM2 - compare two tickers side by side`

```
/compare AAPL MSFT
```
```
AAPL vs MSFT
Price: 150.20 USD | 300.00 USD
As of: 2026-09-28 20:00:00 | 2026-09-28 20:00:00
...
Dividend yield: 0.67% | 1.20%
```

Same fields as `/analyze`, same order, via the shared `AnalysisFormat.sideBySide` helper: each line
is `"<label>: <value for SYM1> | <value for SYM2>"`, `n/a` on either side independently. Both
tickers are analyzed even if one of them isn't found (that side just prints `n/a`/`0.00`
throughout). `Usage: ...` when a second ticker is missing.

### `/menu`

Usage: `/menu - inline keyboard for the main read-only commands`

```
/menu
```
```
[inline keyboard]
/portfolio   /positions
/performance /alerts
/watchlist   /dividends
/paper
```

Sends `MenuHandler.Text` ("Choose a command:") with an inline keyboard
(`TgGateway.sendMessageWithKeyboard` - see
["Inline keyboards and callback queries"](#inline-keyboards-and-callback-queries) above), two
buttons per row, in the order `MenuHandler.Commands`: `/portfolio`, `/positions`, `/performance`,
`/alerts`, `/watchlist`, `/dividends`, `/paper`. Each button's `callbackData` is its own command
text verbatim, so tapping one is routed exactly like typing it. No text reply is sent separately -
`handle` only calls `sendMessageWithKeyboard`.

### `/help`

Usage: `/help - list available commands`

```
/help
```
```
/ping - health check
/info - portfolio positions
/watchlist, /watchlist add SYM (yahoo|tinkoff) (note), or /watchlist rm SYM
/source, /source SYM, /source SYM yahoo, /source SYM tinkoff (BOARD), or /source SYM default
/strategies - list registered strategies
/backtest strategyId SYM[,SYM2,...] (years, default 2) (params=key:value,key:value)
/sweep strategyId SYM [years=2] key=from:to:step [key=from:to:step ...] - parameter sweep, top 5 by Sharpe
/alerts (n, default 10) - recent alert history
/alert price SYM >|< threshold [proximity=1%|2.5|0.5atr14] [cooldown=15m] [max=N], /alert rules, or /alert delete id
/mute (minutes, default 60) - pause alert delivery
/unmute - resume alert delivery
/dividends - upcoming ex-dates and trailing 12-month totals for the watchlist
/performance [1m|3m|6m|1y|all] (default 1y) - portfolio performance
/portfolio - latest portfolio valuation
/positions - current positions by value
/allocation - allocation by instrument type, currency and exchange
/drift - allocation targets vs. actual, with drift
/rebalance [cash=<amount>] - rebalancing suggestions
/paper - virtual paper-trading account
/news SYM (n, default 5, max 10) - recent news for a ticker
/screen gainers|losers|active (n, default 10, max 25) - predefined screener
/tax [year] (default: current year) - estimated annual tax report
/lots SYM - open FIFO tax lots for a ticker
/export operations|valuations|dividends [year] - download a CSV file
/analyze SYM - price, returns, technicals and dividends for a ticker
/compare SYM1 SYM2 - compare two tickers side by side
/menu - inline keyboard for the main read-only commands
/help - list available commands
```
(exact contents/order depend on which handlers were passed as `extraHandlers` - see above.)

### Unrecognized input

```
/frobnicate
```
```
Unknown command. Try /help
```

Plain text (no leading `/`) gets no reply at all.
