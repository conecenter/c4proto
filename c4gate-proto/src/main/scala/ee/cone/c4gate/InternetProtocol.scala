
package ee.cone.c4gate

import ee.cone.c4proto.{Id, Protocol, protocol}

@protocol object HttpProtocol extends Protocol {
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
  case class Header(@Id(0x0024) key: String, @Id(0x0025) value: String)
}

@protocol object TcpProtocol extends Protocol {
  @Id(0x0026) case class TcpWrite(
    @Id(0x002A) srcId: String,
    @Id(0x0027) connectionKey: String,
    @Id(0x0023) body: okio.ByteString,
    @Id(0x002B) priority: Long
  )
  @Id(0x0028) case class TcpConnection(@Id(0x0027) connectionKey: String)
  @Id(0x0029) case class TcpDisconnect(@Id(0x0027) connectionKey: String)
  //0x002E 0x002F
}

@protocol object AlienProtocol extends Protocol {
  @Id(0x0030) case class ToAlienWrite(
    @Id(0x0031) srcId: String,
    @Id(0x0032) sessionKey: String,
    @Id(0x0033) event: String,
    @Id(0x0034) data: String,
    @Id(0x0035) priority: Long
  )
  @Id(0x0036) case class FromAlienState(
    @Id(0x0032) sessionKey: String,
    @Id(0x0037) location: String,
    @Id(0x0039) connectionKey: String, // we need to affect branchKey
    @Id(0x0038) lastPongSecond: Long, //do we need to affect branchKey by this?
    @Id(0x003A) userName: Option[String]
  )
  //3B
}

@protocol object AuthProtocol extends Protocol {
  case class SecureHash(
    @Id(0x0050) iterations: Int,
    @Id(0x0051) hashSizeInBytes: Int,
    @Id(0x0052) salt: okio.ByteString,
    @Id(0x0053) hash: okio.ByteString
  )
  @Id(0x0054) case class PasswordChangeRequest(
    @Id(0x0055) srcId: String,
    @Id(0x0056) hash: Option[SecureHash]
  )
  @Id(0x0057) case class PasswordHashOfUser(
    @Id(0x0058) userName: String,
    @Id(0x0056) hash: Option[SecureHash]
  )
  /*
  @Id(0x0059) case class PasswordVerifiedRequest(
    @Id(0x0055) srcId: String,
    @Id(0x0058) userName: String
  )*/


  @Id(0x0059) case class AuthenticatedSession(
    @Id(0x005A) sessionKey: String,
    @Id(0x0058) userName: String
  )
}
