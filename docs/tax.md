# Tax lots and reports

Package `st.btu.trading.pillar.tax`. Turns an account's executed `AccountOperation`s, its
operations in terms shared by every broker, into FIFO cost-basis lots and a per-year tax estimate.

**This is an estimate for personal reference, not tax advice.** It knows about exactly the rules
listed below - progressive rate, an optional long-term exemption, broker-withheld tax - and nothing
else (no loss carry-forward across years, no IIS deductions, no instrument-specific rules). The FX
conversion it uses is a documented approximation, not the official rate for any given instant - see
["FX conversion"](#fx-conversion) below.

## `TaxLots.fifo` - building the ledger

```scala
final case class Lot(instrument: InstrumentKey, quantity: BigDecimal, unitCost: BigDecimal, acquiredOn: LocalDate)
final case class RealizedGain(instrument: InstrumentKey, ticker: Option[Ticker], sellDate: LocalDate,
                              quantity: BigDecimal, proceeds: BigDecimal, costBasis: BigDecimal, gain: BigDecimal,
                              holdingDays: Long)
final case class LotLedger(open: Map[InstrumentKey, List[Lot]], realized: List[RealizedGain],
                           unmatched: Map[InstrumentKey, BigDecimal])

object TaxLots {
  def fifo(operations: List[AccountOperation], tickerOf: InstrumentKey => Option[Ticker]): LotLedger
}
```

Pure, no `F[_]`. Only operations whose `state` is `executed` and that name an instrument are
considered; everything else in `operations` is ignored. An `InstrumentKey` is the broker and the id
that broker gives the instrument, so lots of instruments at different brokers never mix. `tickerOf`
is a plain function so `TaxLots` itself never touches an `InstrumentDirectory` - the caller
(`TaxServiceImpl`) resolves every instrument it sees to a ticker once, up front. Quantities are
decimals, since an instrument can trade in fractions of a unit.

### Opening a lot

| Operation kind | Lot's `unitCost` |
|---|---|
| `buy` | `\|amount\| / quantity`, **plus** that day's pro-rated fee share for this instrument (see below) |
| `security_in` | the operation's own `price`, or zero when the broker gives none - a transfer-in has no `amount` to derive a cost from |

A lot's `quantity` is the operation's `quantity`, which is the **executed** units. A buy that was
only partly filled opens a lot of the units that were filled, costed over those units: its `amount`
is the payment for them. A buy that executed no units opens no lot.

`acquiredOn` is the operation's date (`AccountOperation.occurredOn`, `occurredAt` as a UTC date, the
same convention `PortfolioValuationServiceImpl` uses throughout). Every buy opens its **own** lot -
same-day buys of the same instrument are never merged into one lot, so each keeps its own `unitCost`
even when they land on the same date.

### Fee pro-ration

A day's total of the instrument's `fee` operations, by `|amount|` (there can be more than one fee
operation on a day; they're summed), is pro-rated **twice, independently**:

- across that day's buys of that instrument, by each one's own share of the day's total *buy*
  quantity - added to each buy's `unitCost` numerator before dividing by its own quantity;
- across that day's sells of that instrument, by each one's own share of the day's total *sell*
  quantity - subtracted from each sell's `proceeds`.

This is a deliberate simplification: a day that has both buys and sells of the same instrument has
its one fee total used in full on *each* side rather than split once across both. In practice a
broker's own commission is charged per trade, so a day that mixes a buy and a sell of the same
instrument (the only case this double-uses the fee pool) is rare; it is called out here rather than
hidden. `security_in` never receives a fee share (there is no `amount`-derived cost for a fee to add
to). A fee names the instrument it was charged on, so a fee with no instrument, such as a service
charge on the account, is in no trade's cost.

### Consuming lots on a sell

| Operation kind |
|---|
| `sell` |

Consumes the instrument's open lots oldest-first ("First In, First Out"), splitting the one lot that
only partially covers the remainder. Exactly one `RealizedGain` is produced per sell operation,
always:

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

**Transfers out**: a `security_out` does not touch the ledger. It opens no lot, consumes none and
produces no `RealizedGain`, so the lots of units transferred away stay open and a later sale can be
matched against them. The valuation does take such units out of the position.

**Selling more than held**: if a sell's quantity exceeds every open lot for that instrument,
whatever lots exist are fully consumed (down to zero open lots) and the shortfall is added to
`LotLedger.unmatched(instrument)` (a running total across every oversold sell for that instrument -
absent, not zero, for an instrument that was never oversold). Nothing is raised or logged
(`TaxLots.fifo` is pure); the `RealizedGain` for that sell still carries the full `proceeds` but
only the matched `quantity`/`costBasis` - so an entirely unmatched sell (no lots at all, e.g. an
instrument bought before the loaded operations window) reports `gain == proceeds`, the most
conservative number available without a cost basis to subtract.

### Determinism

Operations of one instrument are taken in the order of their `occurredAt`, and those at the same
instant in the order of their `ref`, whatever order they were given in. `LotLedger.realized` is
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
`TaxReport.annual` uses it for two things `ledger` alone cannot provide:

1. **Each instrument's trading currency.** `RealizedGain` carries no currency of its own, so it is
   looked up from any operation referencing that instrument (`operations.flatMap(op =>
   op.instrument.map(_ -> op.currency)).toMap`), trusting - as `PortfolioValuationServiceImpl`
   already does - that one instrument trades in one currency.
2. **Dividend and coupon income, and withheld tax.** These operation kinds never open or close a
   lot, so they never appear in `ledger` at all; they are scanned directly from `operations`, the
   executed ones of the year.

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
   operations) dropped from every sum for lack of an `fxRate`.

## `TaxService[F]` / `TaxServiceImpl`

```scala
trait TaxService[F[_]] {
  def config: TaxConfig
  def annualReport(accountId: AccountId, year: Int): F[AnnualTaxReport]
  def openLots(accountId: AccountId, ticker: Ticker): F[List[Lot]]   // oldest first; Nil if none
}
object TaxServiceImpl {
  def create[F[_]: Monad: Clock](operations: AccountOperationStorage[F], directory: InstrumentDirectory[F],
                                 sources: MarketDataSources[F], priceHistory: PriceHistoryStorage[F],
                                 config: TaxConfig): TaxService[F]
}
```

Same "load a long window, then narrow" shape as `PortfolioValuationServiceImpl`: both
`annualReport` and `openLots` load the account's operations from `AccountOperationStorage` over a
trailing 20-year window ending "now" (`Clock[F]`), regardless of the report year or ticker
requested, so a lot opened long before a requested year is still on hand to be matched against a
sale inside it. `config` is exposed on the trait itself (not threaded through every method) so a
caller like `LotsHandler` can judge its own lots with `TaxReport.isLongTerm` without a dedicated
method.

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
