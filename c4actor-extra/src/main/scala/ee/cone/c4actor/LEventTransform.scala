package ee.cone.c4actor

import ee.cone.c4actor.CollectiveTransformProtocol.D_CollectiveTransformMeta
import ee.cone.c4assemble.Types.Values
import ee.cone.c4proto.{Id, Protocol, protocol}

import scala.collection.immutable.Seq

trait LEventTransform extends Product {
  def lEvents(local: Context): Seq[LEvent[Product]]

  def leventsDescription: String = this.getClass.getName
}

case class CollectiveTransform(srcId: String, events: Values[LEventTransform]) extends TxTransform {
  def transform(local: Context): Context =
    TxAdd(events.flatMap(_.lEvents(local)))(InsertOrigMeta(D_CollectiveTransformMeta(events.map(_.leventsDescription).toList) :: Nil)(local))
}

object InsertOrigMeta {
  def apply(origs: List[Product]): Context ⇒ Context =
    TxTransformOrigMetaKey.set(origs.map(MetaAttr))
}

@protocol object CollectiveTransformProtocolBase   {

  @Id(0x0ab0) case class D_CollectiveTransformMeta(
    @Id(0x0ab1) transforms: List[String]
  )

}
