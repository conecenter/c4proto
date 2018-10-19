package ee.cone.c4actor

object ProdLens {
  def of[C,I](of: C⇒I, meta: MetaAttr*): ProdLens[C,I] =
    throw new Exception("not expanded")
  def ofSet[C,I](of: C⇒I, set: I⇒C⇒C, name: String, meta: MetaAttr*): ProdLens[C,I] =
    ProdLensImpl[C,I](NameMetaAttr(name) :: meta.toList)(of,set)
}

case class ProdLensImpl[C,I](metaList: List[MetaAttr])(val of: C⇒I, val set: I⇒C⇒C)
  extends ProdLens[C,I]
{
  def meta(values: MetaAttr*): ProdLens[C,I] = ProdLensImpl[C,I](metaList ++ values)(of,set)
  def to[V](inner: ProdLens[I,V]): ProdLens[C,V] =
    ProdLensImpl[C,V](metaList ::: inner.metaList)(
      container => inner.of(of(container)),
      item => modify(inner.set(item))
    )
  lazy val metaByName: Map[String, List[MetaAttr]] = metaList.groupBy(_.getClass.getName)
  lazy val lensName: List[NameMetaAttr] = metaByName.get(classOf[NameMetaAttr].getName).toList.flatten.asInstanceOf[List[NameMetaAttr]]
}

trait ProdLensFactory {
  def create[C, I](of: C⇒I, set: I⇒C⇒C, name: String, meta: MetaAttr*):ProdLens[C,I]
}

case object ProdLensFactoryImpl extends ProdLensFactory {
  def create[C, I](of: C => I, set: I => C => C, name: String, meta: MetaAttr*): ProdLens[C, I] = ProdLensImpl[C,I](NameMetaAttr(name) :: meta.toList)(of,set)
}

case class NameMetaAttr(value: String) extends MetaAttr

trait ProdLens[C,I] extends AbstractLens[C,I] with Product{
  def meta(values: MetaAttr*): ProdLens[C,I]
  def to[V](inner: ProdLens[I,V]): ProdLens[C,V]
  def metaList: List[MetaAttr]
  def lensName: List[NameMetaAttr]
  def metaByName: Map[String, List[MetaAttr]]
}