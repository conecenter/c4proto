package ee.cone.c4gate

import java.io.InputStream

import scala.util.Try

trait S3FileStorage {
  /**
    * @return project bucket name
    */
  def bucket: String
  /**
    * @return seconds for presigned links to expire
    */
  def expirationTime: Int
  /**
    * gets content of file `filename` from storage as Array[Byte]
    *
    * @param filename to get
    * @return Success(byte array of file)
    *         or failure
    */
  def content(filename: String): Try[Array[Byte]]
  /**
    * generates link for downloading file, which is valid for specified time in seconds
    *
    * @param filename   to get
    * @param expiration seconds for link to expire
    * @return Success(link)
    *         of failure
    */
  def downloadLink(filename: String, expiration: Int): Try[String]
  /**
    * generates link for downloading file with default expiration time
    *
    * @see ee.cone.c4gate.S3FileStorage#downloadLink(java.lang.String, int)
    * @param filename to get
    * @return Success(link)
    *         of failure
    */
  def downloadLink(filename: String): Try[String] =
    downloadLink(filename, expirationTime)
  /**
    * generates link for uploading file via PUT request, which is valid for specified time
    * stores PUT request body as file content with specified filename
    * !!! LINK CAN BE USED MULTIPLE TIMES, OVERRIDING FILE CONTENT !!!
    *
    * @param filename   to store
    * @param expiration seconds for link to expire
    * @return link
    */
  def uploadLink(filename: String, expiration: Int): Try[String]
  /**
    * generates link for uploading file via PUT request with default expiration time
    *
    * @see ee.cone.c4gate.S3FileStorage#uploadLink(java.lang.String)
    * @param filename to store
    * @return link
    */
  def uploadLink(filename: String): Try[String] =
    uploadLink(filename, expirationTime)
  /**
    * uploads content `bytes` as `filename` to storage
    *
    * @param filename filename to save file as
    * @param bytes    array of bytes of body
    * @return success or failure
    */
  def uploadBytes(filename: String, bytes: Array[Byte]): Try[Unit]
  /**
    * uploads stream as filename to storage
    *
    * @param filename    filename to save files as
    * @param inputStream input stream of body
    * @return success or failure
    */
  def uploadByteStream(filename: String, inputStream: InputStream): Try[Unit]
  /**
    * copies file in storage
    *
    * @param fromFilename file to copy
    * @param toFilename   destination filename
    * @return success or failure
    */
  def copy(fromFilename: String, toFilename: String): Try[Unit]
  /**
    * deletes file from storage
    *
    * @param filename to delete
    * @return success(deleted?) or failure
    */
  def delete(filename: String): Try[Boolean]
  /**
    * gets stats JSON of file
    *
    * @param filename to stat
    * @return Success(stats) or failure
    */
  def stats(filename: String): Try[String]
  /**
    * checks is file exists
    *
    * @param filename to check
    * @return Success(exists?) or failure
    */
  def isFileExists(filename: String): Try[Boolean]
  /**
    * adds policy to bucket
    *
    * @see https://docs.aws.amazon.com/en_us/AmazonS3/latest/dev/example-bucket-policies.html
    * @param policy XML|JSON string
    * @return success or failure
    */
  def addPolicy(policy: String): Try[Unit]
  /**
    * adds lifecycle policy that deletes files in tmp/ directory after specified days
    *
    * @param days for file to expire
    * @return success or failure
    */
  def setTmpExpirationDays(days: Int): Try[Unit]
}