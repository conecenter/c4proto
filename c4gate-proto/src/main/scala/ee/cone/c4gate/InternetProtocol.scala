
package ee.cone.c4gate

import ee.cone.c4proto.{Id, Protocol, protocol}

@protocol object InternetProtocol extends Protocol {
  @Id(0x002C) case class HttpPublication(
    @Id(0x0021) path: String,
    @Id(0x0022) headers: List[Header],
    @Id(0x0023) body: okio.ByteString
  )
  @Id(0x0020) case class HttpPost(
    @Id(0x002A) srcId: String,
    @Id(0x0021) path: String,
    @Id(0x0022) headers: List[Header],
    @Id(0x0023) body: okio.ByteString,
    @Id(0x002D) time: Long
  )
  // 2F
  case class Header(@Id(0x0024) key: String, @Id(0x0025) value: String)

  @Id(0x0026) case class TcpWrite(
    @Id(0x002A) srcId: String,
    @Id(0x0027) connectionKey: String,
    @Id(0x0023) body: okio.ByteString,
    @Id(0x002B) priority: Long
  )
  @Id(0x0028) case class TcpConnection(@Id(0x0027) connectionKey: String)
  @Id(0x0029) case class TcpDisconnect(@Id(0x0027) connectionKey: String)
  @Id(0x002E) case class AppLevelInitDone(@Id(0x0027) connectionKey: String)

}
