# Tax lots and reports

Package `st.btu.trading.pillar.tax`. Turns an account's executed `AccountOperation`s, its
operations in terms shared by every broker, into FIFO cost-basis lots, carried through splits,
mergers, spin-offs and stock dividends, and a per-year tax estimate.

**This is an estimate for personal reference, not tax advice.** It knows about exactly the rules
listed below - progressive rate, an optional long-term exemption, broker-withheld tax - and nothing
else (no loss carry-forward across years, no IIS deductions, no instrument-specific rules). The FX
conversion it uses is a documented approximation, not the official rate for any given instant - see
["FX conversion"](#fx-conversion) below.

## `TaxLots.fifo` - building the ledger

```scala
final case class Lot(instrument: InstrumentKey, quantity: BigDecimal, unitCost: BigDecimal, acquiredOn: LocalDate)
final case class RealizedGain(instrument: InstrumentKey, ticker: Option[Ticker], sellDate: LocalDate,
                              quantity: BigDecimal, currency: Currency, proceeds: BigDecimal,
                              costBasis: BigDecimal, gain: BigDecimal, holdingDays: Long)
final case class LotLedger(open: Map[InstrumentKey, List[Lot]], realized: List[RealizedGain],
                           unmatched: Map[InstrumentKey, BigDecimal])

object TaxLots {
  def fifo(operations: List[AccountOperation], tickerOf: InstrumentKey => Option[Ticker],
           allocations: List[CorporateActionAllocation] = Nil): LotLedger
}
```

Pure, no `F[_]`. Only operations whose `state` is `executed` and that name an instrument are
considered; everything else in `operations` is ignored, such as the legs of a currency exchange
(`exchange`), which name none. An `InstrumentKey` is the broker and the id
that broker gives the instrument, so lots of instruments at different brokers never mix. `tickerOf`
is a plain function so `TaxLots` itself never touches an `InstrumentDirectory` - the caller
(`TaxServiceImpl`) resolves every instrument it sees to a ticker once, up front. `allocations` is
plain data for the same reason: see ["Spin-offs"](#spin-offs). Quantities are decimals, since an
instrument can trade in fractions of a unit.

The ledger is one chronological fold over the operations of every instrument of the account, since
a conversion or a spin-off moves lots from one instrument to another. Its state is the open lots of
each instrument, the realized gains and the unmatched quantities. An operation is applied by its
kind, and the operations of one group of a conversion or a spin-off are applied together (see
["Corporate actions"](#corporate-actions)). The fee pools below stay per instrument.

### Opening a lot

| Operation kind | Lot's `unitCost` |
|---|---|
| `buy` | `\|amount\| / quantity`, **plus** that day's pro-rated fee share for this instrument (see below) |
| `security_in` | the operation's own `price`, or zero when the broker gives none - a transfer-in has no `amount` to derive a cost from |
| `stock_dividend` | the operation's own `price`, the market value per unit, or zero when the broker gives none - see ["Stock dividends"](#stock-dividends) |

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
instrument that is already held.

### Fee pro-ration

A day's total of the instrument's `fee` operations **in each currency**, by `|amount|` (there can be
more than one fee operation on a day; they're summed), is pro-rated **twice, independently**:

- across that day's buys of that instrument **in the same currency**, by each one's own share of
  the day's total *buy* quantity in that currency - added to each buy's `unitCost` numerator before
  dividing by its own quantity;
- across that day's sells of that instrument in the same currency, by each one's own share of the
  day's total *sell* quantity in that currency - subtracted from each sell's `proceeds`.

A fee is shared only among the trades in its own currency, since `TaxLots` has no exchange rates to
add a fee to a cost in another currency. A fee in a currency that the instrument had no buy, or no
sell, in that day goes into no trade's cost: a commission charged in dollars on a trade in Taiwan
dollars, which Interactive Brokers does, stays out of the lot's cost in Taiwan dollars, and so the
estimate leaves it out.

This is a deliberate simplification: a day that has both buys and sells of the same instrument has
its one fee total used in full on *each* side rather than split once across both. In practice a
broker's own commission is charged per trade, so a day that mixes a buy and a sell of the same
instrument (the only case this double-uses the fee pool) is rare; it is called out here rather than
hidden. `security_in` never receives a fee share (there is no `amount`-derived cost for a fee to add
to). A fee names the instrument it was charged on, so a fee with no instrument, such as a service
charge on the account, is in no trade's cost.

### Consuming lots on a sell

| Operation kind | Effect |
|---|---|
| `sell` | consumes lots, and produces a `RealizedGain` |
| `security_out` | consumes lots, and realizes nothing - see "Transfers out" below |

Both consume the instrument's open lots oldest-first ("First In, First Out"), splitting the one lot
that only partially covers the remainder. Exactly one `RealizedGain` is produced per sell
operation, always (and one per leg of a conversion that gave up units and was paid cash, see
["Conversions"](#conversions)):

- `currency` is the currency of the sell operation, which `proceeds`, `costBasis` and `gain` are
  in;
- `proceeds = |amount| - that day's pro-rated fee share` (see above), regardless of how much of the
  sell was actually matched against open lots;
- `quantity`/`costBasis` reflect only the lots actually consumed - see "selling more than held"
  below;
- `gain = proceeds - costBasis`;
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
`security_in` opens a lot at the transfer's price, or at no cost when it has none.

**Selling more than held**: if a sell's quantity, or the quantity of a transfer out, exceeds every
open lot for that instrument, whatever lots exist are fully consumed (down to zero open lots) and
the shortfall is added to `LotLedger.unmatched(instrument)` (a running total across every oversold
sell or transfer for that instrument - absent, not zero, for an instrument that was never
oversold). Nothing is raised or logged
(`TaxLots.fifo` is pure); the `RealizedGain` for that sell still carries the full `proceeds` but
only the matched `quantity`/`costBasis` - so an entirely unmatched sell (no lots at all, e.g. an
instrument bought before the loaded operations window) reports `gain == proceeds`, the most
conservative number available without a cost basis to subtract.

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

A `stock_dividend` opens a lot of its `quantity` at its `price`, the market value per unit, or at no
cost when it has none, on the day of the dividend. The value is income too, and `TaxReport` counts it
(see [`dividendIncome`](#field-definitions)). No cash changes. A stock dividend of a negative
quantity, which no adapter gives, takes the units out of the lots like a transfer out.

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
  day of the action, with its share of `C` as proceeds, `f` times the consumed cost as cost basis, `f`
  times the consumed units as `quantity`, and the holding days averaged as for a sale, in the currency
  of the cash. The rest of the cost, `1 - f`, goes to the in-legs.
- When `C` is not positive, `f` is zero, and the cash paid, `-C`, as in a rights subscription, adds
  to the cost of the in-legs.
- Each in-leg gets one lot for each piece of lot that was consumed: its units times the piece's
  share of the out-leg's units, the acquisition date of the piece, and its share of the piece's
  cost, `(1 - f)` times it plus the cash paid in proportion. The in-legs share the cost by their
  value, or by their units when a leg has no price or `V` is zero. The in-leg units that stand for
  unmatched units of the out-leg form one lot on the day of the action, which costs their share of the
  cash paid.
- Every share of units and of cash is apportioned, the last taking what the others leave once
  rounded, so the lots of an in-leg add up to its units exactly.

A 1-for-25 reverse split onto a new instrument over two lots, 250 units at 2 and 250 at 6, gives the
new instrument two lots of 10 units, at 50 and at 150, with the dates of the old ones. A merger of 100
shares costing 1000 into 50 shares of 40 each and 500 in cash has `f` of 500 / (500 + 2000), a fifth:
a gain of 500 less 200 on 20 units, and a lot of 50 units costing 800.

A group with no leg that gave up units opens a lot for each in-leg on the day of the action, which
costs its share of the cash paid.

#### Spin-offs

The in-legs of a `spinoff` group, those with a positive `quantity`, are the new instruments, and a
leg of zero quantity names the parent. The parent keeps its units.

- Each open lot of the parent gives each new instrument one lot: the new instrument's units times
  the lot's share of the parent's units held, the acquisition date of the parent's lot, and the
  fraction `a` of the parent lot's cost. The parent lot keeps what the fractions of all the new
  instruments leave of its cost, `1 - (a1 + a2 + ...)`. The last parent lot's share takes the units
  the others leave once rounded, so a new instrument's lots add up to its units exactly.
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
the gain on the shares of a stock dividend shows up when they are sold.

**Known limit**: lots carry no currency, so a conversion into an instrument quoted in another currency
keeps the cost in the old one.

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
  sale of it counts the whole proceeds as gain.

**Known limit**: lots carry no currency, so a coin bought in one currency, such as EUR, and sold in
another, such as USDT, has its cost counted as if it were in the currency of the sale. The same goes
for a conversion into an instrument quoted in another currency, and for a coin that was bought
with one stablecoin and sold for another.

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
                                 taxableGains: BigDecimal, dividendIncome: BigDecimal, couponIncome: BigDecimal,
                                 taxesWithheld: BigDecimal, estimatedTax: BigDecimal, unconverted: Int,
                                 currency: Currency)

object TaxReport {
  def annual(year: Int, ledger: LotLedger, operations: List[AccountOperation],
             fxRate: (Currency, LocalDate) => Option[BigDecimal], config: TaxConfig = TaxConfig()): AnnualTaxReport
  def isLongTerm(holdingDays: Long, config: TaxConfig): Boolean
}
```

`longTermYears` is `None` for a regime without a long-term exemption: then no gain is exempt and no
lot is long-term. The default, 3 years, follows the Russian rule for tax residents, like the other
defaults.

Pure. `operations` must be the same (or a superset of the) operations `ledger` was built from -
`TaxReport.annual` uses it for what `ledger` alone cannot provide: the **dividend and coupon
income, and the withheld tax**. These operation kinds never open or close a lot, so they never
appear in `ledger` at all; they are scanned directly from `operations`, the executed ones of the
year. So is the market value of a `stock_dividend`, which also opened a lot.

**Each gain's currency.** A realized gain is converted in the currency of its own sale,
`RealizedGain.currency`, and not in the currency of the other operations that name its instrument.
A fee, a tax or a dividend on the instrument can be in another currency than its trades (a
commission charged in dollars on a trade in Taiwan dollars, say), and none of them changes the
currency a gain is in.

### FX conversion

Every monetary figure in `AnnualTaxReport` is in `config.baseCurrency`. A non-base amount is
converted with `fxRate(currency, date)` - supplied by the caller, approximating the rate on `date`
with the latest close in the stored price history **at or before** `date` of the currency's rate
ticker: `config.fxTickers(currency)` when it names one, otherwise the conventional
`<currency><baseCurrency>=X` pair (the exact same convention `PortfolioValuationServiceImpl`
documents in [`analytics.md`](analytics.md#rules) - both share the lookup through
`analytics.FxRates`). The rate ticker's bars are read from the source that ticker is configured to
use, never from another source. When `fxRate` returns `None` for some amount (no bar on or before
that date for that currency), that one amount is dropped from every sum it would have contributed
to and `unconverted` is incremented once for it - never twice, even for a realized gain that would
otherwise have counted toward both `realizedGains` and `exemptGains`.

Currencies are `models.domain.Currency` values, which normalise their code on construction (see
[`analytics.md`](analytics.md#rules), rule 10), so `TaxReport.annual` compares them exactly: a `usd`
operation is `USD` and is converted through `USDRUB=X` with base `RUB`, a `rub` operation counts as
base with no rate, and `AnnualTaxReport.currency` carries the configured base currency.

### Field definitions

1. **`realizedGains`**: every `RealizedGain` whose `sellDate.getYear == year`, converted and summed -
   gains and losses alike.
2. **`exemptGains`**: the subset of the above that is both long-term (`TaxReport.isLongTerm`:
   `holdingDays >= longTermYears * 365`) **and** itself positive after conversion - a long-held
   *loss* is not "exempt", it simply remains counted in `realizedGains` and reduces the taxable
   total below. (An FX rate is always positive, so checking the converted gain's sign or the
   original's is equivalent.) `None` when `longTermYears` is.
3. **`taxableGains`**: `max(0, realizedGains - exemptGains.getOrElse(0))`.
4. **`dividendIncome`** / **`couponIncome`**: the `amount` of the year's `dividend` / `coupon`
   operations, converted and summed as-is (positive, following the same "amount is a signed
   payment" convention `PortfolioValuationServiceImpl` uses - income is a positive `amount`).
   `dividendIncome` also counts each `stock_dividend` of the year that has a `price`, at `price`
   times `quantity`, converted in the operation's currency on its date; one with no price counts
   nothing, and is not an unconverted amount.
5. **`taxesWithheld`**: **minus** the sum of the `amount` of the year's `tax` operations, converted.
   Withholding is a negative `amount`, an outflow, so the figure reads as "how much was withheld",
   and a refund or a correction in the account's favour, a positive `amount`, **lowers** it: it is
   negative if the refunds of the year exceed what was withheld in it. The `tax` kind is every
   broker-withheld-tax operation, refunds and corrections included. For Tinkoff that is
   `OPERATION_TYPE_BOND_TAX`, `_DIVIDEND_TAX`, `_TAX`, `_TAX_CORRECTION`, `_BENEFIT_TAX`, each of
   those five's own `_PROGRESSIVE` variant, every `_TAX_REPO*` variant (`_TAX_REPO`,
   `_TAX_REPO_PROGRESSIVE`, `_TAX_REPO_HOLD`, `_TAX_REPO_REFUND`, `_TAX_REPO_HOLD_PROGRESSIVE`,
   `_TAX_REPO_REFUND_PROGRESSIVE`) and `_TAX_CORRECTION_COUPON`, the correction of tax withheld on
   coupons.
6. **`estimatedTax`**: `config.lowerRate` applied to `taxableGains` up to `config.progressiveThreshold`,
   `config.upperRate` above it - `taxableGains <= threshold ? taxableGains * lowerRate : threshold *
   lowerRate + (taxableGains - threshold) * upperRate`. **Decision**: this estimates tax on
   `taxableGains` (realized capital gains, net of any long-term exemption) **only** - dividend/coupon
   income is shown for reference and is not folded into this estimate, since its own withholding is
   already reported separately in `taxesWithheld` (the broker withholds dividend/coupon tax at
   source; re-estimating it here would double-count against a figure the report already shows).
7. **`unconverted`**: total count of individual amounts (realized gains - counted once per gain even
   though a long-term one is also checked for `exemptGains`, see above - dividends, coupons, tax
   operations, stock dividends) dropped from every sum for lack of an `fxRate`.

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
method. Both also read the account's [spin-off allocations](#spin-offs) from `allocations`, and build
the ledger with them.

Every instrument in the loaded operations is mapped to a ticker through
`InstrumentDirectory.byKey`, once per distinct instrument per call; an instrument the directory
does not know is left out of the ticker mapping, so it never matches a ticker in `openLots`. A
ticker can have one instrument per board: `openLots` returns the open lots of every instrument that
maps to it, concatenated in instrument order and then stably sorted by acquisition date, so the
result is oldest first with same-day lots in instrument order.

FX bars are loaded once per `annualReport` call, one `PriceHistoryStorage.bars` call per non-base
currency actually present in the loaded operations (over the same 20-year window), for that
currency's rate ticker on the source the ticker is configured to use - see
["FX conversion"](#fx-conversion). `TaxConfig.fxTickers` and
[`ValuationConfig.fxTickers`](analytics.md#portfolio-valuation) name a rate ticker for a currency
whose rate is not the conventional pair.

## Commands

See [`telegram-commands.md`](telegram-commands.md#tax) for `/tax (year)` and `/lots SYM`.
