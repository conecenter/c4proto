
package ee.cone.c4gate

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{ContentType, ContentTypes, HttpEntity, HttpRequest, HttpResponse}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Flow
import ee.cone.c4actor.Executable
import ee.cone.c4assemble.Single
import ee.cone.c4gate.HttpProtocolBase.N_Header
import ee.cone.c4proto.ToByteString

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor}

class AkkaHttpServer(port: Int, handler: RHttpHandler) extends Executable {
  def run(): Unit = {
    implicit val system: ActorSystem = ActorSystem.create()
    val mat: ActorMaterializer = ActorMaterializer.create(system)
    implicit val executionContext: ExecutionContextExecutor = system.dispatcher
    val reqFlow: Flow[HttpRequest, HttpResponse, NotUsed] =
      Flow[HttpRequest].mapAsync(1){ req ⇒
        val method = req.method.value
        val path = req.uri.path.toString
        val rHeaders = req.headers.map(h ⇒ N_Header(h.name, h.value)).toList
        for {
          entity ← req.entity.toStrict(Duration(5,MINUTES))(mat)
          body = ToByteString(entity.getData.toArray)
          rReq = RHttpRequest(method, path, rHeaders, body)
          rResp ← handler.handle(rReq)
        } yield {
          val status = Math.toIntExact(rResp.status)
          val(ctHeaders,rHeaders) = rResp.headers.partition(_.key=="Content-Type")
          val contentType =
            Single.option(ctHeaders.flatMap(h⇒ContentType.parse(h.value).toOption))
              .getOrElse(ContentTypes.`application/octet-stream`)
          val aHeaders = rHeaders.map(h⇒RawHeader(h.key,h.value))
          val entity = HttpEntity(contentType,rResp.body.toByteArray)
          HttpResponse(status, aHeaders, entity)
        }
      }
    val done = Http().bind(interface = "localhost", port = port)
      .runForeach(conn⇒conn.handleWith(reqFlow)(mat))(mat)
    Await.result(done, Duration.Inf)
  }
}