package ee.cone.c4http

import ee.cone.c4proto.{Id, Protocol, protocol}

@protocol object HttpProtocol extends Protocol {
  @Id(0x0020) case class RequestValue(@Id(0x0021) path: String, @Id(0x0022) headers: List[Header], @Id(0x0023) body: okio.ByteString)
  case class Header(@Id(0x0024) key: String, @Id(0x0025) value: String)
}

@protocol object TcpProtocol extends Protocol {
  @Id(0x0026) case class WriteEvent(@Id(0x0027) connectionKey: String, @Id(0x0023) body: okio.ByteString)
  @Id(0x0028) case class Status(@Id(0x0027) connectionKey: String, @Id(0x0029) error: String)
  @Id(0x002A) case class DisconnectEvent(@Id(0x0027) connectionKey: String)
}
