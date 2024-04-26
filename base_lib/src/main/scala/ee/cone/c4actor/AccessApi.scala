package ee.cone.c4actor

//import ee.cone.c4actor.Types.SrcId


trait Access[C] extends Product{
  def updatingLens: Option[Lens[Context,C]]
  def initialValue: C
  def metaList: List[AbstractMetaAttr]
  def to[I](inner: ProdLens[C,I]): Access[I]
  def +(metaAttrs: AbstractMetaAttr*): Access[C]
}

trait RModelAccessFactory {
  def to[P <: Product](key: GetByPK[P], product: P): Access[P]
}