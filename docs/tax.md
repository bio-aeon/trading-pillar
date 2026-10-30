# Tax lots and reports

Package `st.btu.trading.pillar.tax`. Turns an account's executed `TcsPortfolioOperation`s
into FIFO cost-basis lots and a per-year tax estimate.

**This is an estimate for personal reference, not tax advice.** It knows about exactly the rules
listed below - progressive rate, an optional long-term exemption, broker-withheld tax - and nothing
else (no loss carry-forward across years, no IIS deductions, no instrument-specific rules). The FX
conversion it uses is a documented approximation, not the official rate for any given instant - see
["FX conversion"](#fx-conversion) below.

## `TaxLots.fifo` - building the ledger

```scala
final case class Lot(figi: String, quantity: Long, unitCost: BigDecimal, acquiredOn: LocalDate)
final case class RealizedGain(figi: String, ticker: Option[Ticker], sellDate: LocalDate, quantity: Long,
                              proceeds: BigDecimal, costBasis: BigDecimal, gain: BigDecimal, holdingDays: Long)
final case class LotLedger(open: Map[String, List[Lot]], realized: List[RealizedGain], unmatched: Map[String, Long])

object TaxLots {
  def fifo(operations: List[TcsPortfolioOperation], tickerOf: String => Option[Ticker]): LotLedger
}
```

Pure, no `F[_]`. Only `status.value == "OPERATION_STATE_EXECUTED"` operations are considered;
everything else in `operations` is ignored. `tickerOf` is a plain function so `TaxLots` itself
never touches an `InstrumentDirectory` - the caller (`TaxServiceImpl`) resolves every figi it sees
to a ticker once, up front.

### Opening a lot

| Operation type | Lot's `unitCost` |
|---|---|
| `OPERATION_TYPE_BUY`, `_BUY_CARD`, `_BUY_MARGIN`, `_DELIVERY_BUY` | `\|amount\| / quantity`, **plus** that day's pro-rated `OPERATION_TYPE_BROKER_FEE` share for this figi (see below) |
| `OPERATION_TYPE_INPUT_SECURITIES` | the operation's own `price` field, unconverted - a transfer-in has no `amount` to derive a cost from |

`acquiredOn` is the operation's own date (`createdAt`, converted to a `LocalDate` in UTC, the same
convention `PortfolioValuationServiceImpl` uses throughout). Every buy-family operation opens its
**own** lot - same-day buys of the same figi are never merged into one lot, so each keeps its own
`unitCost` even when they land on the same date.

### Fee pro-ration

A day's total `OPERATION_TYPE_BROKER_FEE` for one figi (there can be more than one fee operation on
a day; they're summed) is pro-rated **twice, independently**:

- across that day's buy-family operations for that figi, by each one's own share of the day's total
  *buy* quantity - added to each buy's `unitCost` numerator before dividing by its own quantity;
- across that day's sell-family operations for that figi, by each one's own share of the day's total
  *sell* quantity - subtracted from each sell's `proceeds`.

This is a deliberate simplification: a day that has both buys and sells of the same figi has its one
fee total used in full on *each* side rather than split once across both. In practice a broker's own
commission is charged per trade, so a day that mixes a buy and a sell of the same figi (the only case
this double-uses the fee pool) is rare; it is called out here rather than hidden. `INPUT_SECURITIES`
never receives a fee share (there is no `amount`-derived cost for a fee to add to).

### Consuming lots on a sell

| Operation type |
|---|
| `OPERATION_TYPE_SELL`, `_SELL_CARD`, `_SELL_MARGIN`, `_DELIVERY_SELL`, `_BOND_REPAYMENT_FULL` |

Consumes the figi's open lots oldest-first ("First In, First Out"), splitting the one lot that only
partially covers the remainder. Exactly one `RealizedGain` is produced per sell operation, always:

- `proceeds = |amount| - that day's pro-rated broker fee share` (see above), regardless of how much
  of the sell was actually matched against open lots;
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

**Selling more than held**: if a sell's quantity exceeds every open lot for that figi, whatever lots
exist are fully consumed (down to zero open lots) and the shortfall is added to
`LotLedger.unmatched(figi)` (a running total across every oversold sell for that figi - absent, not
zero, for a figi that was never oversold). Nothing is raised or logged (`TaxLots.fifo` is pure); the
`RealizedGain` for that sell still carries the full `proceeds` but only the matched `quantity`/
`costBasis` - so an entirely unmatched sell (no lots at all, e.g. a figi bought before the loaded
operations window) reports `gain == proceeds`, the most conservative number available without a cost
basis to subtract.

### Determinism

