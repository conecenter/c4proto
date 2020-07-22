package ee.cone.c4gate_akka_s3

import java.util.UUID

import akka.http.scaladsl.model.{HttpMethods, HttpRequest}
import akka.stream.scaladsl.StreamConverters
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor_s3.S3FileStorage
import ee.cone.c4di.c4
import ee.cone.c4gate_akka._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

@c4("AkkaMinioGatewayApp") final class AkkaMinioRequestPreHandler(
  s3FileStorage: S3FileStorage,
  akkaMat: AkkaMat,
) extends AkkaRequestPreHandler with LazyLogging {
  def handleAsync(
    income: HttpRequest
  )(
    implicit ec: ExecutionContext
  ): Future[HttpRequest] = if (income.method == HttpMethods.PUT) {
    for { mat <- akkaMat.get } yield {
      val tmpFilename: String = s"tmp/${UUID.randomUUID()}"
      logger debug s"PUT request received; Storing request body to $tmpFilename"
      val is = income.entity.dataBytes.runWith(StreamConverters.asInputStream(5.minutes))(mat) //todo check throw
      logger debug s"Bytes Stream created"
      val isUploaded = s3FileStorage.uploadByteStream(tmpFilename, is)
      logger debug s"${if (isUploaded) "" else "!!!!Not !!!"}uploaded bytestream to $tmpFilename"
      // ? income.headers
      income.withEntity(tmpFilename)
    }
  } else Future.successful(income)
}
