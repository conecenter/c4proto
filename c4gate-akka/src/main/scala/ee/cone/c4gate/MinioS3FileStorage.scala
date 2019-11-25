package ee.cone.c4gate

import java.io.{ByteArrayInputStream, InputStream}

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Config
import ee.cone.c4gate.MinioS3FileStorage.MinioConfig
import ee.cone.c4proto.c4
import io.minio.MinioClient

import scala.util.{Failure, Success, Try}

@c4("AkkaGatewayApp") class MinioS3FileStorage(
  config: Config,
) extends S3FileStorage
  with LazyLogging {
  private val minioConfigString: String = config get "C4MFS"
  private val minioConfig: MinioConfig = MinioConfig fromString minioConfigString
  private val minioClient: MinioClient = minioConfig.client
  val bucket: String = minioConfig.projBucket
  val expirationTime: Int = minioConfig.expirationTime getOrElse 5
  if (!minioClient.bucketExists(bucket)) minioClient.makeBucket(bucket)
  def content(filename: String): Try[Array[Byte]] = Try {
    logger debug s"Getting content of $filename"
    val is = minioClient.getObject(bucket, filename)
    logger debug s"Received content of $filename"
    LazyList.continually(is.read.toByte).takeWhile(_ != -1).toArray
  }
  def downloadLink(
    filename: String, expiration: Int
  ): Try[String] = Try {
    logger debug s"Generating presigned GET link for $filename with ${expiration}s expiration time"
    val link = minioClient.presignedGetObject(bucket, filename, expiration)
    logger debug s"Presigned GET link generated for $filename"
    logger debug s"GET Link for $filename = $link"
    link
  }
  def uploadLink(filename: String, expiration: Int): Try[String] = Try {
    logger debug s"Generating presigned PUT link for $filename with ${expiration}s expiration time"
    val link = minioClient.presignedPutObject(bucket, filename, expiration)
    logger debug s"Presigned PUT link generated for $filename"
    logger debug s"PUT Link for $filename = $link"
    link
  }
  def uploadBytes(
    filename: String, bytes: Array[Byte]
  ): Try[Unit] = Try {
    logger debug s"Storing data to $filename"
    minioClient.putObject(bucket, filename, new ByteArrayInputStream(bytes), "application/octet-stream")
    logger debug s"Successfully stored data @ $filename"
  }
  def copy(
    fromFilename: String, toFilename: String
  ): Try[Unit] = Try {
    logger debug s"Copying $fromFilename content to $toFilename"
    minioClient.copyObject(bucket, fromFilename, bucket, toFilename)
    logger debug s"Copied $fromFilename to $toFilename"
  }
  def delete(filename: String): Try[Boolean] =
    isFileExists(filename) match {
      case f@Failure(_) =>
        f
      case Success(true) =>
        logger debug s"Deleting $filename"
        minioClient.removeObject(bucket, filename)
        val deleted = isFileExists(filename).map(!_)
        deleted.fold(
          fa = _ => logger debug s"Error during deleting file $filename",
          fb = if (_)
                 logger debug s"File $filename was NOT Deleted!"
               else
                 logger debug s"File $filename successfully Deleted",
        )
        deleted
      case s@Success(false) =>
        logger debug s"File $filename doesn't exist. Can't delete"
        s
    }

  def stats(filename: String): Try[String] = Try {
    logger debug s"Getting stats of $filename"
    val stats = minioClient.statObject(bucket, filename).toString
    logger debug s"Stats for $filename:\n${
      stats
    }"
    stats
  }
  def isFileExists(filename: String): Try[Boolean] =
    stats(filename).map(!_.isBlank)
  def addPolicy(policy: String): Try[Unit] = Try {
    logger debug s"Adding policy to $bucket"
    logger debug s"Policy:\n$policy"
    minioClient.setBucketPolicy(bucket, policy)
  }
  private def expirationPolicy(days: Int): String =
    s"""<LifecycleConfiguration>
       |    <Rule>
       |      <ID>tmp-expiration</ID>
       |      <Prefix>tmp/</Prefix>
       |      <Status>Enabled</Status>
       |      <Expiration>
       |        <Days>$days</Days>
       |      </Expiration>
       |    </Rule>
       |  </LifecycleConfiguration>""".stripMargin
  def setTmpExpirationDays(days: Int): Try[Unit] = Try {
    logger debug s"Setting expiration time=$days days for $bucket"
    addPolicy(expirationPolicy(days))
  }
  def uploadByteStream(
    filename: String, inputStream: InputStream
  ): Try[Unit] = Try {
    logger debug s"Storing data to $filename"
    minioClient.putObject(bucket, filename, inputStream, "application/octet-stream")
    logger debug s"Successfully stored data @ $filename"
  }.recover {
    case e: Throwable =>
      e.printStackTrace()
  }
}
object MinioS3FileStorage {
  case class MinioConfig(
    endpoint: String,
    accessKey: String,
    secretKey: String,
    projBucket: String,
    expirationTime: Option[Int],
  ) {
    def client: MinioClient = new MinioClient(endpoint, accessKey, secretKey)
  }
  object MinioConfig {
    def fromString(configString: String): MinioConfig = {
      val cfg = configString.split("&").flatMap {
        _.split("=") match {
          case Array(key) =>
            Some(key.toLowerCase -> "")
          case Array(key, value) =>
            Some(key.toLowerCase -> value)
          case _ =>
            None
        }
      }.toMap
      new MinioConfig(
        endpoint = cfg("endpoint"),
        accessKey = cfg("accesskey"),
        secretKey = cfg("secretkey"),
        projBucket = cfg("bucket"),
        expirationTime = cfg.get("expiration").flatMap(_.toIntOption),
      )
    }
  }
}
