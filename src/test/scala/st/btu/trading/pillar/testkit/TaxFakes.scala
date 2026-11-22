package st.btu.trading.pillar.testkit

import cats.effect.IO
import st.btu.trading.pillar.models.domain.{AccountId, Currency, InstrumentKey}
import st.btu.trading.pillar.models.market.Ticker
import st.btu.trading.pillar.storages.{CorporateActionAllocationStorage, TransferCostStorage}
import st.btu.trading.pillar.tax.*

import java.time.LocalDate

object TaxFakes {

  private val usd = Currency("USD")

  /** A lot whose units cost `unitCost` each in `currency`, dollars unless another is named; a lot
    * at no cost has no cost in any currency.
    */
  def lot(
    instrument: InstrumentKey,
    quantity: BigDecimal,
    unitCost: BigDecimal,
    acquiredOn: LocalDate,
    currency: Currency = usd
  ): Lot =
    Lot(instrument, quantity, costIn(currency, unitCost), acquiredOn)

  /** The result of a sale for `proceeds` in `currency`, dollars unless another is named, of lots
    * that cost `costBasis` in that currency, with the ticker left unknown.
    */
  def realizedGain(
    instrument: InstrumentKey,
    sellDate: LocalDate,
    quantity: BigDecimal,
    proceeds: BigDecimal,
    costBasis: BigDecimal,
    holdingDays: Long,
    currency: Currency = usd
  ): RealizedGain =
    RealizedGain(
      instrument,
      None,
      sellDate,
      quantity,
      currency,
      proceeds,
      costIn(currency, costBasis),
      holdingDays
    )

  /** `amount` in `currency`, or nothing when it is zero. */
  def costIn(currency: Currency, amount: BigDecimal): Map[Currency, BigDecimal] =
    if (amount.signum == 0) Map.empty else Map(currency -> amount)

  trait FakeTaxService extends TaxService[IO]

  def taxService(
    taxConfig: TaxConfig = TaxConfig(),
    annualReports: Map[(AccountId, Int), AnnualTaxReport] = Map.empty,
    openLotsByTicker: Map[(AccountId, Ticker), List[Lot]] = Map.empty
  ): IO[FakeTaxService] =
    IO.pure(new FakeTaxService {
      def config: TaxConfig = taxConfig

      def annualReport(accountId: AccountId, year: Int): IO[AnnualTaxReport] =
        annualReports.get((accountId, year)) match {
          case Some(report) => IO.pure(report)
          case None =>
            IO.raiseError(
              new NoSuchElementException(s"no fake annual report configured for $accountId/$year")
            )
        }

      def openLots(accountId: AccountId, ticker: Ticker): IO[List[Lot]] =
        IO.pure(openLotsByTicker.getOrElse((accountId, ticker), Nil))
    })

  trait FakeTransferCostStorage extends TransferCostStorage[IO]

  def transferCostStorage(
    costs: Map[AccountId, List[TransferCost]] = Map.empty
  ): IO[FakeTransferCostStorage] =
    IO.pure(new FakeTransferCostStorage {
      def byAccount(accountId: AccountId): IO[List[TransferCost]] =
        IO.pure(costs.getOrElse(accountId, Nil))
    })

  trait FakeCorporateActionAllocationStorage extends CorporateActionAllocationStorage[IO]

  def corporateActionAllocationStorage(
    allocations: Map[AccountId, List[CorporateActionAllocation]] = Map.empty
  ): IO[FakeCorporateActionAllocationStorage] =
    IO.pure(new FakeCorporateActionAllocationStorage {
      def byAccount(accountId: AccountId): IO[List[CorporateActionAllocation]] =
        IO.pure(allocations.getOrElse(accountId, Nil))
    })
}
