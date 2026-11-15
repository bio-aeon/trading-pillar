package st.btu.trading.pillar.services

import cats.syntax.traverse.*
import st.btu.trading.pillar.models.domain.AccountOperation
import st.btu.trading.pillar.models.ibkr.*
import st.btu.trading.pillar.services.IbkrCorporateActions.Notice

import java.time.ZoneId

// The one way the rows of an IB account become records: each row goes to the translation of its
// section, and every corporate action leg to the interpretation of its action.
private[services] object IbkrRecords {

  /** The records of the rows, and the notices about how their corporate actions were read. */
  final case class Result(records: List[IbkrRowRecord], notices: List[Notice])

  /** The records of `rows`.
    *
    * @param canceled
    *   the ids of the trades that cancelling rows name, which [[IbkrRowTranslation]] cancels
    * @param parents
    *   rows to look for the parents of spin-offs among, besides `rows` themselves
    * @return
    *   the records, with those of the corporate action legs after the others, or the reason a row
    *   cannot be translated
    */
  def of(
    rows: List[IbkrStatementRow],
    canceled: Set[IbkrTransactionId],
    parents: Iterable[IbkrStatementRow],
    zone: ZoneId
  ): Either[IbkrStatementError, Result] = {
    val (legs, translated) = rows.partitionMap(routed(_, canceled, zone))
    for {
      records <- translated.sequence
      interpreted <- IbkrCorporateActions.interpret(legs, parents ++ rows, zone)
    } yield Result(records ++ interpreted.records, interpreted.notices)
  }

  // A corporate action leg, which is read with the other legs of its action, or the record its
  // section's translation gives a row of any other section.
  private def routed(
    row: IbkrStatementRow,
    canceled: Set[IbkrTransactionId],
    zone: ZoneId
  ): Either[IbkrStatementRow, Either[IbkrStatementError, IbkrRowRecord]] = {
    def recorded(translation: Either[IbkrStatementError, List[AccountOperation]]) =
      Right(translation.map(IbkrRowRecord(row, _)))
    row.section match {
      case IbkrSection.CorporateAction => Left(row)
      case IbkrSection.Trade => recorded(IbkrRowTranslation.trade(row, zone, canceled))
      case IbkrSection.CashTransaction => recorded(IbkrRowTranslation.cashTransaction(row, zone))
      case IbkrSection.Transfer => recorded(IbkrRowTranslation.transfer(row, zone))
    }
  }
}
