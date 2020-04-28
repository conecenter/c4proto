package ee.cone.c4actor_s3_minio

import java.io.{ByteArrayInputStream, InputStream}

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Config
import ee.cone.c4actor_s3.S3FileStorage
import ee.cone.c4actor_s3_minio.MinioS3FileStorage.MinioConfig
import ee.cone.c4di.c4
import io.minio.MinioClient

import scala.util.{Failure, Success, Try}

@c4("MinioS3App") final class MinioS3FileStorage(
  config: Config,
) extends S3FileStorage
  with LazyLogging {
  private lazy val minioConfigString: String = config get "C4MFS"
  private lazy val minioConfig: MinioConfig = MinioConfig fromString minioConfigString
  private lazy val minioClient: MinioClient = minioConfig.client
  lazy val bucket: String = minioConfig.projBucket
  private def isBucketExists: Boolean =
    minioClient.bucketExists(bucket)

  private def createBucket(): Unit =
    if (isBucketExists) ()
    else {
      logger debug s"Creating bucket $bucket"
      minioClient.makeBucket(bucket)
      logger debug s"Created bucket $bucket"
    }

  lazy val expirationTime: Int = minioConfig.expirationTime getOrElse 5
  def content(filename: String): Array[Byte] = {
    logger debug s"Getting content of $filename"
    val is = minioClient.getObject(bucket, filename)
    logger debug s"Received content of $filename"
    LazyList.continually(is.read.toByte).takeWhile(_ != -1).toArray
  }
  def downloadLink(
    filename: String, expiration: Int
  ): String = {
    logger debug s"Generating presigned GET link for $filename with ${expiration}s expiration time"
    val link = minioClient.presignedGetObject(bucket, filename, expiration)
    logger debug s"Presigned GET link generated for $filename"
    logger debug s"GET Link for $filename = $link"
    link
  }
  def uploadLink(filename: String, expiration: Int): String = {
    createBucket()
    logger debug s"Generating presigned PUT link for $filename with ${expiration}s expiration time"
    val link = minioClient.presignedPutObject(bucket, filename, expiration)
    logger debug s"Presigned PUT link generated for $filename"
    logger debug s"PUT Link for $filename = $link"
    link
  }
  def uploadBytes(
    filename: String, bytes: Array[Byte]
  ): Long = {
    createBucket()
    logger debug s"Storing data to $filename"
    minioClient.putObject(bucket, filename, new ByteArrayInputStream(bytes), "application/octet-stream")
    logger debug s"Successfully stored data @ $filename"
    bytes.length
  }
  def copy(
    fromFilename: String, toFilename: String
  ): Boolean = {
    logger debug s"Copying $fromFilename content to $toFilename"
    minioClient.copyObject(bucket, fromFilename, bucket, toFilename)
    logger debug s"Copied $fromFilename to $toFilename"
    isFileExists(toFilename)
  }
  def delete(filename: String): Boolean =
    if (isFileExists(filename)) {
      logger debug s"Deleting $filename"
      minioClient.removeObject(bucket, filename)
      val deleted = !isFileExists(filename)
      if (deleted)
        logger debug s"File $filename was NOT Deleted!"

      else
        logger debug s"File $filename successfully Deleted"
      deleted
    }
    else {
      logger debug s"File $filename doesn't exist. Can't delete"
      false
    }

  def stats(filename: String): String = {
    logger debug s"Getting stats of $filename"
    val stats = minioClient.statObject(bucket, filename).toString
    logger debug s"Stats for $filename:\n${
      stats
    }"
    stats
  }
  def isFileExists(filename: String): Boolean =
    !stats(filename).isBlank
  def addPolicy(policy: String): Unit = {
    createBucket()
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
  def setTmpExpirationDays(days: Int): Unit = {
    createBucket()
    logger debug s"Setting expiration time=$days days for $bucket"
    addPolicy(expirationPolicy(days))
  }
  def uploadByteStream(
    filename: String, inputStream: InputStream
  ): Boolean = {
    createBucket()
    logger debug s"Storing data to $filename"
    minioClient.putObject(bucket, filename, inputStream, "application/octet-stream")
    logger debug s"Successfully stored data @ $filename"
    isFileExists(filename)
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
