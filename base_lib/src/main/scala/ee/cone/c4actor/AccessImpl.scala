package ee.cone.c4actor

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.ToPrimaryKey
import ee.cone.c4di._

@c4("ModelAccessFactoryCompApp") class RModelAccessFactoryImpl extends RModelAccessFactory {
  def to[P <: Product](key: GetByPK[P], product: P): Option[Access[P]] = {
    val name = product.getClass.getName
    val lens = TxProtoLens[P](product)(key.ofA)
    Option(AccessImpl(product,Option(lens),NameMetaAttr(name) :: Nil))
  }
}

case class AccessImpl[P](
  initialValue: P, updatingLens: Option[Lens[Context, P] with Product], metaList: List[AbstractMetaAttr]
) extends Access[P] {
  def +(metaAttrs: AbstractMetaAttr*): AccessImpl[P] = copy(metaList = metaList ::: metaAttrs.toList)
  def to[V](inner: ProdLens[P,V]): Access[V] = {
    val rValue = inner.of(initialValue)
    val rLens = updatingLens.map(l=>ComposedLens(l,inner))
    val rMeta = metaList ::: inner.metaList
    AccessImpl[V](rValue,rLens,rMeta)
  }
}

case class ComposedLens[C,T,I](
  outer: Lens[C,T] with Product, inner: Lens[T,I] with Product
) extends AbstractLens[C,I] {
  def set: I => C => C = item => outer.modify(inner.set(item))
  def of: C => I = container => inner.of(outer.of(container))
}

case class TxProtoLens[V<:Product](initialValue: V)(mapOf: AssembledContext=>Map[SrcId,V]) extends AbstractLens[Context,V] {
  private def className = initialValue.getClass.getName
  private def srcId = ToPrimaryKey(initialValue)
  def of: Context => V = local => mapOf(local).getOrElse(srcId,initialValue)
  def set: V => Context => Context = value => local => {
    if(initialValue != of(local)) throw new Exception(s"'$initialValue' != '${of(local)}'")
    val eventsC = List(UpdateLEvent(srcId, className, value))
    val eventsA = LEvent.update(value)
    if(eventsC != eventsA) throw new Exception(s"'$eventsC' != '$eventsA'")
    TxAdd(eventsC)(local)
  }
}
