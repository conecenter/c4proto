
package ee.cone.c4gate

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{RawHeader, `Content-Type`}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{ActorMaterializer, Materializer, OverflowStrategy}
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.{Config, Executable, Execution, Observer}
import ee.cone.c4assemble.Single
import ee.cone.c4gate.HttpProtocolBase.N_Header
import ee.cone.c4proto.{ToByteString, c4, provide}
import io.minio.MinioClient

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.control.NonFatal
import scala.util.matching.Regex

@c4("AkkaMatApp") class AkkaMatImpl(
  matPromise: Promise[ActorMaterializer] = Promise()
) extends AkkaMat with Executable {
  def get: Future[ActorMaterializer] = matPromise.future
  def run(): Unit = {
    val system = ActorSystem.create()
    matPromise.success(ActorMaterializer.create(system))
  }
}

@c4("AkkaGatewayApp") class AkkaHttpServer(
  config: Config, handler: FHttpHandler, execution: Execution, akkaMat: AkkaMat,
  additionalHandlers: List[AkkaInnerResponseHandler],
)(
  port: Int = config.get("C4HTTP_PORT").toInt
) extends Executable with LazyLogging {
  logger.debug(s"additional handlers: ${additionalHandlers.map(_.getClass.getSimpleName)}")

  def getHandler(mat: Materializer)(implicit ec: ExecutionContext): HttpRequest => Future[HttpResponse] = req => {
    val method = req.method.value
    val path = req.uri.path.toString
    val rHeaders = (`Content-Type`(req.entity.contentType) :: req.headers.toList)
      .map(h => N_Header(h.name, h.value))
    logger.debug(s"req init: $method $path")
    logger.trace(s"req headers: $rHeaders")
    (for {
      entity <- req.entity.toStrict(Duration(5, MINUTES))(mat)
      body = ToByteString(entity.getData.toArray)
      rReq = FHttpRequest(method, path, rHeaders, body)
      rResp <- handler.handle(rReq)
      response <- additionalHandlers.find(_ shouldHandle rResp).getOrElse(AkkaDefaultResponseHandler).handleAsync(rResp, akkaMat)
    } yield response


      ).recover { case NonFatal(e) =>
      logger.error("http-handler", e)
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
        //defapply(configOverrides: String): ServerSettings(system)//ServerSettings(system)
      )(mat)
    } yield binding
  }
}
@c4("AkkaGatewayApp") class WithAkkaRedirect {
  @provide def additionalHandlers: Seq[AkkaInnerResponseHandler] = AkkaRedirectInnerResponseHandler :: Nil
}
case class AkkaMinioSettings(
  endpoint: String,
  accesskey: String,
  privatekey: String,
  projBucket: String,
)
object AkkaMinioSettings {
  val confregex: Regex = """(.*)?accesskey=(.*)&privatekey=(.*)&bucket=(.*)""".r
  def fromConfig(config: Config): List[AkkaMinioSettings] =
    config.getOpt("C4MINIO").map {
      settings =>
        val confregex(endpoint, accesskey, privatekey, projbucket) = settings
        new AkkaMinioSettings(endpoint, accesskey, privatekey, projbucket)
    }.toList
}
object AkkaDefaultResponseHandler extends AkkaInnerResponseHandler with LazyLogging {
  def shouldHandle(httpResponse: ee.cone.c4gate.HttpProtocolBase.S_HttpResponse): Boolean = true
  def handleAsync(
    httpResponse: ee.cone.c4gate.HttpProtocolBase.S_HttpResponse,
    akkaMat: AkkaMat,
  )(
    implicit ec: ExecutionContext,
  ): Future[HttpResponse] = Future.successful {
    val status = Math.toIntExact(httpResponse.status)
    val (ctHeaders, rHeaders) = httpResponse.headers.partition(_.key == "content-type")
    val contentType =
      Single.option(ctHeaders.flatMap(h => ContentType.parse(h.value).toOption))
        .getOrElse(ContentTypes.`application/octet-stream`)
    val aHeaders = rHeaders.map(h => RawHeader(h.key, h.value))
    val entity = HttpEntity(contentType, httpResponse.body.toByteArray)
    logger.debug(s"resp status: $status")
    HttpResponse(status, aHeaders, entity)
  }
}
object AkkaRedirectInnerResponseHandler
  extends AkkaInnerResponseHandler with LazyLogging {
  val headername = "redirect-inner"
  def shouldHandle(
    httpResponse: ee.cone.c4gate.HttpProtocolBase.S_HttpResponse
  ): Boolean = httpResponse.headers.exists {
    case N_Header(`headername`, _) => true
    case _ => false
  }
  def handleAsync(
    httpResponse: ee.cone.c4gate.HttpProtocolBase.S_HttpResponse,
    akkaMat: AkkaMat,
  )(
    implicit ec: ExecutionContext,
  ): Future[HttpResponse] = httpResponse.headers.collectFirst {
    case N_Header(`headername`, uri) =>
      for {
        materializer <- akkaMat.get
        actorsys = materializer.system
        response <- Http(actorsys).singleRequest(HttpRequest(uri = uri))
      } yield response
  }.getOrElse(Future.failed(new Exception("akka-minio-response-handler-failure")))
}

class AkkaStatefulReceiver[Message](ref: ActorRef) extends StatefulReceiver[Message] {
  def send(message: Message): Unit = ref ! message
}

@c4("AkkaStatefulReceiverFactoryApp") class AkkaStatefulReceiverFactory(
  execution: Execution, akkaMat: AkkaMat
) extends StatefulReceiverFactory {
  def create[Message](inner: List[Observer[Message]])
    (implicit executionContext: ExecutionContext): Future[StatefulReceiver[Message]] =
    for {
      mat <- akkaMat.get
      source = Source.actorRef[Message](100, OverflowStrategy.fail)
      sink = Sink.fold(inner)((st, msg: Message) => st.flatMap(_.activate(msg)))
      (actorRef, resF) = source.toMat(sink)(Keep.both).run()(mat)
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