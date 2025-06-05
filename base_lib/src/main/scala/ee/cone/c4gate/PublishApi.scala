package ee.cone.c4gate

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.{RawCompressor, Context, LEvent}
import ee.cone.c4gate.HttpProtocol.N_Header
import okio.ByteString

trait PublishFromStringsProvider {
  def get: List[(String,String)]
}

trait PublishMimeTypesProvider {
  def get: List[(String,String)]
}

class PublishFullCompressor(val value: RawCompressor)

////

case class ByPathHttpPublication(path: String, headers: List[N_Header], body: ByteString)
case class ByPathHttpPublicationUntil(path: String, until: Long)
case class PurgePublication(srcId: SrcId)

trait Publisher {
  def publish(publication: ByPathHttpPublication, until: Long=>Long): Seq[LEvent[Product]]
  def publish(man: String, publications: List[ByPathHttpPublication]): Context=>Seq[LEvent[Product]]
}


