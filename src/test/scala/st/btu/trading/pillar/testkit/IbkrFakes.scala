package st.btu.trading.pillar.testkit

import cats.effect.{IO, Ref}
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import st.btu.trading.pillar.gateways.ibkr.FlexWebService
import st.btu.trading.pillar.models.domain.*
import st.btu.trading.pillar.models.ibkr.*
import st.btu.trading.pillar.storages.{AccountOperationStorage, IbkrStatementStorage}

import java.time.{Instant, LocalDate}

object IbkrFakes {

  // Also the read port of the shared operations, over the translations the records carried.
  trait FakeIbkrStatementStorage extends IbkrStatementStorage[IO] with AccountOperationStorage[IO] {

    /** How many records each `record` call carried, oldest first. */
    def recordedBatchSizes: IO[List[Int]]

    /** Every stored row of every account. */
    def storedRows: IO[List[IbkrStatementRow]]
  }

  // `record` upserts as the contract says: a row by (account, section, transaction id), and the
  // translations of the row as a whole.
  def statementStorage(
    rows: List[IbkrStatementRow] = List.empty,
    translations: List[AccountOperation] = List.empty
  ): IO[FakeIbkrStatementStorage] =
    for {
      rowsRef <- Ref.of[IO, List[IbkrStatementRow]](rows)
      translationsRef <- Ref.of[IO, List[AccountOperation]](translations)
      batchSizesRef <- Ref.of[IO, List[Int]](List.empty)
    } yield new FakeIbkrStatementStorage {
      def record(records: List[IbkrRowRecord]): IO[Unit] =
        batchSizesRef.update(_ :+ records.size) >>
          rowsRef.update(records.map(_.row).foldLeft(_)(upsertRow)) >>
          translationsRef.update(records.foldLeft(_)(replaceTranslations))

      def latestReportDate(accountId: AccountId): IO[Option[LocalDate]] =
        rowsRef.get.map(
          _.filter(_.accountId == accountId).map(_.reportDate).maxByOption(_.toEpochDay)
        )

      def rows(accountId: AccountId): IO[Vector[IbkrStatementRow]] =
        rowsRef.get.map(_.filter(_.accountId == accountId).toVector)

      def rowsByTransactionIds(
        accountId: AccountId,
        section: IbkrSection,
        ids: Set[IbkrTransactionId]
      ): IO[Vector[IbkrStatementRow]] =
        rowsRef.get.map(
          _.filter(row =>
            row.accountId == accountId && row.section == section && ids.contains(row.transactionId)
          ).toVector
        )

      def operations(
        accountId: AccountId,
        since: Instant,
        until: Instant
      ): IO[Vector[AccountOperation]] =
        translationsRef.get.map(_.filter { op =>
          op.accountId == accountId && !op.occurredAt.isBefore(since) &&
          op.occurredAt.isBefore(until)
        }.sortBy(op => (op.occurredAt, op.ref.value)).toVector)

      def recordedBatchSizes: IO[List[Int]] = batchSizesRef.get

      def storedRows: IO[List[IbkrStatementRow]] = rowsRef.get
    }

  private def upsertRow(
    stored: List[IbkrStatementRow],
    row: IbkrStatementRow
  ): List[IbkrStatementRow] = {
    val at = stored.indexWhere(s =>
      s.accountId == row.accountId && s.section == row.section &&
        s.transactionId == row.transactionId
    )
    if (at < 0) stored :+ row else stored.updated(at, row)
  }

  // The translations of the record's row that the record does not carry are removed, and those it
  // carries are stored by (account, ref).
  private def replaceTranslations(
    stored: List[AccountOperation],
    record: IbkrRowRecord
  ): List[AccountOperation] = {
    val row = record.row
    val kept = stored.filterNot(op =>
      op.accountId == row.accountId && row.owns(op.ref) && !record.translations.exists(
        _.ref == op.ref
      )
    )
    record.translations.foldLeft(kept)(upsertTranslation)
  }

  private def upsertTranslation(
    stored: List[AccountOperation],
    translation: AccountOperation
  ): List[AccountOperation] = {
    val at =
      stored.indexWhere(s => s.accountId == translation.accountId && s.ref == translation.ref)
    if (at < 0) stored :+ translation else stored.updated(at, translation)
  }

  final case class FlexRequest(queryId: FlexQueryId, from: LocalDate, to: LocalDate)

  trait FakeFlexWebService extends FlexWebService[IO] {

    /** The statements asked for, oldest first. */
    def requests: IO[List[FlexRequest]]
  }

  // Answers every request with what `respond` makes of it.
  def flexWebService(respond: FlexRequest => IO[String]): IO[FakeFlexWebService] =
    Ref.of[IO, List[FlexRequest]](List.empty).map { requestsRef =>
      new FakeFlexWebService {
        def statement(queryId: FlexQueryId, from: LocalDate, to: LocalDate): IO[String] = {
          val request = FlexRequest(queryId, from, to)
          requestsRef.update(_ :+ request) >> respond(request)
        }

        def requests: IO[List[FlexRequest]] = requestsRef.get
      }
    }

  /** The key of an instrument at Interactive Brokers, whose ids are contract ids. */
  def instrumentKey(conid: String): InstrumentKey =
    InstrumentKey(Broker.Ibkr, BrokerInstrumentId(conid))

  /** A stored statement row, its attributes given as pairs. */
  def row(
    accountId: AccountId,
    section: IbkrSection,
    transactionId: String,
    reportDate: LocalDate,
    attributes: (String, String)*
  ): IbkrStatementRow =
    IbkrStatementRow(
      accountId,
      section,
      IbkrTransactionId(transactionId),
      reportDate,
      attributes.toMap
    )

  /** An XML element with the given attributes, closed on itself. */
  def element(name: String, attributes: (String, String)*): String =
    attributes.map { case (key, value) => s"""$key="$value"""" }.mkString(s"<$name ", " ", "/>")

  /** The response of an Activity Flex Query that holds one statement of `ibkrAccountId`, with
    * `elements` in it.
    */
  def statementXml(ibkrAccountId: String, from: String, to: String, elements: String*): String =
    s"""<FlexQueryResponse queryName="Activity" type="AF"><FlexStatements count="1">""" +
      s"""<FlexStatement accountId="$ibkrAccountId" fromDate="$from" toDate="$to">""" +
      s"${elements.mkString}</FlexStatement></FlexStatements></FlexQueryResponse>"
}
