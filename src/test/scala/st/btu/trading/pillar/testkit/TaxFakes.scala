package st.btu.trading.pillar.testkit

import cats.effect.IO
import st.btu.trading.pillar.models.domain.AccountId
import st.btu.trading.pillar.models.market.Ticker
import st.btu.trading.pillar.tax.{AnnualTaxReport, Lot, TaxConfig, TaxService}

object TaxFakes {

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
}
