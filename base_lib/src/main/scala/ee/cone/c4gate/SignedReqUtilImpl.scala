package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4gate.HttpProtocol._
import ee.cone.c4gate.Time._
import ee.cone.c4di.c4
import ee.cone.c4proto.ToByteString
import okio.ByteString

@c4("SignedReqUtilImplApp") final class SignedReqUtilImpl(
  val catchNonFatal: CatchNonFatal,
  publisher: Publisher,
  txAdd: LTxAdd,
) extends SignedReqUtil {
  def header(headers: List[N_Header], key: String): Option[String] =
    headers.find(_.key == key).map(_.value)
  def signed(headers: List[N_Header]): Option[String] = header(headers,"x-r-signed")
  def respond(succeeded: List[(S_HttpRequest, List[N_Header])], failed: List[(S_HttpRequest, String)]): Context=>Context = {
    val res = succeeded ++ failed.map{ case(req,msg) => req -> List(N_Header("x-r-error-message", msg)) }
    val updates = for {
      (post, headers) <- res
      key <- header(post.headers,"x-r-response-key").toList
      update <- publisher.publish(ByPathHttpPublication(s"/response/$key", headers, ByteString.EMPTY), _+hour)
    } yield update
    val deletes = for {
      (post, headers) <- res
      delete <- LEvent.delete(post)
    } yield delete
    val now =  System.currentTimeMillis
    val respFailUpdates = for {
      (post, msg) <- failed
      update <- LEvent.update(S_HttpResponse(
        post.srcId, 500, N_Header("Content-Type", "text/html; charset=UTF-8") :: Nil, ToByteString(msg), now
      ))
    } yield update
    txAdd.add(updates ++ deletes ++ respFailUpdates)
  }
}
