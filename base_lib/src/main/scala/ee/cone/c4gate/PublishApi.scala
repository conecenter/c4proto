package ee.cone.c4gate

import ee.cone.c4actor.{Compressor, Context, LEvent}
import ee.cone.c4gate.HttpProtocol.N_Header
import okio.ByteString

trait PublishFromStringsProvider {
  def get: List[(String,String)]
}

trait PublishMimeTypesProvider {
  def get: List[(String,String)]
}

class PublishFullCompressor(val value: Compressor)

////

case class ByPathHttpPublication(path: String, headers: List[N_Header], body: ByteString)
case class ByPathHttpPublicationUntil(path: String, until: Long)

trait Publisher {
  def publish(publication: ByPathHttpPublication, lifetime: Long): Seq[LEvent[Product]]
  def publish(man: String, publications: List[ByPathHttpPublication]): Context=>Seq[LEvent[Product]]
}


