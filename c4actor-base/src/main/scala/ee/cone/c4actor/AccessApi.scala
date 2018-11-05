package ee.cone.c4actor

//import ee.cone.c4actor.Types.SrcId


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

