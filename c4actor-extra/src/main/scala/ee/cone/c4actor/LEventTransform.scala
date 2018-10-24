package ee.cone.c4actor

import ee.cone.c4assemble.Types.Values

import scala.collection.immutable.Seq

trait LEventTransform extends Product{
  def lEvents(local: Context): Seq[LEvent[Product]]

  def transformMeta: String = this.getClass.getName
}

case class CollectiveTransform(srcId: String, events: Values[LEventTransform]) extends TxTransform {
  def transform(local: Context): Context =
    TxAdd(events.flatMap(_.lEvents(local)))(local)

  override lazy val transformMeta: String = s"CollectiveTransform for ${events.size}: ${events.map(_.transformMeta).mkString(",")}"
}
