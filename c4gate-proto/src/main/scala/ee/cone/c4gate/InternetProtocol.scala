
package ee.cone.c4gate

import ee.cone.c4proto._

@protocol(ExchangeCat) object HttpProtocolBase   {
  @Id(0x002C) case class S_HttpPublication(
    @Id(0x0021) path: String,
    @Id(0x0022) headers: List[N_Header],
    @Id(0x0023) body: okio.ByteString,
    @Id(0x002E) until: Option[Long]
  )
  @Id(0x0020) case class S_HttpPost(
    @Id(0x002A) srcId: String,
    @Id(0x0021) path: String,
    @Id(0x0022) headers: List[N_Header],
    @Id(0x0023) body: okio.ByteString,
    @Id(0x002D) time: Long
  )

  @Cat(InnerCat)
  case class N_Header(@Id(0x0024) key: String, @Id(0x0025) value: String)
}

@protocol(ExchangeCat) object TcpProtocolBase   {
  @Id(0x0026) case class S_TcpWrite(
    @Id(0x002A) srcId: String,
    @Id(0x0027) connectionKey: String,
    @Id(0x0023) body: okio.ByteString,
    @Id(0x002B) priority: Long
  )
  @Id(0x0028) case class S_TcpConnection(@Id(0x0027) connectionKey: String)
  @Id(0x0029) case class S_TcpDisconnect(@Id(0x0027) connectionKey: String)
  //0x002F
}

case object AlienCat extends DataCategory

@protocol object AlienProtocolBase   {
  @Cat(AlienCat)
  @Id(0x0030) case class ToAlienWrite(
    @Id(0x0031) srcId: String,
    @Id(0x0032) sessionKey: String,
    @Id(0x0033) event: String,
    @Id(0x0034) data: String,
    @Id(0x0035) priority: Long
  )

  @Cat(AlienCat)
  @Id(0x0036) case class FromAlienState(
    @Id(0x0032) sessionKey: String,
    @Id(0x0037) location: String,
    @Id(0x0039) connectionKey: String, // we need to affect branchKey
    @Id(0x003A) userName: Option[String]
  )

  @Cat(ExchangeCat)
  @Id(0x003B) case class PostConsumer(
    @Id(0x003C) srcId: String,
    @Id(0x003D) consumer: String,
    @Id(0x003E) condition: String
  )

  @Cat(AlienCat)
  @Id(0x003F) case class FromAlienStatus(
    @Id(0x0032) sessionKey: String,
    @Id(0x0038) expirationSecond: Long,
    @Id(0x005C) isOnline: Boolean
  )
}

case object AuthOrigCat extends DataCategory

@protocol(AuthOrigCat) object AuthProtocolBase   {

  @Cat(InnerCat)
  case class N_SecureHash(
    @Id(0x0050) iterations: Int,
    @Id(0x0051) hashSizeInBytes: Int,
    @Id(0x0052) salt: okio.ByteString,
    @Id(0x0053) hash: okio.ByteString
  )
  @Id(0x0054) case class S_PasswordChangeRequest(
    @Id(0x0055) srcId: String,
    @Id(0x0056) hash: Option[N_SecureHash]
  )
  @Id(0x0057) case class C_PasswordHashOfUser(
    @Id(0x0058) userName: String,
    @Id(0x0056) hash: Option[N_SecureHash]
  )
  /*
  @Id(0x0059) case class PasswordVerifiedRequest(
    @Id(0x0055) srcId: String,
    @Id(0x0058) userName: String
  )*/


  @Id(0x0059) case class U_AuthenticatedSession(
    @Id(0x005A) sessionKey: String,
    @Id(0x0058) userName: String,
    @Id(0x005B) untilSecond: Long
  )
}
