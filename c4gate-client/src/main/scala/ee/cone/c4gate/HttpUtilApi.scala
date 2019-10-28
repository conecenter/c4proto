package ee.cone.c4gate

import okio.ByteString

case class HttpResponse(status: Int, headers: Map[String, List[String]], body: ByteString)

trait HttpUtil {
  def get(url: String, headers: List[(String, String)]): HttpResponse
  def post(url: String, headers: List[(String, String)]): Unit
  def post(url: String, headers: List[(String, String)], body: ByteString, timeOut: Option[Int], expectCode: Int): Unit
}
