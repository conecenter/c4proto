package ee.cone.c4http

import ee.cone.c4proto.{Protocol, protocol}

@protocol object HttpProtocol extends Protocol {
  case class POSTRequestValue(headers: List[Header], body: okio.ByteString)
  case class Header(key: String, value: String)
}
