package ee.cone.c4actor

object ProdLens {
  def of[C, I](of: C ⇒ I, meta: MetaAttr*): ProdLens[C, I] =
    throw new Exception("not expanded")

  def ofSet[C, I](of: C ⇒ I, set: I ⇒ C ⇒ C, name: String, meta: MetaAttr*): ProdLens[C, I] =
    ProdLens[C, I](NameMetaAttr(name) :: meta.toList)(of, set)
}

case class NameMetaAttr(value: String) extends MetaAttr

case class ProdLens[C, I](metaList: List[MetaAttr])(val of: C ⇒ I, val set: I ⇒ C ⇒ C) extends AbstractLens[C, I] {
  def to[V](inner: ProdLens[I, V]): ProdLens[C, V] =
    ProdLens[C, V](metaList ::: inner.metaList)(
      container => inner.of(of(container)),
      item => modify(inner.set(item))
    )
}