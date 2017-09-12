package ee.cone.c4actor

trait ModelAccessFactory {
  def conducts[P<:Product](product: P): Nothing = throw new Exception("ProdLensNotExpanded")
  def ofModel[P<:Product](product: P): Option[ModelAccess[P]]
}
trait ModelAccess[I<:Product] {
  def ofField[V](of: I=>V, set: V=>I=>I, postfix: String): FieldAccess[V]
}
trait FieldAccess[I] {
  def updatingLens: Option[Lens[Context,I]]
  def name: String
  def initialValue: I
}

case object ModelAccessFactoryKey extends SharedComponentKey[ModelAccessFactory]

trait FieldMetaBuilder[Model,Value] {
  def ofField[V](f: Model⇒V): FieldMetaBuilder[Model,Value]
  def ofField(of: Model=>Any, set: Nothing=>Model=>Model, postfix: String): FieldMetaBuilder[Model,Value]
  def set(value: Value): List[Injectable]
  def model[P](cl: Class[P]): FieldMetaBuilder[P, Value]
  def attr[V](key: SharedComponentKey[Map[String,V]]): FieldMetaBuilder[Model,V]
}