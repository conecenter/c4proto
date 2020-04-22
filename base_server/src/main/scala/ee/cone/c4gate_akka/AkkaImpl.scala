
package ee.cone.c4gate_akka

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.{Http, HttpExt}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{RawHeader, `Content-Type`}
import akka.http.scaladsl.settings.ServerSettings
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{ActorMaterializer, Materializer, OverflowStrategy}
import com.typesafe.scalalogging.LazyLogging
import com.typesafe.config.ConfigFactory
import ee.cone.c4actor.{Config, Early, Executable, Execution, Observer}
import ee.cone.c4assemble.Single
import ee.cone.c4di.c4
import ee.cone.c4gate.HttpProtocol.N_Header
import ee.cone.c4gate_server._
import ee.cone.c4proto.ToByteString

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal

@c4("AkkaMatApp") final class AkkaMatImpl(configs: List[AkkaConf], execution: Execution, matPromise: Promise[ActorMaterializer] = Promise()) extends AkkaMat with Executable with Early {
  def get: Future[ActorMaterializer] = matPromise.future
  def run(): Unit = {
    val config = ConfigFactory.parseString(configs.map(_.content).sorted.mkString("\n"))
    val system = ActorSystem.create("default",config)
    execution.success(matPromise, ActorMaterializer.create(system))
  }
}

@c4("AkkaGatewayApp") final class AkkaHttpServerConf extends AkkaConf {
  def content: String = List(
    "akka.http.server.parsing.max-content-length = infinite",
    //"akka.http.server.parsing.max-to-strict-bytes = infinite",
    "akka.http.client.parsing.max-content-length = infinite",
    "akka.http.server.request-timeout = 600 s",
    "akka.http.client.request-timeout = 600 s",
    "akka.http.parsing.max-to-strict-bytes = infinite",
    "akka.http.server.raw-request-uri-header = on",
  ).mkString("\n")
}

@c4("AkkaGatewayApp") final class AkkaHttpServer(
  config: Config, handler: FHttpHandler, execution: Execution, akkaMat: AkkaMat,
  requestPreHandler: AkkaRequestPreHandler
)(
  port: Int = config.get("C4HTTP_PORT").toInt,
) extends Executable with Early with LazyLogging {
  def getHandler(mat: Materializer, http: HttpExt)(implicit ec: ExecutionContext): HttpRequest => Future[HttpResponse] = req => {
    val method = req.method.value
    val path = req.uri.path.toString
    val rHeaders = (`Content-Type`(req.entity.contentType) :: req.headers.toList)
      .map(h => N_Header(h.name, h.value))
    logger.debug(s"req init: $method $path")
    logger.trace(s"req headers: $rHeaders")
    (for {
      request <- requestPreHandler.handleAsync(req)
      entity <- request.entity.toStrict(Duration(5, MINUTES))(mat)
      body = ToByteString(entity.getData.toArray)
      rReq = FHttpRequest(method, path, rHeaders, body)
      rResp <- handler.handle(rReq)
      response <- {
        val status = Math.toIntExact(rResp.status)
        val(ctHeaders,rHeaders) = rResp.headers.partition(_.key=="content-type")
        val contentType =
          Single.option(ctHeaders.flatMap(h=>ContentType.parse(h.value).toOption))
            .getOrElse(ContentTypes.`application/octet-stream`)
        val aHeaders = rHeaders.map(h=>RawHeader(h.key,h.value))
        val entity = HttpEntity(contentType,rResp.body.toByteArray)
        logger.debug(s"resp status: $status")
        val response = HttpResponse(status, aHeaders, entity)
        val redirectUriOpt =
          Single.option(response.headers.filter(_.name == "x-r-redirect-inner").map(_.value))
        redirectUriOpt.fold(Future.successful(response)){ uri:String =>
          logger debug s"Redirecting to $uri"
          http.singleRequest(HttpRequest(uri = uri))
        }
      }
    } yield response).recover{ case NonFatal(e) =>
        logger.error("http-handler",e)
        throw e
    }
  }
  def run(): Unit = execution.fatal{ implicit ec =>
    for{
      mat <- akkaMat.get
      http: HttpExt = Http()(mat.system)
      handler = getHandler(mat,http)
      // to see: MergeHub/PartitionHub.statefulSink solution of the same task vs FHttpHandler
      binding <- http.bindAndHandleAsync(
        handler = handler,
        interface = "0.0.0.0",
        port = port,
        settings = ServerSettings(mat.system)
        //defapply(configOverrides: String): ServerSettings(system)//ServerSettings(system)
      )(mat)
    } yield binding
  }
}


@c4("SimpleAkkaGatewayApp") final class AkkaDefaultRequestPreHandler extends AkkaRequestPreHandler with LazyLogging {
  def handleAsync(income: HttpRequest)(implicit ec: ExecutionContext): Future[HttpRequest] =
    Future.successful(income)
}

class AkkaStatefulReceiver[Message](ref: ActorRef) extends StatefulReceiver[Message] {
  def send(message: Message): Unit = ref ! message
}
@c4("AkkaStatefulReceiverFactoryApp") final class AkkaStatefulReceiverFactory(execution: Execution, akkaMat: AkkaMat) extends StatefulReceiverFactory {
  def create[Message](inner: List[Observer[Message]])(implicit executionContext: ExecutionContext): Future[StatefulReceiver[Message]] =
    for {
      mat <- akkaMat.get
      source = Source.actorRef[Message](100, OverflowStrategy.fail)
      sink = Sink.fold(inner)((st, msg: Message) => st.map(_.activate(msg)))
      (actorRef,resF) = source.toMat(sink)(Keep.both).run()(mat)
    } yield {
      execution.fatal(_ => resF)
      new AkkaStatefulReceiver[Message](actorRef)
    }
}


//execution: Execution,
//implicit val ec: ExecutionContextExecutor = system.dispatcher
// List[RunnableGraph[Future[_]]]
//val a: Source[String, NotUsed] = Source(Stream.from(1)).delay(1.second).fold("")((s,i)=>s"$s-$i")
//Source setup fromFutureSource tick