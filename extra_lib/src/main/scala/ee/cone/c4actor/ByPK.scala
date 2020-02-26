package ee.cone.c4actor

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Getter

import scala.collection.immutable.Map

object ByPK {
  def apply[V<:Product](cl: Class[V]): ByPrimaryKeyGetter[V] =
    ByPrimaryKeyGetter(cl.getName)
}
//todo? def t[T[U],U](clO: Class[T[U]], cl1: Class[U]): Option[T[U]] = None

case class ByPrimaryKeyGetter[V<:Product](className: String)
  extends Getter[SharedContext with AssembledContext,Map[SrcId,V]]
{
  def of: SharedContext with AssembledContext => Map[SrcId, V] = context =>
    GetOrigIndexKey.of(context)(context,className).asInstanceOf[Map[SrcId,V]]
}

case object GetOrigIndexKey extends SharedComponentKey[(AssembledContext,String)=>Map[SrcId,Product]]

trait ModelAccessFactory {
  def to[P<:Product](product: P): Option[Access[P]]
}