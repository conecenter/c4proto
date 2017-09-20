package ee.cone.c4actor

//import ee.cone.c4actor.Types.SrcId

trait MetaAttr extends Product

trait Access[C] {
  def updatingLens: Option[Lens[Context,C]]
  def initialValue: C
  def metaList: List[MetaAttr]
  def to[I](inner: ProdLens[C,I]): Access[I]
}

case object ModelAccessFactoryKey extends SharedComponentKey[ModelAccessFactory]
trait ModelAccessFactory {
  def to[P<:Product](product: P): Option[Access[P]]
  //def to[P<:Product](cl: Class[P], srcId: SrcId): Option[ModelAccess[P]]
}

case class NameMetaAttr(value: String) extends MetaAttr

object ProdLens {
  def of[C,I](of: C⇒I, meta: MetaAttr*): ProdLens[C,I] =
    throw new Exception("not expanded")
  def ofSet[C,I](of: C⇒I, set: I⇒C⇒C, name: String, meta: MetaAttr*): ProdLens[C,I] =
    ProdLens[C,I](NameMetaAttr(name) :: meta.toList)(of,set)
}

case class ProdLens[C,I](metaList: List[MetaAttr])(val of: C⇒I, val set: I⇒C⇒C)
  extends AbstractLens[C,I]
{
  def meta(values: MetaAttr*): ProdLens[C,I] = ProdLens[C,I](metaList ++ values)(of,set)
}

