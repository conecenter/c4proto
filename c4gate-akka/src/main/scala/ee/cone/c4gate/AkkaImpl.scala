
package ee.cone.c4gate

import java.util.UUID

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{RawHeader, `Content-Type`}
import akka.stream.scaladsl.{Keep, Sink, Source, StreamConverters}
import akka.stream.{ActorMaterializer, Materializer, OverflowStrategy}
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.{Config, Executable, Execution, Observer}
import ee.cone.c4assemble.Single
import ee.cone.c4gate.HttpProtocolBase.N_Header
import ee.cone.c4proto.{ToByteString, c4}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Try
import scala.util.control.NonFatal

@c4("AkkaMatApp") class AkkaMatImpl(matPromise: Promise[ActorMaterializer] = Promise()) extends AkkaMat with Executable {
  def get: Future[ActorMaterializer] = matPromise.future
  def run(): Unit = {
    val system = ActorSystem.create()
    matPromise.success(ActorMaterializer.create(system))
  }
}

@c4("AkkaGatewayApp") class AkkaHttpServer(
  config: Config,
  handler: FHttpHandler,
  execution: Execution,
  akkaMat: AkkaMat,
)(
  port: Int = config.get("C4HTTP_PORT").toInt
) extends Executable with LazyLogging {

  def additionalRequestPreparation(
    mat: Materializer,
    req: HttpRequest
  )(
    implicit ec: ExecutionContext
  ): Future[HttpRequest] = Future successful req
  def getHandler(mat: Materializer)(implicit ec: ExecutionContext): HttpRequest => Future[HttpResponse] = req => {
    val method = req.method.value
    val path = req.uri.path.toString
    val rHeaders = (`Content-Type`(req.entity.contentType) :: req.headers.toList)
      .map(h => N_Header(h.name, h.value))
    logger.debug(s"req init: $method $path")
    logger.trace(s"req headers: $rHeaders")
    (for {
      request <- additionalRequestPreparation(mat, req)
      entity <- request.entity.toStrict(Duration(5, MINUTES))(mat)
      body = ToByteString(entity.getData.toArray)
      rReq = FHttpRequest(method, path, rHeaders, body)
      rResp <- handler.handle(rReq)
    } yield {
      val status = Math.toIntExact(rResp.status)
      val (ctHeaders, rHeaders) = rResp.headers.partition(_.key == "content-type")
      val contentType =
        Single.option(ctHeaders.flatMap(h => ContentType.parse(h.value).toOption))
          .getOrElse(ContentTypes.`application/octet-stream`)
      val aHeaders = rHeaders.map(h => RawHeader(h.key, h.value))
      val entity = HttpEntity(contentType, rResp.body.toByteArray)
      logger.debug(s"resp status: $status")
      HttpResponse(status, aHeaders, entity)
    }).recover{ case NonFatal(e) =>
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
        //defapply(configOverrides: String): ServerSettings(system)//ServerSettings(system)
      )(mat)
    } yield binding
  }
}
@c4("AkkaGatewayApp") class AkkaHttpServerWithS3(
  akkaHttpServer: AkkaHttpServer,
  config: Config,
  handler: FHttpHandler,
  execution: Execution,
  akkaMat: AkkaMat,
  s3FileStorage: S3FileStorage,
)(
  port: Int = config.get("C4HTTP_PORT").toInt,
  useS3: Boolean = config.getOpt("C4MFS").exists(!_.isBlank),
) extends AkkaHttpServer(config, handler, execution, akkaMat)(port) {
  if (useS3) s3FileStorage.setTmpExpirationDays(1)

  override def additionalRequestPreparation(mat: Materializer, req: HttpRequest)(
    implicit ec: ExecutionContext
  ): Future[HttpRequest] = if (useS3) req.method match {
    case HttpMethods.PUT =>
      logger debug s"PUT request received"
      val tmpFilename = s"tmp/${UUID.randomUUID().toString}"
      logger debug s"Storing request body to $tmpFilename"
      Future {
        val succ = Try {
          val is = req.entity.dataBytes.runWith(StreamConverters.asInputStream(5.minutes))(mat)
          logger debug s"Bytes Stream created"
          s3FileStorage.uploadByteStream(tmpFilename, is)
          logger debug s"Uploaded bytestream to $tmpFilename"
        }
        if (succ.isSuccess)
          req.withEntity(tmpFilename)
        else
          req.withEntity(HttpEntity.Empty).addHeader(RawHeader("file-not-stored", "true"))
      }
    case _ =>
      super.additionalRequestPreparation(mat, req)
  }
                           else super.additionalRequestPreparation(mat, req)
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