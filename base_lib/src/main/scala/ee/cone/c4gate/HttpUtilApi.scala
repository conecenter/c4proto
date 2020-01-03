package ee.cone.c4gate

import okio.ByteString

case class HttpResponse(status: Int, headers: Map[String, List[String]], body: ByteString)

trait HttpUtil {
  def get(url: String, headers: List[(String, String)]): HttpResponse
  def post(url: String, headers: List[(String, String)]): Unit
  def post(url: String, headers: List[(String, String)], body: ByteString, timeOut: Option[Int], expectCode: Int): Unit
  def put(url: String, headers: List[(String, String)], body: ByteString, timeOut: Option[Int]): Int
  def put(url: String, headers: List[(String, String)], body: ByteString): Int
}
object HttpUtil {
  sealed trait HttpMethod {
    def ==: (that: String): Boolean = that == this.toString
  }
  object HttpMethod {
    case object PUT extends HttpMethod
    case object POST extends HttpMethod
    case object GET extends HttpMethod
    case object OPTIONS extends HttpMethod
    case object HEAD extends HttpMethod
    case object PATCH extends HttpMethod
    case object DELETE extends HttpMethod
    case object TRACE extends HttpMethod
    case object CONNECT extends HttpMethod
  }
}
