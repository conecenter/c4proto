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

object ModelAccessFactoryImpl extends ModelAccessFactory with ToInject {
  def toInject: List[Injectable] = ModelAccessFactoryKey.set(this)
  def ofModel[P <: Product](product: P): Option[ModelAccess[P]] = {
    val name = product.getClass.getName
    val inner = TxProtoLens[P](name, ByPK.raw(name), ToPrimaryKey(product))
    val lens = NonOptProdLens(inner, product)
    Option(ModelAccessImpl(name,product,Option(lens)))
  }
}

case class ModelAccessImpl[P<:Product](
  name: String, model: P, lens: Option[Lens[Context, P]]
) extends ModelAccess[P] {
  def ofField[V](of: P ⇒ V, set: V ⇒ P ⇒ P, postfix: String): FieldAccess[V] = {
    val rName = name + postfix
    val rValue = of(model)
    val checkSet: V ⇒ P ⇒ P = newValue ⇒ currentModel ⇒ {
      assert(rValue==of(currentModel))
      set(newValue)(currentModel)
    }
    val rLens = lens.map(l⇒ComposedLens(l,ProdValLens(rName)(of,checkSet)))
    FieldAccessImpl(rName,rValue,rLens)
  }
}

case class FieldAccessImpl[V](
  name: String, initialValue: V, updatingLens: Option[Lens[Context, V]]
) extends FieldAccess[V]

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

case class FieldMetaBuilderImpl[Model,Value](
  name: String = "", keyOpt: Option[SharedComponentKey[Map[String,Value]]] = None
) extends FieldMetaBuilder[Model,Value] {
  def ofField[V](f: Model⇒V): FieldMetaBuilder[Model,Value] = throw new Exception("macro not expanded")
  def ofField(of: Model=>Any, set: Nothing=>Model=>Model, postfix: String): FieldMetaBuilder[Model,Value] =
    copy(name + postfix)
  def set(value: Value): List[Injectable] =
    keyOpt.get.set(Map(name→value))
  def model[P](cl: Class[P]): FieldMetaBuilder[P, Value] =
    FieldMetaBuilderImpl(cl.getName,keyOpt)
  def attr[V](key: SharedComponentKey[Map[String,V]]): FieldMetaBuilder[Model,V] =
    FieldMetaBuilderImpl(name,Option(key))
}