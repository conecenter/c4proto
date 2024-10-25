
package ee.cone.c4gate

import ee.cone.c4actor.{SnapshotPatchIgnore, _}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4di.{c4, provide}
import ee.cone.c4gate.AlienProtocol._
import ee.cone.c4gate.AuthProtocol.U_AuthenticatedSession
import ee.cone.c4gate.HttpProtocol._
import ee.cone.c4proto._

@protocol("HttpProtocolApp") object HttpProtocol   {
  @Id(0x002C) case class S_HttpPublicationV1(
    @Id(0x0021) path: String
  )
  @Id(0x0092) case class S_HttpPublicationV2(
    @Id(0x002A) srcId: String,
    @Id(0x0021) path: String,
    @Id(0x0022) headers: List[N_Header],
    @Id(0x0023) body: okio.ByteString,
    @Id(0x002D) time: Long,
  )
  @Id(0x0093) case class S_Manifest(
    @Id(0x002A) srcId: String,
    @Id(0x002C) paths: List[String],
    @Id(0x002E) until: Long
  )
  @Id(0x0020) case class S_HttpRequest(
    @Id(0x002A) srcId: String,
    @Id(0x002F) method: String,
    @Id(0x0021) path: String,
    @Id(0x36af) rawQueryString: Option[String],
    @Id(0x0022) headers: List[N_Header],
    @Id(0x0023) body: okio.ByteString,
    @Id(0x002D) time: Long
  )

  case class N_Header(@Id(0x0024) key: String, @Id(0x0025) value: String)

  /*
  PostConsumer is not perfect -- we may have zombie PostConsumer-s;
  it is ok for dos-protection, but it is not acceptable for ResponseOptions, as they will conflict;
  making ResponseOptions ByPath is not perfect -- we will have potential orig ownership conflict (and non-stop updating);
  simple solution is to check/update ResponseOptions only on service startup
   */
//  @Id(0x002F) case class E_ResponseOptionsByPath(...)

  @Id(0x0090) case class S_HttpResponse(
    @Id(0x002A) srcId: String, //note: make srcId random and add requestId to detect consumer conflict
    @Id(0x0091) status: Int,
    @Id(0x0022) headers: List[N_Header],
    @Id(0x0023) body: okio.ByteString,
    @Id(0x002D) time: Long
  )
}

// WAS classes: 0x0026 0x0028 0x0029 ?0x002F 0x0030 0x0037 0x00B3

@protocol("AlienProtocolApp") object AlienProtocol   {

  @Id(0x0036) case class U_FromAlienState(
    @Id(0x0032) sessionKey: String,
    @Id(0x0037) location: String,
    // @Id(0x0036) reloadKey: String, // we need to affect branchKey
    @deprecated @Id(0x003A) userName: Option[String]
  )

  @Id(0x003B) case class E_HttpConsumer(
    @Id(0x003C) srcId: String,
    @Id(0x003D) consumer: String,
    @Id(0x003E) condition: String,
    //isSynchronous: Boolean
  )

  @Id(0x003F) case class U_FromAlienStatus(
    @Id(0x0032) sessionKey: String,
    @Id(0x0038) expirationSecond: Long,
    @Id(0x005C) isOnline: Boolean
  )

  @Id(0x0032) case class U_ToAlienAck(
    @Id(0x0032) sessionKey: String,
    @Id(0x0030) values: List[N_Header]
  )
}

@protocol("AuthProtocolApp") object AuthProtocol   {

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

  @Id(0x005E) case class C_PasswordRequirements(
    @Id(0x005D) srcId: String,
    @Id(0x005F) regex: String
  )
  /*
  @Id(0x0059) case class PasswordVerifiedRequest(
    @Id(0x0055) srcId: String,
    @Id(0x0058) userName: String
  )*/


  @Id(0x0059) case class U_AuthenticatedSession(
    @Id(0x005A) sessionKey: String,
    @Id(0x0058) userName: String,
    @Id(0x005B) untilSecond: Long,
    @Id(0x0022) headers: List[N_Header],
    @Id(0x0020) logKey: String,
  )
}

@c4("HttpProtocolApp") final class HttpSnapshotPatchIgnores {
  @provide def get: Seq[GeneralSnapshotPatchIgnore] = Seq(
    classOf[S_HttpRequest],
    //classOf[S_HttpPublicationV2], classOf[S_Manifest],
  ).map(new SnapshotPatchIgnore(_))
}
