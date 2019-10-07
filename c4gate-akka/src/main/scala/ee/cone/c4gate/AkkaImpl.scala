
package ee.cone.c4gate

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{ContentType, ContentTypes, HttpEntity, HttpRequest, HttpResponse}
import akka.http.scaladsl.settings.ServerSettings
import akka.stream.{ActorMaterializer, Materializer, OverflowStrategy}
import akka.stream.scaladsl.{Keep, Sink, Source}
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.{Executable, Execution, Observer}
import ee.cone.c4assemble.Single
import ee.cone.c4gate.HttpProtocolBase.N_Header
import ee.cone.c4proto.ToByteString

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}

class AkkaMatImpl(matPromise: Promise[ActorMaterializer] = Promise()) extends AkkaMat with Executable {
  def get: Future[ActorMaterializer] = matPromise.future
  def run(): Unit = {
    val system = ActorSystem.create()
    matPromise.success(ActorMaterializer.create(system))
  }
}

class AkkaHttpServer(
  port: Int, handler: FHttpHandler, execution: Execution, akkaMat: AkkaMat
) extends Executable with LazyLogging {
  def getHandler(mat: Materializer)(implicit ec: ExecutionContext): HttpRequest⇒Future[HttpResponse] = req ⇒ {
    val method = req.method.value
    val path = req.uri.path.toString
    val rHeaders = req.headers.map(h ⇒ N_Header(h.name, h.value)).toList
    logger.debug(s"req init: $method $path")
    logger.trace(s"req headers: $rHeaders")
    for {
      entity ← req.entity.withoutSizeLimit.toStrict(Duration(5,MINUTES))(mat)
      body = ToByteString(entity.getData.toArray)
      rReq = FHttpRequest(method, path, rHeaders, body)
      rResp ← handler.handle(rReq)
    } yield {
      val status = Math.toIntExact(rResp.status)
      val(ctHeaders,rHeaders) = rResp.headers.partition(_.key=="content-type")
      val contentType =
        Single.option(ctHeaders.flatMap(h⇒ContentType.parse(h.value).toOption))
          .getOrElse(ContentTypes.`application/octet-stream`)
      val aHeaders = rHeaders.map(h⇒RawHeader(h.key,h.value))
      val entity = HttpEntity(contentType,rResp.body.toByteArray)
      logger.debug(s"resp status: $status")
      HttpResponse(status, aHeaders, entity)
    }
  }
  def run(): Unit = execution.fatal{ implicit ec ⇒
    for{
      mat ← akkaMat.get
      handler = getHandler(mat)
      // to see: MergeHub/PartitionHub.statefulSink solution of the same task vs FHttpHandler
      binding ← Http()(mat.system).bindAndHandleAsync(
        handler = handler,
        interface = "localhost",
        port = port,
        settings = ServerSettings(
          """akka.http.server.request-timeout = 80 s
            |akka.http.server.parsing.max-content-length = infinite
            |""".stripMargin)
        //defapply(configOverrides: String): ServerSettings(system)//ServerSettings(system)
      )(mat)
    } yield binding
  }
}

class AkkaStatefulReceiver[Message](ref: ActorRef) extends StatefulReceiver[Message] {
  def send(message: Message): Unit = ref ! message
}

class AkkaStatefulReceiverFactory(execution: Execution, akkaMat: AkkaMat) extends StatefulReceiverFactory {
  def create[Message](inner: List[Observer[Message]])(implicit executionContext: ExecutionContext): Future[StatefulReceiver[Message]] =
    for {
      mat ← akkaMat.get
      source = Source.actorRef[Message](100, OverflowStrategy.fail)
      sink = Sink.fold(inner)((st, msg: Message) ⇒ st.flatMap(_.activate(msg)))
      (actorRef,resF) = source.toMat(sink)(Keep.both).run()(mat)
    } yield {
      execution.fatal(_ ⇒ resF)
      new AkkaStatefulReceiver[Message](actorRef)
    }
}


//execution: Execution,
//implicit val ec: ExecutionContextExecutor = system.dispatcher
// List[RunnableGraph[Future[_]]]
//val a: Source[String, NotUsed] = Source(Stream.from(1)).delay(1.second).fold("")((s,i)⇒s"$s-$i")
//Source setup fromFutureSource tick