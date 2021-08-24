package su.wps.trading.pillar.security

import cats.effect.Sync
import org.bouncycastle.jce.provider.BouncyCastleProvider

import java.security.Security

trait Bouncy

object Bouncy {
  def create[F[_]](implicit F: Sync[F]): F[Bouncy] =
    F.delay {
      if (Security.getProvider("BC") == null)
        Security.addProvider(new BouncyCastleProvider())
      new Bouncy {}
    }
}
