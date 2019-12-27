
package ee.cone.c4gate_akka

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
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
import ee.cone.c4gate.HttpProtocolBase.N_Header
import ee.cone.c4gate_server._
import ee.cone.c4proto.ToByteString

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal

@c4("AkkaMatApp") class AkkaMatImpl(configs: List[AkkaConf], matPromise: Promise[ActorMaterializer] = Promise()) extends AkkaMat with Executable with Early {
  def get: Future[ActorMaterializer] = matPromise.future
  def run(): Unit = {
    val config = ConfigFactory.parseString(configs.map(_.content).sorted.mkString("\n"))
    val system = ActorSystem.create("default",config)
    matPromise.success(ActorMaterializer.create(system))
  }
}

@c4("AkkaGatewayApp") class AkkaHttpServerConf extends AkkaConf {
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


@c4("AkkaGatewayApp") class AkkaDefaultRequestHandlerProvider extends AkkaRequestHandlerProvider{
  def get: AkkaRequestHandler = AkkaDefaultRequestHandler
}
@c4("AkkaGatewayApp") class AkkaDefaultResponseHandlerProvider extends AkkaResponseHandlerProvider {
  def get: AkkaResponseHandler =
    new AkkaRedirectResponseHandler(AkkaDefaultResponseHandler)
}


@c4("AkkaGatewayApp") class AkkaHttpServer(
  config: Config, handler: FHttpHandler, execution: Execution, akkaMat: AkkaMat,
  requestPreHandlerProvider: AkkaRequestHandlerProvider,
  responsePreHandlerProvider: AkkaResponseHandlerProvider,
)(
  port: Int = config.get("C4HTTP_PORT").toInt,
  requestPreHandler: AkkaRequestHandler = requestPreHandlerProvider.get,
  responsePreHandler: AkkaResponseHandler = responsePreHandlerProvider.get
) extends Executable with Early with LazyLogging {
  def getHandler(mat: Materializer)(implicit ec: ExecutionContext): HttpRequest => Future[HttpResponse] = req => {
    val method = req.method.value
    val path = req.uri.path.toString
    val rHeaders = (`Content-Type`(req.entity.contentType) :: req.headers.toList)
      .map(h => N_Header(h.name, h.value))
    logger.debug(s"req init: $method $path")
    logger.trace(s"req headers: $rHeaders")
    (for {
      request <- requestPreHandler.handleAsync(req, akkaMat)
      entity <- request.entity.toStrict(Duration(5, MINUTES))(mat)
      body = ToByteString(entity.getData.toArray)
      rReq = FHttpRequest(method, path, rHeaders, body)
      rResp <- handler.handle(rReq)
      response <- responsePreHandler.handleAsync(rResp, akkaMat)
    } yield response).recover{ case NonFatal(e) =>
        logger.error("http-handler",e)
        throw e
    }
  }
  def run(): Unit = execution.fatal{ implicit ec =>
    for{
      mat <- akkaMat.get
      handler = getHandler(mat)
      // to see: MergeHub/PartitionHub.statefulSink solution of the same task vs FHttpHandler
      binding <- Http()(mat.system).bindAndHandleAsync(
        handler = handler,
        interface = "localhost",
        port = port,
        settings = ServerSettings(mat.system)
        //defapply(configOverrides: String): ServerSettings(system)//ServerSettings(system)
      )(mat)
    } yield binding
  }
}

class AkkaRedirectResponseHandler(
  nextHandler: AkkaResponseHandler,
) extends AkkaResponseHandler with LazyLogging {
  val headername: String = "redirect-inner"
  def handleAsync(
    income: ee.cone.c4gate.HttpProtocolBase.S_HttpResponse,
    akkaMat: AkkaMat,
  )(
    implicit ec: ExecutionContext,
  ): Future[HttpResponse] = income match {
    case response if response.headers.exists(_.key equalsIgnoreCase headername) =>
      income.headers.collectFirst {
        case N_Header(`headername`, uri) =>
          for {
            materializer <- akkaMat.get
            actorsys = materializer.system
            _ = logger debug s"Redirecting resp with id=${income.srcId} to $uri"
            response <- Http(actorsys).singleRequest(HttpRequest(uri = uri))
            _ = logger debug "Redirect successful"
          } yield response
      }.getOrElse(
        Future.failed(
          new Exception("akka-redirect-response-handler-failure")
        )
      )
    case _ =>
      nextHandler.handleAsync(income, akkaMat)
  }
}
object AkkaDefaultResponseHandler extends AkkaResponseHandler with LazyLogging {
  def handleAsync(
    income: ee.cone.c4gate.HttpProtocolBase.S_HttpResponse,
    akkaMat: AkkaMat,
  )(
    implicit ec: ExecutionContext,
  ): Future[HttpResponse] = Future.successful {
    val status = Math.toIntExact(income.status)
    val (ctHeaders, rHeaders) = income.headers.partition(_.key == "content-type")
    val contentType =
      Single.option(ctHeaders.flatMap(h => ContentType.parse(h.value).toOption))
        .getOrElse(ContentTypes.`application/octet-stream`)
    val aHeaders = rHeaders.map(h => RawHeader(h.key, h.value))
    val entity = HttpEntity(contentType, income.body.toByteArray)
    logger.debug(s"resp status: $status")
    HttpResponse(status, aHeaders, entity)
  }
}
object AkkaDefaultRequestHandler extends AkkaRequestHandler with LazyLogging {
  def handleAsync(
    income: HttpRequest, akkaMat: AkkaMat
  )(
    implicit ec: ExecutionContext
  ): Future[HttpRequest] = Future successful income
}
class AkkaStatefulReceiver[Message](ref: ActorRef) extends StatefulReceiver[Message] {
  def send(message: Message): Unit = ref ! message
}
@c4("AkkaStatefulReceiverFactoryApp") class AkkaStatefulReceiverFactory(execution: Execution, akkaMat: AkkaMat) extends StatefulReceiverFactory {
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