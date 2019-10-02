package ee.cone.c4actor

import ee.cone.c4assemble.ToPrimaryKey
import ee.cone.c4proto.c4component

@c4component("ModelAccessFactoryApp") class ModelAccessFactoryImpl extends ModelAccessFactory {
  def to[P <: Product](product: P): Option[Access[P]] = {
    val name = product.getClass.getName
    val lens = TxProtoLens[P](product)
    Option(AccessImpl(product,Option(lens),NameMetaAttr(name) :: Nil))
  }
}

case class AccessImpl[P](
  initialValue: P, updatingLens: Option[Lens[Context, P] with Product], metaList: List[AbstractMetaAttr]
) extends Access[P] {
  def to[V](inner: ProdLens[P,V]): Access[V] = {
    val rValue = inner.of(initialValue)
    val rLens = updatingLens.map(l=>ComposedLens(l,inner))
    val rMeta = metaList ::: inner.metaList
    AccessImpl[V](rValue,rLens,rMeta)
  }

  def zoom: Access[P] = AccessImpl[P](initialValue,
    MakeTxProtoLens(initialValue),
    metaList)
}

case class ComposedLens[C,T,I](
  outer: Lens[C,T] with Product, inner: Lens[T,I] with Product
) extends AbstractLens[C,I] {
  def set: I => C => C = item => outer.modify(inner.set(item))
  def of: C => I = container => inner.of(outer.of(container))
}

case object MakeTxProtoLens {
  def apply[P](initialValue: P): Option[Lens[Context, P] with Product] =
    initialValue match {
      case a:Product => Option(TxProtoLens(a)).asInstanceOf[Option[Lens[Context, P] with Product]]
      case _ => None
    }
}

case class TxProtoLens[V<:Product](initialValue: V) extends AbstractLens[Context,V] {
  private def className = initialValue.getClass.getName
  private def srcId = ToPrimaryKey(initialValue)
  private def key = ByPrimaryKeyGetter(className)
  def of: Context => V = local => key.of(local).getOrElse(srcId,initialValue)
  def set: V => Context => Context = value => local => {
    if(initialValue != of(local)) throw new Exception(s"'$initialValue' != '${of(local)}'")
    val eventsC = List(UpdateLEvent(srcId, className, value))
    val eventsA = LEvent.update(value)
    if(eventsC != eventsA) throw new Exception(s"'$eventsC' != '$eventsA'")
    TxAdd(eventsC)(local)
  }
}
