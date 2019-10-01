package ee.cone.c4actor.tests

import java.io.BufferedInputStream
import java.net.{HttpURLConnection, URL}
import java.util.Locale

import ee.cone.c4actor._

import scala.collection.JavaConverters.{iterableAsScalaIterableConverter, mapAsScalaMapConverter}

//C4STATE_TOPIC_PREFIX=ee.cone.c4actor.tests.SnapshotGetterApp sbt ~'c4actor-extra-examples/runMain ee.cone.c4actor.ServerMain'

class SnapshotGetterTest(execution: Execution) extends Executable {
  private def withConnection[T](url: String): (HttpURLConnection ⇒ T) ⇒ T =
    FinallyClose[HttpURLConnection, T](_.disconnect())(
      new URL(url).openConnection().asInstanceOf[HttpURLConnection]
    )
  private def setHeaders(connection: HttpURLConnection, headers: List[(String, String)]): Unit = {
    headers.flatMap(normalizeHeader).foreach { case (k, v) ⇒
      connection.setRequestProperty(k, v)
    }
  }
  private def normalizeHeader[T](kv: (String, T)): List[(String, T)] = {
    val (k, v) = kv
    Option(k).map(k ⇒ (k.toLowerCase(Locale.ENGLISH), v)).toList
  }

  def get(url: String): (String, Long, Long, Map[String, List[String]]) = {
    val time = System.currentTimeMillis()
    val res: HttpResponse = withConnection(url) { conn ⇒
      setHeaders(conn, Nil)
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
    val took = System.currentTimeMillis() - time
    (SnapshotUtilImpl.hashFromData(res.body.toByteArray), res.body.size(), took, res.headers)
  }


  def run(): Unit = {
    val url = "http://10.20.2.216:10580/snapshots/000000000081c0e5-92b87c05-294d-3c1d-b443-fb83bdc71d20-c-lz4"
    for {i ← 1 to 5} yield {
      println(get(url))
    }
    execution.complete()
  }
}

class SnapshotGetterApp
  extends ToStartApp
    with VMExecutionApp
    with ProtocolsApp
    with ExecutableApp
    with RichDataApp
    with EnvConfigApp {

  override def toStart: List[Executable] = new SnapshotGetterTest(execution) :: super.toStart
  def assembleProfiler: AssembleProfiler = NoAssembleProfiler
}
