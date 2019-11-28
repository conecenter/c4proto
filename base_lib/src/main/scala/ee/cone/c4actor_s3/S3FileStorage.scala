package ee.cone.c4actor_s3

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
    * @return byte array of file
    */
  def content(filename: String): Array[Byte]
  /**
    * generates link for downloading file, which is valid for specified time in seconds
    *
    * @param filename   to get
    * @param expiration seconds for link to expire
    * @return link
    */
  def downloadLink(filename: String, expiration: Int): String
  /**
    * generates link for downloading file with default expiration time
    *
    * @see ee.cone.c4gate.S3FileStorage#downloadLink(java.lang.String, int)
    * @param filename to get
    * @return link
    */
  def downloadLink(filename: String): String =
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
  def uploadLink(filename: String, expiration: Int): String
  /**
    * generates link for uploading file via PUT request with default expiration time
    *
    * @see ee.cone.c4gate.S3FileStorage#uploadLink(java.lang.String)
    * @param filename to store
    * @return link
    */
  def uploadLink(filename: String): String =
    uploadLink(filename, expirationTime)
  /**
    * uploads content `bytes` as `filename` to storage
    *
    * @param filename filename to save file as
    * @param bytes    array of bytes of body
    * @return uploaded bytes count
    */
  def uploadBytes(filename: String, bytes: Array[Byte]): Long
  /**
    * uploads stream as filename to storage
    *
    * @param filename    filename to save files as
    * @param inputStream input stream of body
    * @return is file exists
    */
  def uploadByteStream(filename: String, inputStream: InputStream): Boolean
  /**
    * copies file in storage
    *
    * @param fromFilename file to copy
    * @param toFilename   destination filename
    * @return success or failure
    */
  def copy(fromFilename: String, toFilename: String): Boolean
  /**
    * deletes file from storage
    *
    * @param filename to delete
    * @return deleted?
    */
  def delete(filename: String): Boolean
  /**
    * gets stats JSON of file
    *
    * @param filename to stat
    * @return S3 stats of file
    */
  def stats(filename: String): String
  /**
    * checks is file exists
    *
    * @param filename to check
    * @return exists?
    */
  def isFileExists(filename: String): Boolean
  /**
    * adds policy to bucket
    *
    * @see https://docs.aws.amazon.com/en_us/AmazonS3/latest/dev/example-bucket-policies.html
    * @param policy XML|JSON string
    */
  def addPolicy(policy: String): Unit
  /**
    * adds lifecycle policy that deletes files in tmp/ directory after specified days
    *
    * @param days for file to expire
    */
  def setTmpExpirationDays(days: Int): Unit
}
