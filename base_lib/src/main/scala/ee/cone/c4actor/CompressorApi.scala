package ee.cone.c4actor

import okio.ByteString

import scala.concurrent.{ExecutionContext, Future}

sealed trait NamedCompressing {
  def name: String
}

trait Compressor extends NamedCompressing {
  def compress(data: ByteString): ByteString
}

trait DeCompressor extends NamedCompressing {
  def deCompress(data: ByteString): ByteString
}

trait RawCompressor extends NamedCompressing {
  def compress(data: Array[Byte]): Array[Byte]
}

trait DeCompressorRegistry {
  def byName: String => MultiDeCompressor
}

trait StreamCompressorFactory {
  def create(): Option[Compressor]
}

trait MultiDeCompressor extends NamedCompressing {
  def deCompress(data: ByteString)(implicit executionContext: ExecutionContext): List[Future[ByteString]]
}

trait MultiRawCompressor extends NamedCompressing {
  def compress(data: Future[Array[Byte]]*)(implicit executionContext: ExecutionContext): Future[Array[Byte]]
}