package ee.cone.c4gate_akka_s3

import java.util.UUID

import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpEntity, HttpMethods, HttpRequest}
import akka.stream.scaladsl.StreamConverters
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Config
import ee.cone.c4actor_s3.S3FileStorage
import ee.cone.c4actor_s3_minio.MinioS3FileStorage
import ee.cone.c4di.c4
import ee.cone.c4gate_akka.{AkkaMat, AkkaRequestHandler, AkkaRequestResponseHandlerProvider, AkkaRequestResponsePreHandlers}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class AkkaMinioRequestHandler(
  s3FileStorage: S3FileStorage,
) extends AkkaRequestHandler
  with LazyLogging {
  def shouldHandle(income: HttpRequest): Boolean = income.method == HttpMethods.PUT
  def handleAsync(income: HttpRequest, akkaMat: AkkaMat)(
    implicit ec: ExecutionContext
  ): Future[HttpRequest] = for {
    mat <- akkaMat.get
    _ = logger debug "PUT request received"
    tmpFilename: String = s"tmp/${UUID.randomUUID().toString}"
    _ = logger debug s"Storing request body to $tmpFilename"
    request <- Future {
      val succ = Try {
        val is = income.entity.dataBytes.runWith(StreamConverters.asInputStream(5.minutes))(mat)
        logger debug s"Bytes Stream created"
        s3FileStorage.uploadByteStream(tmpFilename, is)
        logger debug s"Uploaded bytestream to $tmpFilename"
      }.recover {
        case e: Throwable =>
          logger debug "Upload failed. Reason:"
          e.printStackTrace(System.out)
      }
      if (succ.isSuccess)
        income.withEntity(tmpFilename)
      else
        income.withEntity(HttpEntity.Empty).addHeader(RawHeader("file-not-stored", "true"))
    }
  } yield request
}

@c4("AkkaMinioGatewayApp") class WithAkkaMinioPreHandlers(
  config: Config,
  inner: AkkaRequestResponseHandlerProvider,
) extends AkkaRequestResponseHandlerProvider {
  lazy val fileStorage: MinioS3FileStorage = new MinioS3FileStorage(config)
  lazy val innerHandler: AkkaRequestResponsePreHandlers = inner.get
  def get: AkkaRequestResponsePreHandlers = innerHandler.copy(
    additionalRequestHandlers = new AkkaMinioRequestHandler(fileStorage) :: innerHandler.additionalRequestHandlers
  )
}