package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4gate.HttpProtocol._
import ee.cone.c4gate.Time._
import okio.ByteString

class SignedPostUtilImpl(val catchNonFatal: CatchNonFatal) extends SignedPostUtil {
  def header(headers: List[N_Header], key: String): Option[String] =
    headers.find(_.key == key).map(_.value)
  def signed(headers: List[N_Header]): Option[String] = header(headers,"X-r-signed")
  def respond(succeeded: List[(S_HttpPost, List[N_Header])], failed: List[(S_HttpPost, String)]): Context⇒Context = {
    val res = succeeded ++ failed.map{ case(post,msg) ⇒ post → List(N_Header("X-r-error-message", msg)) }
    val updates = for {
      (post, headers) ← res
      key ← header(post.headers,"X-r-response-key").toList
      update ← LEvent.update(S_HttpPublication(s"/response/$key", headers, ByteString.EMPTY, Option(now + hour)))
    } yield update
    val deletes = for {
      (post, headers) ← res
      delete ← LEvent.delete(post)
    } yield delete
    TxAdd(updates ++ deletes)
  }
}
