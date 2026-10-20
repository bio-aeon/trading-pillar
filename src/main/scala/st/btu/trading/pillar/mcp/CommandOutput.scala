package st.btu.trading.pillar.mcp

import ch.linkyard.mcp.protocol.Content
import st.btu.trading.pillar.gateways.CapturedReply

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import scala.util.Try

// Turns what the command handlers replied into tool output: the texts as one text block, a
// document as its name and, when it is text, its content, and a photo as an image.
private[mcp] object CommandOutput {

  // A document longer than this is cut, so a large export cannot swamp the caller's context.
  val MaxDocumentChars = 20000

  val NoReply = "The command produced no reply."

  private val Paragraph = "\n\n"
  private val PngMimeType = "image/png"

  def render(replies: List[CapturedReply]): List[Content] = {
    val texts = replies.flatMap(textOf)
    val images = replies.collect { case CapturedReply.Photo(content, _) =>
      Content.Image(content, PngMimeType)
    }
    if (texts.isEmpty && images.isEmpty) List(Content.Text(NoReply))
    else Option.when(texts.nonEmpty)(Content.Text(texts.mkString(Paragraph))).toList ++ images
  }

  private def textOf(reply: CapturedReply): Option[String] =
    reply match {
      case CapturedReply.Text(text) => Some(text)
      case CapturedReply.TextWithKeyboard(text, rows) =>
        val labels = rows.flatten
        Some(if (labels.isEmpty) text else s"$text\nButtons: ${labels.mkString(", ")}")
      case CapturedReply.Document(fileName, content, caption) =>
        Some((caption.toList :+ describeDocument(fileName, content)).mkString("\n"))
      case CapturedReply.Photo(_, caption) => caption
    }

  private def describeDocument(fileName: String, content: Array[Byte]): String =
    utf8Text(content) match {
      case Some(text) if text.length <= MaxDocumentChars => s"File $fileName:\n$text"
      case Some(text) =>
        s"File $fileName, first $MaxDocumentChars of ${text.length} characters:\n" +
          text.take(MaxDocumentChars)
      case None => s"File $fileName: binary content, ${content.length} bytes, not shown"
    }

  // Text only when the bytes are well-formed UTF-8 without NUL characters, which no text file the
  // bot sends contains.
  private def utf8Text(content: Array[Byte]): Option[String] =
    Try(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(content)).toString).toOption
      .filterNot(_.contains('\u0000'))
}
