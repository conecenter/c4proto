package ee.cone.c4actor

import ee.cone.c4di.TypeKey

object ProdLens {
  def of[C, I](of: C => I, meta: AbstractMetaAttr*): ProdLensStrict[C, I] =
    throw new Exception("not expanded")

  def ofSetStrict[C, I](of: C => I, set: I => C => C, name: String, clFrom: Class[C], clTo: Class[I], tkFrom: TypeKey, tkTo: TypeKey, meta: AbstractMetaAttr*): ProdLensStrict[C, I] =
    ProdLensStrict[C, I](NameMetaAttr(name) :: meta.toList, clFrom, clTo, tkFrom, tkTo)(of, set)

  def ofSet[C, I](of: C => I, set: I => C => C, name: String, meta: AbstractMetaAttr*): ProdLensNonstrict[C, I] =
    ProdLensNonstrict[C, I](NameMetaAttr(name) :: meta.toList)(of, set)
}

case class NameMetaAttr(value: String) extends AbstractMetaAttr

case class IdMetaAttr(fieldId: Long) extends AbstractMetaAttr

case class ClassesAttr(modelClName: String, fieldClName: String) extends AbstractMetaAttr

case class TypeKeyAttr(from: TypeKey, to: TypeKey) extends AbstractMetaAttr

abstract class ProdLens[C, I] extends AbstractLens[C, I] with Product {
  def metaList: List[AbstractMetaAttr]
  def to[V](inner: ProdLens[I, V]): ProdLens[C, V]
}

case class ProdLensNonstrict[C, I](metaList: List[AbstractMetaAttr])(val of: C => I, val set: I => C => C) extends ProdLens[C, I] {
  def to[V](inner: ProdLens[I, V]): ProdLensNonstrict[C, V] =
    ProdLensNonstrict[C, V](metaList ::: inner.metaList)(
      container => inner.of(of(container)),
      item => modify(inner.set(item))
    )
}

case class ProdLensStrict[C, I](
  extraMetaList: List[AbstractMetaAttr],
  clFrom: Class[C], clTo: Class[I], tkFrom: TypeKey, tkTo: TypeKey
)(
  val of: C => I, val set: I => C => C
) extends ProdLens[C, I] {
  lazy val metaList: List[AbstractMetaAttr] = ee.cone.c4actor.TypeKeyAttr(tkFrom, tkTo) ::
    ee.cone.c4actor.ClassesAttr(clFrom.getName, clTo.getName) :: extraMetaList

  def to[V](inner: ProdLens[I, V]): ProdLens[C, V] = inner match {
    case strictInner: ProdLensStrict[I, V] => ProdLensStrict[C, V](
      extraMetaList ::: strictInner.extraMetaList,
      clFrom, strictInner.clTo, tkFrom, strictInner.tkTo
    )(
      container => inner.of(of(container)),
      item => modify(inner.set(item))
    )
    case _ =>
      ProdLensNonstrict[C, V](metaList ::: inner.metaList)(
        container => inner.of(of(container)),
        item => modify(inner.set(item))
      )
  }
}