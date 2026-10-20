package st.btu.trading.pillar.gateways

/** One thing a command handler sent to a chat, as [[CapturingTgGateway]] recorded it.
  *
  * The `Array[Byte]` contents compare by reference, as arrays do.
  */
sealed trait CapturedReply

object CapturedReply {

  /** A plain text message. */
  final case class Text(text: String) extends CapturedReply

  /** A text message with an inline keyboard.
    *
    * @param rows
    *   the labels of the keyboard's buttons, top to bottom and left to right within a row
    */
  final case class TextWithKeyboard(text: String, rows: List[List[String]]) extends CapturedReply

  /** A file attachment. */
  final case class Document(fileName: String, content: Array[Byte], caption: Option[String])
      extends CapturedReply

  /** A PNG image. */
  final case class Photo(content: Array[Byte], caption: Option[String]) extends CapturedReply
}