`LotLedger.realized` is sorted by `(sellDate, figi)` regardless of the input operations' own order
or `TaxLots.fifo`'s internal `groupBy` traversal order (a plain `Map`'s iteration order is not
guaranteed) - two sells of the same figi on the same date keep their original chronological relative
order (a stable sort), and different figis on the same date break the tie by figi string. `open` and
`unmatched` are `Map`s, so their own order is never observable.

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
  def annual(year: Int, ledger: LotLedger, operations: List[TcsPortfolioOperation],
             fxRate: (Currency, LocalDate) => Option[BigDecimal], config: TaxConfig = TaxConfig()): AnnualTaxReport
  def isLongTerm(holdingDays: Long, config: TaxConfig): Boolean
}
```

`longTermYears` is `None` for a regime without a long-term exemption: then no gain is exempt and no
lot is long-term. The default, 3 years, follows the Russian rule for tax residents, like the other
defaults.

Pure. `operations` must be the same (or a superset of the) operations `ledger` was built from -
`TaxReport.annual` uses it for two things `ledger` alone cannot provide:

1. **Each figi's trading currency.** `RealizedGain` carries no currency of its own, so it is looked
   up from any operation referencing that figi (`operations.flatMap(op => op.figi.map(_ ->
   op.currency)).toMap`), trusting - as `PortfolioValuationServiceImpl` already does - that one
   instrument trades in one currency.
2. **Dividend and coupon income, and withheld tax.** These operation types never open or close a
   lot, so they never appear in `ledger` at all; they are scanned directly from `operations`.

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
4. **`dividendIncome`** / **`couponIncome`**: `OPERATION_TYPE_DIVIDEND` / `OPERATION_TYPE_COUPON`
   operations of the year, converted and summed as-is (positive, following the same "amount is a
   signed payment" convention `PortfolioValuationServiceImpl` uses - income is a positive `amount`).
5. **`taxesWithheld`**: the broker-withheld-tax family below, of the year, converted and summed as a
   **positive magnitude** (`|amount|` - withholding is a negative `amount`, an outflow, but this
   field reads as "how much was withheld"):
   `OPERATION_TYPE_BOND_TAX`, `_DIVIDEND_TAX`, `_TAX`, `_TAX_CORRECTION`, `_BENEFIT_TAX`, each of
   those five's own `_PROGRESSIVE` variant, and every `_TAX_REPO*` variant (`_TAX_REPO`,
   `_TAX_REPO_PROGRESSIVE`, `_TAX_REPO_HOLD`, `_TAX_REPO_REFUND`, `_TAX_REPO_HOLD_PROGRESSIVE`,
   `_TAX_REPO_REFUND_PROGRESSIVE`). Note `OPERATION_TYPE_TAX_CORRECTION_COUPON` is **not** in this
   list (it matches neither "ends in `_TAX`/named family above" nor "starts with `TAX_REPO`"
   literally) - a deliberate narrow reading of the spec's enumerated set, not an oversight.
6. **`estimatedTax`**: `config.lowerRate` applied to `taxableGains` up to `config.progressiveThreshold`,
   `config.upperRate` above it - `taxableGains <= threshold ? taxableGains * lowerRate : threshold *
   lowerRate + (taxableGains - threshold) * upperRate`. **Decision**: this estimates tax on
   `taxableGains` (realized capital gains, net of any long-term exemption) **only** - dividend/coupon
   income is shown for reference and is not folded into this estimate, since its own withholding is
   already reported separately in `taxesWithheld` (the broker withholds dividend/coupon tax at
   source; re-estimating it here would double-count against a figure the report already shows).
7. **`unconverted`**: total count of individual amounts (realized gains - counted once per gain even
   though a long-term one is also checked for `exemptGains`, see above - dividends, coupons,
   withheld-tax operations) dropped from every sum for lack of an `fxRate`.

## `TaxService[F]` / `TaxServiceImpl`

```scala
trait TaxService[F[_]] {
  def config: TaxConfig
  def annualReport(accountId: AccountId, year: Int): F[AnnualTaxReport]
  def openLots(accountId: AccountId, ticker: Ticker): F[List[Lot]]   // oldest first; Nil if none
}
object TaxServiceImpl {
  def create[F[_]: Monad: Clock](operations: TcsPortfolioOperationStorage[F], directory: InstrumentDirectory[F],
                                 sources: MarketDataSources[F], priceHistory: PriceHistoryStorage[F],
                                 config: TaxConfig): TaxService[F]
}
```

Same "load a long window, then narrow" shape as `PortfolioValuationServiceImpl`: both
`annualReport` and `openLots` load the account's executed operations over a trailing 20-year window
ending "now" (`Clock[F]`), regardless of the report year or ticker requested, so a lot opened long
before a requested year is still on hand to be matched against a sale inside it. `config` is exposed
on the trait itself (not threaded through every method) so a caller like `LotsHandler` can judge its
own lots with `TaxReport.isLongTerm` without a dedicated method.

Every figi in the loaded operations is mapped to a ticker through `InstrumentDirectory.byFigi`, once
per distinct figi per call; a figi the directory does not know is left out of the ticker mapping, so
it never matches a ticker in `openLots`. A ticker can have one figi per board: `openLots` returns the
open lots of every figi that maps to it, concatenated in figi order and then stably sorted by
acquisition date, so the result is oldest first with same-day lots in figi order.

FX bars are loaded once per `annualReport` call, one `PriceHistoryStorage.bars` call per non-base
currency actually present in the loaded operations (over the same 20-year window), for that
currency's rate ticker on the source the ticker is configured to use - see
["FX conversion"](#fx-conversion). `TaxConfig.fxTickers` and
[`ValuationConfig.fxTickers`](analytics.md#portfolio-valuation) name a rate ticker for a currency
whose rate is not the conventional pair.

## Commands

See [`telegram-commands.md`](telegram-commands.md#tax) for `/tax (year)` and `/lots SYM`.
