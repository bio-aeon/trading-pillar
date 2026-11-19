package st.btu.trading.pillar.services

import st.btu.trading.pillar.models.binance.{BinanceAsset, BinanceMinute}

// The prices that value what no cash pays for: a trade between two coins, a fee paid in a coin.
// A price is the close of a one-minute bar, found by candidates in order of preference.
private[services] object BinancePrices {

  /** A bar that might value an operation, and the asset a close of it is in: the quote of the pair.
    */
  final case class Candidate(minute: BinanceMinute, quote: BinanceAsset)

  /** The candidate that values an operation, with its close. */
  final case class Found(candidate: Candidate, close: BigDecimal)

  /** The closes known when a record is translated.
    *
    * @param found
    *   the closes stored or fetched
    * @param absent
    *   the bars that were asked for and do not exist, or that a pair Binance does not list could
    *   never have
    */
  final case class PriceBook(
    found: Map[BinanceMinute, BigDecimal] = Map.empty,
    absent: Set[BinanceMinute] = Set.empty
  ) {

    def withClose(minute: BinanceMinute, close: BigDecimal): PriceBook =
      copy(found = found.updated(minute, close))

    def withoutBar(minute: BinanceMinute): PriceBook = copy(absent = absent + minute)

    /** The first of `candidates`, in order, that has a close. A candidate before it that is neither
      * found nor known to be absent is the lookup still to make: a close of another record can be
      * stored for a later candidate while an earlier one exists, and the earlier one comes first.
      */
    def first(candidates: List[Candidate]): Looked[Option[Found]] = {
      val hit =
        candidates.iterator
          .flatMap(candidate => found.get(candidate.minute).map(Found(candidate, _)))
          .nextOption()
      val before = hit.fold(candidates)(found => candidates.takeWhile(_ != found.candidate))
      val pending = before.map(_.minute).find(minute => !absent(minute))
      Looked(hit, candidates.map(_.minute), pending)
    }
  }

  /** A value with the price lookups that went into it, so that the sync can tell which bars a
    * record might need, and which one to ask Binance for next.
    *
    * @param examined
    *   every bar the value looked at, in order
    * @param pending
    *   the first bar that a lookup is still to make for, if any
    */
  final case class Looked[+A](
    value: A,
    examined: List[BinanceMinute],
    pending: Option[BinanceMinute]
  ) {

    def map[B](f: A => B): Looked[B] = copy(value = f(value))

    def flatMap[B](f: A => Looked[B]): Looked[B] = {
      val next = f(value)
      Looked(next.value, examined ++ next.examined, pending.orElse(next.pending))
    }
  }

  object Looked {

    /** A value that needed no price. */
    def pure[A](value: A): Looked[A] = Looked(value, Nil, None)
  }
}
