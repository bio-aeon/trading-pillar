# Tax lots and reports

Package `st.btu.trading.pillar.tax`. Turns an account's executed `AccountOperation`s, its
operations in terms shared by every broker, into FIFO cost-basis lots that keep the currency of
their cost, carried through splits, mergers, spin-offs and stock dividends, and a per-year tax
estimate that converts each cost on the day of the sale and counts the variation margin of futures
as a result of its own.

**This is an estimate for personal reference, not tax advice.** It knows about exactly the rules
listed below - progressive rate, an optional long-term exemption, broker-withheld tax, the
variation margin of futures - and nothing else (no loss carry-forward across years, no IIS
deductions, no instrument-specific rules). The FX conversion it uses is a documented approximation,
not the official rate for any given instant - see ["FX conversion"](#fx-conversion) below.

## `TaxLots.fifo` - building the ledger

```scala
final case class Lot(instrument: InstrumentKey, quantity: BigDecimal, unitCost: Map[Currency, BigDecimal],
                     acquiredOn: LocalDate)
final case class RealizedGain(instrument: InstrumentKey, ticker: Option[Ticker], sellDate: LocalDate,
                              quantity: BigDecimal, currency: Currency, proceeds: BigDecimal,
                              costBasis: Map[Currency, BigDecimal], holdingDays: Long)
final case class LotLedger(open: Map[InstrumentKey, List[Lot]], realized: List[RealizedGain],
                           unmatched: Map[InstrumentKey, BigDecimal])

object TaxLots {
  def fifo(operations: List[AccountOperation], tickerOf: InstrumentKey => Option[Ticker],
           allocations: List[CorporateActionAllocation] = Nil,
           transferCosts: List[TransferCost] = Nil,
           futures: Set[InstrumentKey] = Set.empty): LotLedger
}
```

Pure, no `F[_]`. Only operations whose `state` is `executed` and that name an instrument are
considered; everything else in `operations` is ignored, such as the legs of a currency exchange
(`exchange`), which name none. An `InstrumentKey` is the broker and the id
that broker gives the instrument, so lots of instruments at different brokers never mix. `tickerOf`
is a plain function so `TaxLots` itself never touches an `InstrumentDirectory` - the caller
(`TaxServiceImpl`) resolves every instrument it sees to a ticker once, up front. `allocations`,
`transferCosts` and `futures` are plain data for the same reason: see ["Spin-offs"](#spin-offs),
["Transfers in with an entered cost"](#transfers-in-with-an-entered-cost) and
["Futures"](#futures). Quantities are decimals, since an instrument can trade in fractions of a
unit.

The ledger is one chronological fold over the operations of every instrument of the account, since
a conversion or a spin-off moves lots from one instrument to another. Its state is the open lots of
each instrument, the realized gains and the unmatched quantities. An operation is applied by its
kind, and the operations of one group of a conversion or a spin-off are applied together (see
["Corporate actions"](#corporate-actions)). The fee pools below stay per instrument.

### Lots in the currencies of their cost

A lot keeps its cost in the currency it was paid in. `Lot.unitCost` is a map from currency to the
cost of one unit in it, with no zero amount, so a lot that cost nothing has an empty map. A coin
bought for 2000 euros, 0.1 of it, is a lot of `Map(EUR -> 20000)`, and stays one when it is sold for
tether. The ledger has no exchange rates, so it does not add costs of different currencies: a sale
reports the cost of the lots it consumed in each of their currencies, and `TaxReport` converts each
amount on the day of the sale (see ["How a gain is converted"](#how-a-gain-is-converted)).

A lot bought and sold in one currency has the gain `proceeds` less `costBasis` in that currency,
which `TaxReport` converts as one amount. A lot's cost has several currencies only through a fee
paid in another currency than the purchase, or a conversion that charged cash in another currency;
a sale's cost basis has several when it consumes lots paid in different currencies.

### Opening a lot

| Operation kind | Lot's `unitCost` |
|---|---|
| `buy` | `\|amount\| / quantity` in the operation's currency, **plus** that day's pro-rated fee shares for this instrument, each in the currency of its fee (see below) |
| `security_in` | the cost entered for it, divided by its units, in the currency of that cost; else the operation's own `price` in the operation's currency, or nothing when the broker gives none - see ["Transfers in with an entered cost"](#transfers-in-with-an-entered-cost) |
| `stock_dividend`, `reward` | the operation's own `price`, the market value per unit, in the operation's currency, or nothing when it gives none - see ["Stock dividends"](#stock-dividends) and ["Rewards"](#rewards) |

No operation of an instrument in `futures` opens a lot, whatever its kind: see
["Futures"](#futures).

A lot's `quantity` is the operation's `quantity`, which is the **executed** units. A buy that was
only partly filled opens a lot of the units that were filled, costed over those units: its `amount`
is the payment for them. A buy that executed no units opens no lot.

`acquiredOn` is the operation's date (`AccountOperation.occurredOn`, `occurredAt` as a UTC date, the
same convention `PortfolioValuationServiceImpl` uses throughout). Every buy opens its **own** lot -
same-day buys of the same instrument are never merged into one lot, so each keeps its own `unitCost`
even when they land on the same date.

An instrument's lots are kept oldest first by `acquiredOn`, which a purchase keeps by itself. A lot
that a corporate action carries over keeps the date of the lot it comes from, and so goes before
the lots of its instrument that were opened later, as when a merger brings shares into an
instrument that is already held. So does the lot of a transfer in whose entered cost gives an
earlier day.

### Fee pro-ration

A day's total of the instrument's `fee` operations **in each currency**, by `|amount|` (there can be
more than one fee operation on a day; they're summed), is pro-rated **twice, independently**, once
over that day's buys of the instrument and once over its sells, by quantity:

- a fee in a currency that the instrument had buys in that day goes to those buys **in that
  currency** alone, each taking its own share of the day's *buy* quantity in the currency. The share
  joins the buy's cost in that currency, added to the numerator before dividing by the buy's own
  quantity;
- a fee in a currency that the instrument had no buy in that day is shared among **all** of that
  day's buys of the instrument, in whatever currency they were paid, by each one's share of the
  day's total buy quantity, and joins the lot's unit cost in the currency of the fee;
- a fee in a currency that the instrument had sells in that day goes to those sells in that currency
  alone, by their share of the day's *sell* quantity in it, and is subtracted from each sale's
  `proceeds`;
- a fee in a currency that the instrument had no sell in that day is shared among all of that day's
  sells of the instrument by quantity and joins each sale's `costBasis` in the currency of the fee,
  since it cannot come off proceeds in another currency.

A commission in dollars on a trade in Taiwan dollars, which Interactive Brokers charges, is thus part
of the lot's cost in dollars, and the estimate converts it with the rest. A day with no buy, or no
sell, of the instrument gives its fees to no trade on that side.

This is a deliberate simplification: a day that has both buys and sells of the same instrument has
its one fee total used in full on *each* side rather than split once across both. In practice a
broker's own commission is charged per trade, so a day that mixes a buy and a sell of the same
instrument (the only case this double-uses the fee pool) is rare; it is called out here rather than
hidden. `security_in` never receives a fee share (there is no `amount`-derived cost for a fee to add
to). A fee names the instrument it was charged on, so a fee with no instrument, such as a service
charge on the account, is in no trade's cost. Nor is a fee charged on a future: it joins no pool,
and the futures result counts it instead.

### Consuming lots on a sell

| Operation kind | Effect |
|---|---|
| `sell` | consumes lots, and produces a `RealizedGain` |
| `security_out` | consumes lots, and realizes nothing - see "Transfers out" below |

Both consume the instrument's open lots oldest-first ("First In, First Out"), splitting the one lot
that only partially covers the remainder. Exactly one `RealizedGain` is produced per sell
operation, always (and one per leg of a conversion that gave up units and was paid cash, see
["Conversions"](#conversions)):

- `currency` is the currency of the sell operation, which `proceeds` is in;
- `proceeds = |amount| - that day's pro-rated fee share in that currency` (see above), regardless of
  how much of the sell was actually matched against open lots;
- `quantity`/`costBasis` reflect only the lots actually consumed - see "selling more than held"
  below;
- `costBasis` is the cost of the consumed units in each currency their lots were paid in, with no
  zero amount, plus the sale's share of any fee in a currency that the instrument had no sell in
  that day. It is empty for a sale matched against no lot, and for lots that cost nothing. The
  ledger has no exchange rates, so it holds no `gain`: `TaxReport` takes the proceeds and every
  cost to the base currency (see ["How a gain is converted"](#how-a-gain-is-converted));
- `holdingDays` is the **quantity-weighted average** of the consumed lots' own holding periods
  (`ChronoUnit.DAYS.between(lot.acquiredOn, sellDate)`), rounded to the nearest whole day. A sell
  fully covered by one lot (the common case) has only one value to average, so this only matters
  when a sell spans more than one lot bought on different dates. **Judgment call, flagged for
  review** (same spirit as `Risk.sortino`'s convention note in [`analytics.md`](analytics.md)): the
  spec calls for one `RealizedGain` per sell with a single `holdingDays`, which has no single
  "correct" definition once more than one lot is involved: this averages holding *days*, not the
  underlying *dates* (the two differ slightly for an uneven split), and `TaxReport.annual`'s
  long-term exemption check is only as precise as this one blended number.

**Transfers out**: a `security_out` consumes the oldest open lots of its instrument, as a sale
does, but realizes no gain: the units left the account at their cost, and a transfer is not a
disposal. It produces no `RealizedGain`, and a later sale is matched against the lots that remain.
The units beyond the open lots are added to `LotLedger.unmatched`, as for a sale. A transfer between
two accounts of one owner would ideally carry the lots, with their cost and dates, to the receiving
account; the library has no link across accounts to do that, so the receiving account's
`security_in` opens a lot at the cost the owner entered for it, else at the transfer's price, or at
no cost when it has none (see
["Transfers in with an entered cost"](#transfers-in-with-an-entered-cost)).

**Selling more than held**: if a sell's quantity, or the quantity of a transfer out, exceeds every
open lot for that instrument, whatever lots exist are fully consumed (down to zero open lots) and
the shortfall is added to `LotLedger.unmatched(instrument)` (a running total across every oversold
sell or transfer for that instrument - absent, not zero, for an instrument that was never
oversold). Nothing is raised or logged
(`TaxLots.fifo` is pure); the `RealizedGain` for that sell still carries the full `proceeds` but
only the matched `quantity`/`costBasis` - so an entirely unmatched sell (no lots at all, e.g. an
instrument bought before the loaded operations window) reports the whole proceeds against an empty
cost basis, which makes its gain the whole proceeds, the most conservative number available without
a cost basis to subtract.

### Corporate actions

A corporate action reaches the ledger as operations of four kinds, `split`, `conversion`, `spinoff`
and `stock_dividend`, which a broker's adapter gives the legs of an action; a disposal for cash is a
plain `sell`. They carry the lots through the action with their cost and acquisition dates, apart
from what a rule below says, and none of them takes a fee share.

The operations of one **group** of the kinds `conversion` and `spinoff`
(`AccountOperation.group`, the broker's id for the event) are applied together, when the fold
reaches the first of them, and dated by it. An operation of those kinds with no group is a group of
its own. The other kinds are applied one by one.

#### Splits

A `split` is one instrument's quantity changing with no cash, positive for a forward split and
negative for a reverse one. The instrument's open lots scale their units by
`(held + quantity) / held`, `held` being the units in its open lots, and keep their total cost and
acquisition dates, so each unit cost scales the other way. A forward split of 2 for 1 turns a lot of
10 units at 100 into 20 at 50. The last lot takes the units the others leave once rounded, so the
lots add up to the units held exactly, and a sale of all of them leaves no remainder.

- With no open lots, a positive quantity opens a lot at no cost on the day of the split, and a
  negative one is unmatched.
- Units that would leave nothing or less close the lots without a gain, and the shortfall is
  unmatched.

#### Stock dividends

A `stock_dividend` opens a lot of its `quantity` at its `price`, the market value per unit, in the
operation's currency, or at no cost when it has none, on the day of the dividend. The value is income
too, and `TaxReport` counts it (see [`dividendIncome`](#field-definitions)). No cash changes. A stock
dividend of a negative quantity, which no adapter gives, takes the units out of the lots like a
transfer out.

#### Conversions

The legs of a `conversion` group that gave up units (negative `quantity`) move their lots to the legs
that received units (positive `quantity`). Each leg that gave up units, its *out-leg*:

- consumes its units from its instrument's open lots, oldest first; units beyond the lots are
  unmatched;
- takes a share of the legs that received units, the *in-legs*, and of the group's cash, by their
  values (`price` times units) when every out-leg has a value above zero, else by their units, so
  that each has a share to carry its cost. One out-leg takes all.

`C` is the group's cash, the sum of the `amount` of its legs, and `V` the value of the in-legs, a leg
without a price counting zero.

- When `C` is positive, as in a merger paid in cash and stock, the fraction `f = C / (C + V)` of the
  consumed lots is disposed of. That is one `RealizedGain` for the out-leg's instrument on the
  day of the action, with its share of `C` as proceeds in the currency of the cash, `f` times the
  consumed cost as cost basis in the currencies the lots were paid in, `f` times the consumed units
  as `quantity`, and the holding days averaged as for a sale. The rest of the cost, `1 - f`, goes to
  the in-legs.
- When `C` is not positive, `f` is zero, and the cash paid, `-C`, as in a rights subscription, adds
  to the cost of the in-legs in the currency of the cash, next to the cost of the lots in theirs.
- Each in-leg gets one lot for each piece of lot that was consumed: its units times the piece's
  share of the out-leg's units, the acquisition date of the piece, and its share of the piece's
  cost, `(1 - f)` times it in each of its currencies plus the cash paid in proportion, in the
  currency of the cash. The in-legs share the cost by their value, or by their units when a leg has
  no price or `V` is zero. The in-leg units that stand for unmatched units of the out-leg form one
  lot on the day of the action, which costs their share of the cash paid.
- Every share of units and of cash is apportioned, the last taking what the others leave once
  rounded, so the lots of an in-leg add up to its units exactly.

A 1-for-25 reverse split onto a new instrument over two lots, 250 units at 2 and 250 at 6, gives the
new instrument two lots of 10 units, at 50 and at 150, with the dates of the old ones. A merger of 100
shares costing 1000 into 50 shares of 40 each and 500 in cash has `f` of 500 / (500 + 2000), a fifth:
a gain of 500 less 200 on 20 units, and a lot of 50 units costing 800. If the shares cost 1000 euros
and the merger pays in dollars, the sale is of 20 units for 500 dollars against a cost basis of 200
euros, and the 50 units cost 800 euros.

A group with no leg that gave up units opens a lot for each in-leg on the day of the action, which
costs its share of the cash paid.

#### Spin-offs

The in-legs of a `spinoff` group, those with a positive `quantity`, are the new instruments, and a
leg of zero quantity names the parent. The parent keeps its units.

- Each open lot of the parent gives each new instrument one lot: the new instrument's units times
  the lot's share of the parent's units held, the acquisition date of the parent's lot, and the
  fraction `a` of the parent lot's cost, in each currency it was paid in. The parent lot keeps what
  the fractions of all the new instruments leave of its cost, `1 - (a1 + a2 + ...)`. The last
  parent lot's share takes the units the others leave once rounded, so a new instrument's lots add
  up to its units exactly.
- A broker reports no value for a spin-off, so `a` is the **allocation** stored for the group and the
  new instrument, a `CorporateActionAllocation`, and zero without one: by default the new instrument
  takes none of the cost. An allocation is meant to be edited from the figures the issuer
  publishes. An allocation is kept within 0 and 1, and a group whose fractions add up to more than 1
  has them scaled down to add up to 1.
- Without a parent leg, or with a parent that has no open lots, each new instrument gets one lot at
  no cost on the day of the spin-off.
- A leg that gives up units, which no adapter gives, takes them out of the lots like a transfer out.

```scala
final case class CorporateActionAllocation(group: OperationGroup, instrument: InstrumentKey, costFraction: BigDecimal)
```

`TaxServiceImpl` reads the allocations of the account from
[`CorporateActionAllocationStorage`](storage-contracts.md#corporateactionallocationstoragef_) and
passes them to `TaxLots.fifo`, for `annualReport` and for `openLots` alike.

**What `LotLedger.realized` holds**: one entry per `sell` and one per conversion out-leg that was
paid cash. A split, a stock dividend, a spin-off and a conversion with no cash realize nothing, and
the gain on the shares of a stock dividend shows up when they are sold. A conversion into an
instrument quoted in another currency keeps the cost in the currency it was paid in, so the lots of
the new instrument cost in the old currency until they are sold and converted.

### Transfers in with an entered cost

A `security_in` brings units into the account with no cost of its own: a coin deposited from another
wallet, shares transferred from another broker. The owner can say what they cost:

```scala
final case class TransferCost(ref: OperationRef, cost: BigDecimal, currency: Currency,
                              acquiredOn: Option[LocalDate])
```

`ref` is the `ref` of the executed `security_in` the cost is for, `cost` the total paid for all of
its units, in `currency`, and `acquiredOn` the day the units were acquired where that is not the day
of the transfer. A `security_in` with an entered cost opens its lot at `cost / quantity` in the
currency of the cost, acquired on `acquiredOn` where it is given and on the operation's day
otherwise, and a lot acquired before the others of its instrument goes before them, as the lot a
corporate action carries over does. A zero cost makes a lot that costs nothing and still takes the
day. Without an entered cost, the lot costs the operation's `price` in its currency, or nothing when
it has none. A cost whose `ref` names no executed `security_in` of an instrument is ignored, so a
cost entered for an operation that was canceled or that carries no instrument changes nothing. The
cost applies at every broker, since a transfer in is the same operation at each.

`TaxServiceImpl` reads the account's costs from
[`TransferCostStorage`](storage-contracts.md#transfercoststoragef_) and passes them to `TaxLots.fifo`,
for `annualReport` and for `openLots` alike. The currency of a cost needs a rate on the day of a sale
like any other, so the service loads the bars of the currencies of the costs too, and the held
instruments service puts their rate tickers on the watchlist (see
[`market-data.md`](market-data.md#rate-tickers-on-the-watchlist)).

### Rewards

A `reward` is units of an instrument, or cash, received as income outside a trade, such as a
distribution or an airdrop. With an instrument, the ledger opens a lot of its `quantity` at its
`price`, the market value per unit, in the operation's currency, or at no cost when it has none, on
the day of the reward, as it does for a stock dividend. The value is income, which `TaxReport` counts
as [`rewardIncome`](#field-definitions). A reward of a negative quantity, which no adapter gives,
takes the units out of the lots like a transfer out. A reward of cash names no instrument, so it
opens no lot, and its amount is income. Neither the Tinkoff nor the Interactive Brokers translation
gives a reward.

### Futures

A futures contract has no lots. `TaxLots.fifo` leaves out every operation that names an instrument
in `futures`, before it orders the operations and pools the fees, so a future opens no lot, consumes
none, realizes no gain and leaves nothing unmatched, and its fees join no fee pool. The gain or loss
of a contract is its variation margin, which `TaxReport` counts as the
[`futuresResult`](#field-definitions). With an empty `futures` set, the default, every instrument is
in the ledger as before.

`TaxServiceImpl` takes the futures from the directory's record of each instrument it sees, the
record it reads for the ticker already (`BrokerInstrument.isFuture`; see
[`analytics.md`](analytics.md#futures)), and passes them to the ledger and to the report, for
`annualReport` and for `openLots` alike. So `openLots` of a future's ticker is empty, and `/lots`
replies that it has no open lots.

### Trades between coins and fees paid in a coin

A broker that exchanges coins for coins, as Binance does, reaches the ledger through the kinds
above, with no rule of its own:

- A trade or a Convert between two coins is a `sell` of the coin given up and a `buy` of the coin
  received, both at one amount in one currency, a stablecoin, so their cash amounts cancel. The
  sale consumes lots and realizes a gain, `proceeds` less cost, in that currency, and the coin
  received opens a lot at `|amount| / quantity` in it: the coin given up is disposed of at its
  market value, and the coin received starts at its market value.
- When no price values such an exchange, its legs are `conversion` operations of one group with no
  cash. The lots of the coin given up move to the coin received with their cost and acquisition
  dates (see ["Conversions"](#conversions)), and no gain is realized until the coin received is
  sold.
- A fee paid in a coin, BNB as a rule, is a `sell` of that coin, which realizes its own gain, and a
  `fee` of the same value naming the coin traded. The fee takes part in the [fee
  pro-ration](#fee-pro-ration) as any fee does, shared among the day's buys or sells of the coin in
  the fee's currency. The adapter values the fee in the currency of its trade where it can, so that
  it joins the trade's pool. A fee that names no coin, such as the network fee of a withdrawal or
  the fee of a conversion between cash assets, goes into no lot's cost. A commission paid in the
  coin received is a smaller quantity bought for the same amount, so the coin costs more per unit.
  A commission that no price values is a `security_out`, which consumes lots with no gain.
- A coin deposited from elsewhere is a `security_in` with no price, so its lot costs nothing and a
  sale of it counts the whole proceeds as gain, until the owner enters what it cost (see
  ["Transfers in with an entered cost"](#transfers-in-with-an-entered-cost)).
- A coin bought in one currency, such as EUR, and sold in another, such as USDT, keeps its cost in
  euros through the sale, which the report converts on the day of the sale; the same goes for a coin
  bought with one stablecoin and sold for another.
- Small balances converted into BNB, the dust, are a `conversion` of the coin given up into BNB,
  which carries the cost and dates of the coin and realizes nothing; dust in a stablecoin is a `buy`
  of BNB for it.
- A distribution or an airdrop of a coin is a `reward`, which opens a lot at the market value of
  the day (see ["Rewards"](#rewards)).

### Determinism

Operations are taken in the order of their `occurredAt`, and those at the same instant in the order
of their `ref`, whatever order they were given in. `LotLedger.realized` is
sorted by `(sellDate, instrument)` regardless of the input operations' own order or
`TaxLots.fifo`'s internal `groupBy` traversal order (a plain `Map`'s iteration order is not
guaranteed) - two sells of the same instrument on the same date keep their original chronological
relative order (a stable sort), and different instruments on the same date break the tie by
instrument: the `value` of the broker, then the id. `open` and `unmatched` are `Map`s, so their own
order is never observable.

## `TaxReport.annual` - the yearly estimate

```scala
final case class TaxConfig(baseCurrency: Currency = Currency("RUB"), progressiveThreshold: BigDecimal = 2400000,
                           lowerRate: BigDecimal = 0.13, upperRate: BigDecimal = 0.15,
                           longTermYears: Option[Int] = Some(3), fxTickers: Map[Currency, Ticker] = Map.empty)
final case class AnnualTaxReport(year: Int, realizedGains: BigDecimal, exemptGains: Option[BigDecimal],
                                 futuresResult: BigDecimal, taxableGains: BigDecimal, dividendIncome: BigDecimal,
                                 couponIncome: BigDecimal, rewardIncome: BigDecimal, taxesWithheld: BigDecimal,
                                 estimatedTax: BigDecimal, unconverted: Int, currency: Currency)

object TaxReport {
  def annual(year: Int, ledger: LotLedger, operations: List[AccountOperation],
             fxRate: (Currency, LocalDate) => Option[BigDecimal], config: TaxConfig = TaxConfig(),
             futures: Set[InstrumentKey] = Set.empty): AnnualTaxReport
  def isLongTerm(holdingDays: Long, config: TaxConfig): Boolean
}
```

`longTermYears` is `None` for a regime without a long-term exemption: then no gain is exempt and no
lot is long-term. The default, 3 years, follows the Russian rule for tax residents, like the other
defaults.

Pure. `operations` must be the same (or a superset of the) operations `ledger` was built from -
`TaxReport.annual` uses it for what `ledger` alone cannot provide: the **dividend, coupon and
reward income, the withheld tax, and the variation margin and fees of futures**. Dividends, coupons
and taxes never open or close a lot, so they never appear in `ledger` at all, the income of a
`stock_dividend` or a `reward` appears there only as the cost of the lot it opened, and a future has
no lots (see ["Futures"](#futures)). All of them are scanned directly from `operations`, the
executed ones of the year. `futures` names the instruments whose fees belong to the futures result.

### How a gain is converted

A realized gain is converted on the day of its sale, `RealizedGain.sellDate`, from the amounts of
its `RealizedGain`, and not from the other operations that name its instrument. Its proceeds are in
the currency of the sale, `RealizedGain.currency`, and its cost basis has an amount for each
currency its lots were paid in:

- the proceeds less the cost in the currency of the sale are converted together, with the sale's
  rate; with no cost in that currency this is the proceeds alone;
- every cost in another currency is converted in its own, with that currency's rate, and taken off.

A coin bought for 2000 euros, 0.1 of it, and sold for 2400 tether, with euros at 100 and tether at 90
in the base currency on the day of the sale, is a gain of 2400 x 90 - 2000 x 100 = 16000. A sale
whose lots were all paid in its own currency converts one amount and gives the figure it would from
`proceeds - costBasis` in that currency. A cost of nothing needs no rate. A gain with an amount that
has no rate is left out of every sum and counted once in `unconverted`. The long-term exemption is
judged on the converted gain: a long-term sale that is a loss once its amounts are converted is not
exempt, whatever its proceeds and costs look like in their own currencies.

The currency of a fee, a tax or a dividend on the instrument does not change the currency a gain is
in: a commission charged in dollars on a trade in Taiwan dollars joins the cost basis in dollars,
and the proceeds stay in Taiwan dollars.

### FX conversion

Every monetary figure in `AnnualTaxReport` is in `config.baseCurrency`. A non-base amount is
converted with `fxRate(currency, date)` - supplied by the caller, approximating the rate on `date`
with the latest close in the stored price history **at or before** `date` of the currency's rate
ticker: `config.fxTickers(currency)` when it names one, otherwise the conventional
`<currency><baseCurrency>=X` pair (the exact same convention `PortfolioValuationServiceImpl`
documents in [`analytics.md`](analytics.md#rules) - both share the lookup through
`analytics.FxRates`). The rate ticker's bars are read from the source that ticker is configured to
use, never from another source. The bars are there once the rate ticker is on a watchlist, which the
held instruments step arranges for the currencies of the account's operations and of its entered
transfer costs (see [`market-data.md`](market-data.md#rate-tickers-on-the-watchlist)). When `fxRate` returns `None` for
some amount (no bar on or before that date for that currency, as for a currency whose rate ticker
has no bars at all), that one amount is dropped from every sum it would have contributed
to and `unconverted` is incremented once for it - never twice, even for a realized gain that would
otherwise have counted toward both `realizedGains` and `exemptGains`.

Currencies are `models.domain.Currency` values, which normalise their code on construction (see
[`analytics.md`](analytics.md#rules), rule 10), so `TaxReport.annual` compares them exactly: a `usd`
operation is `USD` and is converted through `USDRUB=X` with base `RUB`, a `rub` operation counts as
base with no rate, and `AnnualTaxReport.currency` carries the configured base currency.

### Field definitions

1. **`realizedGains`**: every `RealizedGain` whose `sellDate.getYear == year`, converted as
   described in ["How a gain is converted"](#how-a-gain-is-converted) and summed - gains and
   losses alike.
2. **`exemptGains`**: the subset of the above that is both long-term (`TaxReport.isLongTerm`:
   `holdingDays >= longTermYears * 365`) **and** itself positive after conversion - a long-held
   *loss* is not "exempt", it simply remains counted in `realizedGains` and reduces the taxable
   total below. `None` when `longTermYears` is.
3. **`futuresResult`**: the year's executed `variation_margin` amounts plus the amounts of its
   executed `fee`s that name an instrument in `futures`, each converted on its own day, so a loss is
   negative. A margin counts whether or not it names its contract; a fee that names a share, or no
   instrument at all, does not count. An amount with no rate is counted in `unconverted` and left
   out.
4. **`taxableGains`**: `max(0, realizedGains - exemptGains.getOrElse(0) + futuresResult)`. The
   estimate sets the futures result against the gains in one base, so a loss on futures lowers the
   taxable gains, to no less than zero; regimes that tax derivatives as a base of their own differ.
5. **`dividendIncome`** / **`couponIncome`**: the `amount` of the year's `dividend` / `coupon`
   operations, converted and summed as-is (positive, following the same "amount is a signed
   payment" convention `PortfolioValuationServiceImpl` uses - income is a positive `amount`).
   `dividendIncome` also counts each `stock_dividend` of the year that has a `price`, at `price`
   times `quantity`, converted in the operation's currency on its date; one with no price counts
   nothing, and is not an unconverted amount.
6. **`rewardIncome`**: the year's executed `reward`s, each converted in its own currency on its
   date: one with an instrument at `price` times `quantity` (counting nothing, and no unconverted
   amount, when it has no price), one without an instrument at its `amount`. Like the other income,
   it is shown for reference and is not in `estimatedTax`.
7. **`taxesWithheld`**: **minus** the sum of the `amount` of the year's `tax` operations, converted.
   Withholding is a negative `amount`, an outflow, so the figure reads as "how much was withheld",
   and a refund or a correction in the account's favour, a positive `amount`, **lowers** it: it is
   negative if the refunds of the year exceed what was withheld in it. The `tax` kind is every
   broker-withheld-tax operation, refunds and corrections included. For Tinkoff that is
   `OPERATION_TYPE_BOND_TAX`, `_DIVIDEND_TAX`, `_TAX`, `_TAX_CORRECTION`, `_BENEFIT_TAX`, each of
   those five's own `_PROGRESSIVE` variant, every `_TAX_REPO*` variant (`_TAX_REPO`,
   `_TAX_REPO_PROGRESSIVE`, `_TAX_REPO_HOLD`, `_TAX_REPO_REFUND`, `_TAX_REPO_HOLD_PROGRESSIVE`,
   `_TAX_REPO_REFUND_PROGRESSIVE`) and `_TAX_CORRECTION_COUPON`, the correction of tax withheld on
   coupons.
8. **`estimatedTax`**: `config.lowerRate` applied to `taxableGains` up to `config.progressiveThreshold`,
   `config.upperRate` above it - `taxableGains <= threshold ? taxableGains * lowerRate : threshold *
   lowerRate + (taxableGains - threshold) * upperRate`. **Decision**: this estimates tax on
   `taxableGains` (realized capital gains, net of any long-term exemption, with the futures result)
   **only** - dividend, coupon and reward income is shown for reference and is not folded into this
   estimate, since the withholding on dividends and coupons is already reported separately in
   `taxesWithheld` (the broker withholds dividend/coupon tax at source; re-estimating it here would
   double-count against a figure the report already shows).
9. **`unconverted`**: total count of individual amounts (realized gains - counted once per gain even
   though it has several amounts to convert and a long-term one is also checked for `exemptGains`,
   see above - variation margins, futures fees, dividends, coupons, rewards, tax operations, stock
   dividends) dropped from every sum for lack of an `fxRate`.

## `TaxService[F]` / `TaxServiceImpl`

```scala
trait TaxService[F[_]] {
  def config: TaxConfig
  def annualReport(accountId: AccountId, year: Int): F[AnnualTaxReport]
  def openLots(accountId: AccountId, ticker: Ticker): F[List[Lot]]   // oldest first; Nil if none
}
object TaxServiceImpl {
  def create[F[_]: Monad: Clock](operations: AccountOperationStorage[F],
                                 allocations: CorporateActionAllocationStorage[F],
                                 transferCosts: TransferCostStorage[F],
                                 directory: InstrumentDirectory[F], sources: MarketDataSources[F],
                                 priceHistory: PriceHistoryStorage[F], config: TaxConfig): TaxService[F]
}
```

Same "load a long window, then narrow" shape as `PortfolioValuationServiceImpl`: both
`annualReport` and `openLots` load the account's operations from `AccountOperationStorage` over a
trailing 20-year window ending "now" (`Clock[F]`), regardless of the report year or ticker
requested, so a lot opened long before a requested year is still on hand to be matched against a
sale inside it. `config` is exposed on the trait itself (not threaded through every method) so a
caller like `LotsHandler` can judge its own lots with `TaxReport.isLongTerm` without a dedicated
method. Both also read the account's [spin-off allocations](#spin-offs) from `allocations` and its
[entered transfer costs](#transfers-in-with-an-entered-cost) from `transferCosts`, and build the
ledger with them.

Every instrument in the loaded operations is looked up through `InstrumentDirectory.byKey`, once
per distinct instrument per call. The record gives its ticker, and whether it is a future (see
["Futures"](#futures)); an instrument the directory does not know is left out of the mapping, so it
never matches a ticker in `openLots` and is not taken for a future. A ticker can have one instrument
per board: `openLots` returns the open lots of every instrument that maps to it, concatenated in
instrument order and then stably sorted by acquisition date, so the result is oldest first with
same-day lots in instrument order.

FX bars are loaded once per `annualReport` call, one `PriceHistoryStorage.bars` call per non-base
currency actually present in the loaded operations or in the account's transfer costs (over the same
20-year window), for that currency's rate ticker on the source the ticker is configured to use - see
["FX conversion"](#fx-conversion). `TaxConfig.fxTickers` and
[`ValuationConfig.fxTickers`](analytics.md#portfolio-valuation) name a rate ticker for a currency
whose rate is not the conventional pair.

## Commands

See [`telegram-commands.md`](telegram-commands.md#tax) for `/tax (year)` and `/lots SYM`.
