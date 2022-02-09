
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
    "akka.log-config-on-start = on",
    "akka.http.server.idle-timeout = 300 s",
    "akka.http.server.parsing.max-content-length = infinite",
    //"akka.http.server.parsing.max-to-strict-bytes = infinite",
    "akka.http.client.parsing.max-content-length = infinite",
    "akka.http.server.request-timeout = 600 s",
    "akka.http.client.request-timeout = 600 s",
    "akka.http.parsing.max-to-strict-bytes = infinite",
    "akka.http.server.raw-request-uri-header = on",
    "akka.http.host-connection-pool.max-connections = 512",
    "akka.http.host-connection-pool.max-open-requests = 512",
  ).mkString("\n")
}

@c4("AkkaGatewayApp") final class AkkaHttpImpl(
  execution: Execution, akkaMat: AkkaMat,
)(
  val httpPromise: Promise[HttpExt] = Promise()
) extends AkkaHttp with Executable with Early {
  def run(): Unit = execution.fatal { implicit ec =>
    for(mat <- akkaMat.get) yield execution.success(httpPromise, Http()(mat.system))
  }
  def get: Future[HttpExt] = httpPromise.future
}

@c4("AkkaGatewayApp") final class DefaultAkkaRequestHandler(
  handler: FHttpHandler,
  akkaMat: AkkaMat, akkaHttp: AkkaHttp,
  requestPreHandler: AkkaRequestPreHandler
) extends AkkaRequestHandler with LazyLogging {
  def pathPrefix = ""
  def handleAsync(req: HttpRequest)(implicit ec: ExecutionContext): Option[Future[HttpResponse]] =
    Option(for {
      mat <- akkaMat.get
      http <- akkaHttp.get
      request <- requestPreHandler.handleAsync(req)
      entity <- request.entity.toStrict(Duration(5, MINUTES))(mat)
      rReq = {
        val body = ToByteString(entity.getData.toArray)
        val method = req.method.value
        val path = req.uri.path.toString
        val rHeaders = (`Content-Type`(req.entity.contentType) :: req.headers.toList)
          .map(h => N_Header(h.name, h.value))
        logger.debug(s"req init: $method")
        logger.trace(s"req headers: $rHeaders")
        FHttpRequest(method, path, req.uri.rawQueryString, rHeaders, body)
      }
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
          /*
          if(response.headers.exists(h => h.name == "accept" and h.value == "text/event-stream")){
            logger debug s"Redirecting event-stream $uri"
            Source.single(request)
              .via(http.connectionTo(request.uri.authority.host).toPort(request.uri.authority.port).http())
              .toMat(Sink.head)(Keep.right)
          } else {*/
          logger debug s"Redirecting to $uri"
          val nReq = if(uri.startsWith("orig:")) req.withUri(uri.drop(5))
            else HttpRequest(uri = uri)
          http.singleRequest(nReq)
          //}
        }
      }
    } yield response)
}

@c4("SimpleAkkaGatewayApp") final class AkkaHttpServer(
  config: Config, execution: Execution, akkaMat: AkkaMat, akkaHttp: AkkaHttp,
  handlersUnsorted: List[AkkaRequestHandler]
)(
  port: Int = config.get("C4HTTP_PORT").toInt,
  handlers: List[AkkaRequestHandler] = handlersUnsorted.sortBy(_.pathPrefix).reverse
) extends Executable with Early with LazyLogging {
  def run(): Unit = execution.fatal{ implicit ec =>
    for{
      mat <- akkaMat.get
      http <- akkaHttp.get
      // to see: MergeHub/PartitionHub.statefulSink solution of the same task vs FHttpHandler
      binding <- http.bindAndHandleAsync(
        handler = (req: HttpRequest)=>{
          val path = req.uri.path.toString
          logger.debug(s"req init: $path")
          handlers.foldLeft(Option.empty[Future[HttpResponse]])((res,h)=>
            if(res.isEmpty && path.startsWith(h.pathPrefix)) h.handleAsync(req)
            else res
          ).get.recover{ case NonFatal(e) =>
            logger.error("http-handler",e)
            throw e
          }
        },
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