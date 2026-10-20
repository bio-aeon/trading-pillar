package st.btu.trading.pillar.data

import cats.Functor
import cats.syntax.functor.*
import tofu.generate.GenUUID

/** Trace ids for [[ProcessContext.traceId]]: a random UUID rendered as a plain string, the form the
  * library's processes generate for every iteration. A consumer that runs work of its own in a
  * fresh context uses it so its log entries carry ids of the same form.
  */
object TraceIds {

  /** A fresh trace id. */
  def next[F[_]: Functor: GenUUID]: F[String] = GenUUID[F].randomUUID.map(_.toString)
}
