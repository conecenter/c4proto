package ee.cone.c4actor

import java.io.BufferedInputStream
import java.net.{HttpURLConnection, URL}
import java.util.Locale

import com.typesafe.scalalogging.LazyLogging
import okio.ByteString

import scala.collection.JavaConverters.{iterableAsScalaIterableConverter, mapAsScalaMapConverter}

case class HttpResponse(status: Int, headers: Map[String, List[String]], body: ByteString)

object HttpUtil extends LazyLogging {
  private def withConnection[T](url: String): (HttpURLConnection ⇒ T) ⇒ T =
    FinallyClose[HttpURLConnection, T](_.disconnect())(
      new URL(url).openConnection().asInstanceOf[HttpURLConnection]
    )
  private def setHeaders(connection: HttpURLConnection, headers: List[(String, String)]): Unit = {
    headers.flatMap(normalizeHeader).foreach { case (k, v) ⇒
      logger.trace(s"http header $k: $v")
      connection.setRequestProperty(k, v)
    }
  }
  private def normalizeHeader[T](kv: (String, T)): List[(String, T)] = {
    val (k, v) = kv
    Option(k).map(k ⇒ (k.toLowerCase(Locale.ENGLISH), v)).toList
  }

  def get(url: String, headers: List[(String, String)]): HttpResponse = {
    logger.debug(s"http get $url")
    val res = withConnection(url) { conn ⇒
      setHeaders(conn, headers)
      FinallyClose(new BufferedInputStream(conn.getInputStream)) { is ⇒
        FinallyClose(new okio.Buffer) { buffer ⇒
          buffer.readFrom(is)
          val headers = conn.getHeaderFields.asScala.map {
            case (k, l) ⇒ k -> l.asScala.toList
          }.flatMap(normalizeHeader).toMap
          HttpResponse(conn.getResponseCode, headers, buffer.readByteString())
        }
      }
    }
    logger.debug(s"http get done")
    res
  }
  def post(url: String, headers: List[(String, String)]): Unit = {
    logger.debug(s"http post $url")
    withConnection(url) { conn ⇒
      conn.setRequestMethod("POST")
      setHeaders(conn, ("content-length", "0") :: headers)
      conn.connect()
      logger.debug(s"http resp status ${conn.getResponseCode}")
      assert(conn.getResponseCode == 200)
    }
    logger.debug(s"http post done")
  }

  def post(url: String, body: ByteString, mimeType: String, headers: List[(String, String)], timout: Int): Unit = {
    logger.debug(s"http post $url")
    withConnection(url) { conn ⇒
      conn.setConnectTimeout(timout)
      conn.setRequestMethod("POST")
      setHeaders(conn, ("content-type", mimeType) :: ("content-length", s"${body.size}") :: headers)
      FinallyClose(conn.getOutputStream){ bodyStream ⇒
        bodyStream.write(body.toByteArray)
        bodyStream.flush()
      }
      conn.connect()
      logger.debug(s"http resp status ${conn.getResponseCode}")
      assert(conn.getResponseCode == 200)
    }
    logger.debug(s"http post done")
  }

  def authHeaders(signed: String): List[(String, String)] =
    List(("x-r-signed", signed))
  def ok(res: HttpResponse): ByteString = {
    assert(res.status == 200)
    res.body
  }
}
