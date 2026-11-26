package st.btu.trading.pillar.models

import com.comcast.ip4s.{Host, Port}

/** An HTTP proxy at `host:port` that tunnels each connection with `CONNECT`. Its host is resolved
  * each time a connection is made.
  */
final case class HttpProxy(host: Host, port: Port)
