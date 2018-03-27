package ee.cone.c4actor

import ee.cone.c4assemble.ToPrimaryKey

trait ModelAccessFactoryApp {
  def modelAccessFactory: ModelAccessFactory = modelAccessFactoryImpl
  private lazy val modelAccessFactoryImpl = ModelAccessFactoryImpl
}


object ModelAccessFactoryImpl extends ModelAccessFactory {
  def to[P <: Product](product: P): Option[Access[P]] = {
    val name = product.getClass.getName
    val lens = TxProtoLens[P](product)
    Option(AccessImpl(product,Option(lens),NameMetaAttr(name) :: Nil))
  }
}

case class AccessImpl[P](
  initialValue: P, updatingLens: Option[Lens[Context, P] with Product], metaList: List[MetaAttr]
) extends Access[P] {
  def to[V](inner: ProdLens[P,V]): Access[V] = {
    val rValue = inner.of(initialValue)
    val rLens = updatingLens.map(l⇒ComposedLens(l,inner))
    val rMeta = metaList ::: inner.metaList
    AccessImpl[V](rValue,rLens,rMeta)
  }
}

case class ComposedLens[C,T,I](
  outer: Lens[C,T] with Product, inner: Lens[T,I] with Product
) extends AbstractLens[C,I] {
  def set: I ⇒ C ⇒ C = item ⇒ outer.modify(inner.set(item))
  def of: C ⇒ I = container ⇒ inner.of(outer.of(container))
}

case class TxProtoLens[V<:Product](initialValue: V) extends AbstractLens[Context,V] {
  private def className = initialValue.getClass.getName
  private def srcId = ToPrimaryKey(initialValue)
  private def key = ByPrimaryKeyGetter(ByPK.raw(className))
  def of: Context ⇒ V = local ⇒ key.of(local).getOrElse(srcId,initialValue)
  def set: V ⇒ Context ⇒ Context = value ⇒ local ⇒ {
    if(initialValue != of(local)) throw new Exception(s"'$initialValue' != '${of(local)}'")
    val eventsC = List(LEvent(srcId, className, Option(value)))
    val eventsA = LEvent.update(value)
    if(eventsC != eventsA) throw new Exception(s"'$eventsC' != '$eventsA'")
    TxAdd(eventsC)(local)
  }
}
