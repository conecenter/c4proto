package ee.cone.c4gate

import java.io.BufferedInputStream
import java.net.{HttpURLConnection, URL}
import java.util.Locale

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.FinallyClose
import ee.cone.c4di.c4
import ee.cone.c4gate.HttpUtil.HttpMethod
import okio.ByteString

import scala.jdk.CollectionConverters.MapHasAsScala
import scala.jdk.CollectionConverters.ListHasAsScala

@c4("HttpUtilApp") class HttpUtilImpl extends HttpUtil with LazyLogging {
  private def withConnection[T](url: String): (HttpURLConnection => T) => T =
    FinallyClose[HttpURLConnection, T](_.disconnect())(
      new URL(url).openConnection().asInstanceOf[HttpURLConnection]
    )
  private def setHeaders(connection: HttpURLConnection, headers: List[(String, String)]): Unit = {
    headers.flatMap(normalizeHeader).foreach { case (k, v) =>
      logger.trace(s"http header $k: $v")
      connection.setRequestProperty(k, v)
    }
  }
  private def normalizeHeader[T](kv: (String, T)): List[(String, T)] = {
    val (k, v) = kv
    Option(k).map(k => (k.toLowerCase(Locale.ENGLISH), v)).toList
  }

  def get(url: String, headers: List[(String, String)]): HttpResponse = {
    logger.debug(s"http get $url")
    // TODO no way to find out that data download ended before fully loaded
    val res = withConnection(url) { conn =>
      setHeaders(conn, headers)
      FinallyClose(new BufferedInputStream(conn.getInputStream)) { is =>
        FinallyClose(new okio.Buffer) { buffer =>
          buffer.readFrom(is)
          val headers = conn.getHeaderFields.asScala.toList
            .flatMap(normalizeHeader).toMap.transform((_,v)=>v.asScala.toList)
          HttpResponse(conn.getResponseCode, headers, buffer.readByteString())
        }
      }
    }
    logger.debug(s"http get done")
    res
  }
  /*
  def post(url: String, headers: List[(String, String)]): Unit = {
    logger.debug(s"http post $url")
    withConnection(url) { conn =>
      conn.setRequestMethod("POST")
      setHeaders(conn, ("content-length", "0") :: headers)
      conn.connect()
      logger.debug(s"http resp status ${conn.getResponseCode}")
      assert(conn.getResponseCode == 200)
    }
    logger.debug(s"http post done")
  }*/

  def genReq(
    method: HttpMethod = HttpMethod.GET,
    url: String = "/",
    headers: List[(String, String)] = Nil,
    body: ByteString = ByteString.EMPTY,
    timeOut: Option[Int] = None,
  ): Int = {
    logger debug s"http $method $url"
    val responseCode = withConnection(url) { conn =>
      conn.setDoOutput(true)
      timeOut.foreach(conn.setConnectTimeout)
      conn.setRequestMethod(method.toString)
      setHeaders(conn, ("content-length", s"${body.size}") :: headers)
        logger debug "connection configured"
        FinallyClose(conn.getOutputStream) { bodyStream =>
          bodyStream.write(body.toByteArray)
          bodyStream.flush()
        }
        logger debug "starting connection"
        conn.connect()
        logger debug s"http resp status ${conn.getResponseCode}"
        conn.getResponseCode
    }
    logger debug s"http $method done"
    responseCode
  }
  def post(url: String, headers: List[(String, String)]): Unit =
    post(url,headers,ByteString.EMPTY,None,200)
  def post(url: String, headers: List[(String, String)], body: ByteString, timeOut: Option[Int], expectCode: Int): Unit = {
    genReq(HttpMethod.POST, url, headers, body, timeOut).ensuring(_ == expectCode)
    ()
  }
  def put(
    url: String,
    headers: List[(String, String)],
    body: ByteString,
    timeOut: Option[Int],
  ): Int = genReq(
    method = HttpMethod.PUT,
    url = url,
    headers = headers,
    body = body,
    timeOut = timeOut,
  )
  def put(
    url: String, headers: List[(String, String)],
    body: ByteString
  ): Int = put(
    url = url,
    headers = headers,
    body = body,
    timeOut = None,
  )
}
