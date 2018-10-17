package ee.cone.c4actor

//import ee.cone.c4actor.Types.SrcId

trait MetaAttr extends Product


trait Access[C] extends Product{
  def updatingLens: Option[Lens[Context,C]]
  def initialValue: C
  def metaList: List[MetaAttr]
  def to[I](inner: ProdLens[C,I]): Access[I]
  def zoom: Access[C]
}

trait ModelAccessFactory {
  def to[P<:Product](product: P): Option[Access[P]]
  //def to[P<:Product](cl: Class[P], srcId: SrcId): Option[ModelAccess[P]]
}

case class NameMetaAttr(value: String) extends MetaAttr

trait ProdLens[C,I] extends AbstractLens[C,I] with Product{
  def meta(values: MetaAttr*): ProdLens[C,I]
  def to[V](inner: ProdLens[I,V]): ProdLens[C,V]
  def metaList: List[MetaAttr]
  def lensName: List[NameMetaAttr]
  def metaByName: Map[String, List[MetaAttr]]
}

