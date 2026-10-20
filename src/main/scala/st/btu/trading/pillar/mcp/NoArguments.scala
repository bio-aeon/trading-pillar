package st.btu.trading.pillar.mcp

import com.melvinlow.json.schema.JsonSchemaEncoder
import com.melvinlow.json.schema.generic.{auto, semiauto}
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder

import semiauto.given

/** The input of a tool that takes no arguments: its schema has no properties, and a call with no
  * arguments decodes into it.
  */
final case class NoArguments()

object NoArguments {
  implicit val schema: JsonSchemaEncoder[NoArguments] = auto.derived
  implicit val decoder: Decoder[NoArguments] = deriveDecoder
}
