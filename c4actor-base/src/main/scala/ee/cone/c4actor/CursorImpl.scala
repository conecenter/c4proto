package ee.cone.c4actor

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.{AssembledKey, Single, ToPrimaryKey}
import ee.cone.c4assemble.Types.Index

/*
case class TodoTask(comments: String)
@prodLens
object Test {
  val taskCursor: Cursor[TodoTask] = ???
  def strView(cursor: Cursor[String]): Unit = ???
  //strView(taskCursor.prodLens(_.comments))
  strView(taskCursor % (_.comments))
}
*/

object CursorFactoryImpl extends CursorFactory with ToInject {
  def toInject: List[Injectable] = CursorFactoryKey.set(this)
  def forOriginal[P <: Product](product: P): ProductCursor[P] = {
    val name = product.getClass.getName
    val inner = TxProtoLens[P](name, ByPK.raw(name), ToPrimaryKey(product))
    val lens = NonOptProdLens(inner, product)
    ProductCursorImpl(name,product,Option(lens))
  }
}

case class ProductCursorImpl[P<:Product](
  name: String, value: P, lens: Option[Lens[Context, P]]
) extends ProductCursor[P] {
  def %[V](of: P ⇒ V): Cursor[V] = throw new Exception("macro was not expanded")
  def %[V](of: P ⇒ V, set: V ⇒ P ⇒ P, postfix: String): Cursor[V] = {
    val rName = name + postfix
    val rLens = lens.map(l⇒ComposedLens(l,ProdValLens(rName)(of,set)))
    ValueCursor(rName,of(value),rLens)
  }
}

case class ValueCursor[V](
  name: String, value: V, lens: Option[Lens[Context, V]]
) extends Cursor[V]

case class ProdValLens[P<:Product,V](name: String)(val of: P ⇒ V, val set: V ⇒ P ⇒ P) extends AbstractLens[P,V]

case class ComposedLens[C,T,I](
  outer: Lens[C,T], inner: Lens[T,I]
) extends AbstractLens[C,I] {
  def set: I ⇒ C ⇒ C = item ⇒ outer.modify(inner.set(item))
  def of: C ⇒ I = container ⇒ inner.of(outer.of(container))
}

case class NonOptProdLens[C,V<:Product](
  inner: Lens[C,Option[V]], default: V
) extends AbstractLens[C,V] {
  def of: C ⇒ V = c ⇒ inner.of(c).getOrElse(default)
  def set: V ⇒ C ⇒ C = v ⇒ inner.set(Option(v))
}

case class TxProtoLens[V<:Product](
  className: String, key: AssembledKey[Index[SrcId,V]], srcId: SrcId
) extends AbstractLens[Context,Option[V]] {
  def of: Context ⇒ Option[V] =
    local ⇒ Single.option(key.of(local.assembled).getOrElse(srcId, Nil))
  def set: Option[V] ⇒ Context ⇒ Context = value ⇒ {
    val event = List(LEvent(srcId, className, value))
    value.map(LEvent.update).foreach(ev⇒assert(ev==event,s"$ev != $event"))
    TxAdd(event)
  }
}