package ee.cone.c4actor

import ee.cone.c4assemble.Getter
import ee.cone.c4di.TypeKey

object ProdLens {
  def of[C, I](of: C => I, meta: AbstractMetaAttr*): ProdLensStrict[C, I] =
    throw new Exception("not expanded")

  def ofFunc[C, I](fldName: String, of: C => I, meta: AbstractMetaAttr*): ProdGetterStrict[C, I] =
    throw new Exception("not expanded")

  def ofFuncStrict[C, I](of: C => I, name: String, clFrom: Class[C], clTo: Class[I], tkFrom: TypeKey, tkTo: TypeKey, meta: AbstractMetaAttr*): ProdGetterStrict[C, I] =
    ProdGetterStrict[C, I](NameMetaAttr(name) :: meta.toList, clFrom, clTo, tkFrom, tkTo)(of)

  def ofSetStrict[C, I](of: C => I, set: I => C => C, name: String, clFrom: Class[C], clTo: Class[I], tkFrom: TypeKey, tkTo: TypeKey, meta: AbstractMetaAttr*): ProdLensStrict[C, I] =
    ProdLensStrict[C, I](NameMetaAttr(name) :: meta.toList, clFrom, clTo, tkFrom, tkTo)(of, set)

  def ofSet[C, I](of: C => I, set: I => C => C, name: String, meta: AbstractMetaAttr*): ProdLensNonstrict[C, I] =
    ProdLensNonstrict[C, I](NameMetaAttr(name) :: meta.toList)(of, set)
}

case class NameMetaAttr(value: String) extends AbstractMetaAttr

case class IdMetaAttr(fieldId: Long) extends AbstractMetaAttr

case class ClassesAttr(modelClName: String, fieldClName: String) extends AbstractMetaAttr

case class TypeKeyAttr(from: TypeKey, to: TypeKey) extends AbstractMetaAttr

trait GetterWithMetaList[-C, +I] extends Getter[C, I] with Product {
  def metaList: List[AbstractMetaAttr]
}

trait ProdGetter[-C, +I] extends GetterWithMetaList[C, I] {
  def extraMetaList: List[AbstractMetaAttr]
  def tkFrom: TypeKey
  def tkTo: TypeKey
  def clFrom: Class[_ >: C]
  def clTo: Class[_ <: I]
}

abstract class ProdLens[C, I] extends AbstractLens[C, I] with GetterWithMetaList[C, I]{
  def to[V](inner: ProdLens[I, V]): ProdLens[C, V]
}

case class ProdLensNonstrict[C, I](metaList: List[AbstractMetaAttr])(val of: C => I, val set: I => C => C) extends ProdLens[C, I] {
  def to[V](inner: ProdLens[I, V]): ProdLensNonstrict[C, V] =
    ProdLensNonstrict[C, V](metaList ::: inner.metaList)(
      container => inner.of(of(container)),
      item => modify(inner.set(item))
    )
}


case class ProdGetterStrict[C, I](
  extraMetaList: List[AbstractMetaAttr],
  clFrom: Class[_ >: C], clTo: Class[_ <: I], tkFrom: TypeKey, tkTo: TypeKey
)(
  val of: C => I
) extends ProdGetter[C, I] {
  lazy val metaList: List[AbstractMetaAttr] = ee.cone.c4actor.TypeKeyAttr(tkFrom, tkTo) ::
    ee.cone.c4actor.ClassesAttr(clFrom.getName, clTo.getName) :: extraMetaList

  def to[V](inner: ProdGetter[I, V]): ProdGetterStrict[C, V] =
    ProdGetterStrict[C, V](
      extraMetaList ::: inner.extraMetaList,
      clFrom, inner.clTo, tkFrom, inner.tkTo
    )(
      container => inner.of(of(container))
    )

  def escalate[C1 <: C]: ProdGetterStrict[C1, I] =
     this.asInstanceOf[ProdGetterStrict[C1, I]]
}

case class ProdLensStrict[C, I](
  extraMetaList: List[AbstractMetaAttr],
  clFrom: Class[C], clTo: Class[I], tkFrom: TypeKey, tkTo: TypeKey
)(
  val of: C => I, val set: I => C => C
) extends ProdLens[C, I] with ProdGetter[C, I] {
  lazy val metaList: List[AbstractMetaAttr] = ee.cone.c4actor.TypeKeyAttr(tkFrom, tkTo) ::
    ee.cone.c4actor.ClassesAttr(clFrom.getName, clTo.getName) :: extraMetaList

  def toStrict[V](strictInner: ProdLensStrict[I, V]): ProdLensStrict[C, V] =
    ProdLensStrict[C, V](
      extraMetaList ::: strictInner.extraMetaList,
      clFrom, strictInner.clTo, tkFrom, strictInner.tkTo
    )(
      container => strictInner.of(of(container)),
      item => modify(strictInner.set(item))
    )

  def to[V](inner: ProdGetterStrict[I, V]): ProdGetterStrict[C, V] =
    ProdGetterStrict[C, V](
      extraMetaList ::: inner.extraMetaList,
      clFrom, inner.clTo, tkFrom, inner.tkTo
    )(
      container => inner.of(of(container))
    )

  def to[V](inner: ProdLens[I, V]): ProdLens[C, V] = inner match {
    case strictInner: ProdLensStrict[I, V] => toStrict(strictInner)
    case _ =>
      ProdLensNonstrict[C, V](metaList ::: inner.metaList)(
        container => inner.of(of(container)),
        item => modify(inner.set(item))
      )
  }
}