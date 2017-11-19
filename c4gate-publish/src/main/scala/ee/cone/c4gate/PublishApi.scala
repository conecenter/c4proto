package ee.cone.c4gate

trait PublishConfig {
  def mimeTypes: Map[String,String]
  def publishFromStrings: List[(String,String)]
}

abstract class PublishDir(val value: String)
