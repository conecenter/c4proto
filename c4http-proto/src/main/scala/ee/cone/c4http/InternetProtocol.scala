package ee.cone.c4http

import ee.cone.c4proto.{Id, Protocol, protocol}

@protocol object InternetProtocol extends Protocol {
  @Id(0x0020) case class HttpRequestValue(@Id(0x0021) path: String, @Id(0x0022) headers: List[Header], @Id(0x0023) body: okio.ByteString)
  case class Header(@Id(0x0024) key: String, @Id(0x0025) value: String)
  @Id(0x002B) case class ForwardingConf(@Id(0x002C) actorName: String, @Id(0x002D) rules: List[ForwardingRule])
  case class ForwardingRule(@Id(0x0021) path: String)
  @Id(0x0026) case class TcpWrite(@Id(0x0027) connectionKey: String, @Id(0x0023) body: okio.ByteString)
  @Id(0x0028) case class TcpStatus(@Id(0x0027) connectionKey: String, @Id(0x0029) error: String)
  @Id(0x002A) case class TcpDisconnect(@Id(0x0027) connectionKey: String)
}
