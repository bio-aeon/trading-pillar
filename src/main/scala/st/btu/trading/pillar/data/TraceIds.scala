package st.btu.trading.pillar.data

import cats.Functor
import cats.syntax.functor.*
import tofu.generate.GenUUID

// A fresh trace id for ProcessContext.traceId: a random UUID rendered as a plain String, the form
// every self-restarting process generates one per iteration.
private[pillar] object TraceIds {

  def next[F[_]: Functor: GenUUID]: F[String] = GenUUID[F].randomUUID.map(_.toString)
}
