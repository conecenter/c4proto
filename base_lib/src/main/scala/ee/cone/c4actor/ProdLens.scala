package ee.cone.c4actor

import ee.cone.c4actor.Types.{FieldId, TypeId}
import ee.cone.c4assemble.Getter
import ee.cone.c4di.TypeKey

object ProdLens {
  //for use inside @fieldAccess
  def of[C, I](of: C => I, meta: AbstractMetaAttr*): ProdLensStrict[C, I] =
    throw new Exception("not expanded")
  def ofSet[C, I](of: C => I, set: I => C => C, meta: AbstractMetaAttr*): ProdLensStrict[C, I] =
    throw new Exception("not expanded")
  def from[C, I](inner: AbstractProdLens[C,I], meta: AbstractMetaAttr*): ProdLensStrict[C, I] =
    throw new Exception("not expanded")
}

object CreateProdLens {
  //for generated code only
  def from[C, I](clFrom: Class[C], clTo: Class[I], tkFrom: TypeKey, tkTo: TypeKey)(fldName: String)(inner: AbstractProdLens[C,I], meta: AbstractMetaAttr*): ProdLensStrict[C, I] =
    check(ProdLensStrict[C, I](NameMetaAttr(fldName+"."+inner.hashCode) :: meta.toList, tkFrom, tkTo)(clFrom, clTo, inner.of, inner.set))
  def ofSet[C, I](clFrom: Class[C], clTo: Class[I], tkFrom: TypeKey, tkTo: TypeKey)(fldName: String)(of: C => I, set: I => C => C, meta: AbstractMetaAttr*): ProdLensStrict[C, I] =
    check(ProdLensStrict[C, I](NameMetaAttr(fldName) :: meta.toList, tkFrom, tkTo)(clFrom, clTo, of, set))
  def check[C, I](l: ProdLensStrict[C, I]): ProdLensStrict[C, I] = {
    assert(l.clFrom == l.tkFrom.cl && l.clTo == l.tkTo.cl)
    l
  }
}

object ProdGetter {
  //for use inside @fieldAccess
  def from[C, I](inner: AbstractProdGetter[C,I], meta: AbstractMetaAttr*): ProdGetterStrict[C, I] =
    throw new Exception("not expanded")
  def of[C, I](of: C => I, meta: AbstractMetaAttr*): ProdGetterStrict[C, I] =
    throw new Exception("not expanded")
}

object CreateProdGetter {
  //for generated code only
  def from[C, I](clFrom: Class[C], clTo: Class[I], tkFrom: TypeKey, tkTo: TypeKey)(fldName: String)(inner: AbstractProdGetter[C,I], meta: AbstractMetaAttr*): ProdGetterStrict[C, I] =
    ProdGetterStrict[C, I](NameMetaAttr(fldName+"."+inner.hashCode) :: meta.toList, tkFrom, tkTo)(clFrom, clTo, inner.of)
  def of[C, I](clFrom: Class[C], clTo: Class[I], tkFrom: TypeKey, tkTo: TypeKey)(fldName: String)(of: C => I, meta: AbstractMetaAttr*): ProdGetterStrict[C, I] =
    ProdGetterStrict[C, I](NameMetaAttr(fldName) :: meta.toList, tkFrom, tkTo)(clFrom, clTo, of)
}

@deprecated("results in bad product") object UnsafeProdGetter {
  //for minimal use inside @fieldAccess
  def of[C, I](fldName: String)(of: C => I, meta: AbstractMetaAttr*): ProdGetterStrict[C, I] =
    throw new Exception("not expanded")
}

case class NameMetaAttr(value: String) extends AbstractMetaAttr

case class IdMetaAttr(fieldId: Long) extends AbstractMetaAttr

case class OrigIdAttr(typeId: Long) extends AbstractMetaAttr

case class FieldNameAttr(fieldName: String) extends AbstractMetaAttr

case class ClassesAttr(modelClName: String, fieldClName: String) extends AbstractMetaAttr

case class TypeKeyAttr(from: TypeKey, to: TypeKey) extends

case object SrcIdFieldAttr extends AbstractMetaAttr

trait GetterWithMetaList[C, +I] extends Getter[C, I] with Product {
  def metaList: List[AbstractMetaAttr]
  def +(metaAttrs: AbstractMetaAttr*): GetterWithMetaList[C, I]
}

trait AbstractProdGetter[C,+I] extends Getter[C, I] with Product {
  type OutOfType[J] = C=>J
}

abstract class ProdGetter[C, I] extends AbstractProdGetter[C, I] with GetterWithMetaList[C, I] {
  def +(metaAttrs: AbstractMetaAttr*): ProdGetter[C, I]
  def to[V](inner: ProdGetter[I, V]): ProdGetter[C, V]
  def extraMetaList: List[AbstractMetaAttr]
  def tkFrom: TypeKey
  def tkTo: TypeKey
  def clFrom: Class[C]
  def clTo: Class[I]
}

trait AbstractProdLens[C,I] extends AbstractLens[C, I] with AbstractProdGetter[C, I] with Product {
  type OfType = C=>I
  type SetType = I=>C=>C
}

abstract class ProdLens[C, I] extends ProdGetter[C, I] with AbstractProdLens[C, I] {
  def +(metaAttrs: AbstractMetaAttr*): ProdLens[C, I]
  def to[V](inner: ProdLens[I, V]): ProdLens[C, V]
}

object ProdGetterStrict {
  @deprecated def apply[C, I](
    extraMetaList: List[AbstractMetaAttr],
    clFrom: Class[C], clTo: Class[I],
    tkFrom: TypeKey, tkTo: TypeKey
  )(
    of: C => I
  ): ProdGetterStrict[C, I] = ProdGetterStrict[C, I](extraMetaList, tkFrom, tkTo)(clFrom, clTo, of)
}

case class ProdGetterStrict[C, I](
  extraMetaList: List[AbstractMetaAttr],
  tkFrom: TypeKey, tkTo: TypeKey
)(
  val clFrom: Class[C], val clTo: Class[I], val of: C => I
) extends ProdGetter[C, I] {
  def +(metaAttrs: AbstractMetaAttr*): ProdGetterStrict[C, I] =
    ProdGetterStrict[C, I](
      extraMetaList ::: metaAttrs.toList,
      tkFrom, tkTo
    )(
      clFrom, clTo, of
    )

  lazy val metaList: List[AbstractMetaAttr] = ee.cone.c4actor.TypeKeyAttr(tkFrom, tkTo) ::
    ee.cone.c4actor.ClassesAttr(clFrom.getName, clTo.getName) :: extraMetaList

  def to[V](inner: ProdGetter[I, V]): ProdGetter[C, V] =
    ProdGetterStrict[C, V](
      extraMetaList ::: inner.extraMetaList,
      tkFrom, inner.tkTo
    )(
      clFrom, inner.clTo, container => inner.of(of(container))
    )
}

case class ProdLensStrict[C, I](
  extraMetaList: List[AbstractMetaAttr], tkFrom: TypeKey, tkTo: TypeKey
)(
  val clFrom: Class[C], val clTo: Class[I], val of: C => I, val set: I => C => C
) extends ProdLens[C, I] {
  def +(metaAttrs: AbstractMetaAttr*): ProdLensStrict[C, I] = copy(extraMetaList = extraMetaList ::: metaAttrs.toList)(clFrom, clTo, of, set)
  lazy val metaList: List[AbstractMetaAttr] = ee.cone.c4actor.TypeKeyAttr(tkFrom, tkTo) ::
    ee.cone.c4actor.ClassesAttr(clFrom.getName, clTo.getName) :: extraMetaList

  def toStrict[V](strictInner: ProdLensStrict[I, V]): ProdLensStrict[C, V] =
    CreateProdLens.check(ProdLensStrict[C, V](
      extraMetaList ::: strictInner.extraMetaList,
      tkFrom, strictInner.tkTo
    )(
      clFrom, strictInner.clTo,
      container => strictInner.of(of(container)),
      item => modify(strictInner.set(item))
    ))

  def to[V](inner: ProdGetter[I, V]): ProdGetter[C, V] =
    ProdGetterStrict[C, V](
      extraMetaList ::: inner.extraMetaList,
      tkFrom, inner.tkTo
    )(
      clFrom, inner.clTo, container => inner.of(of(container))
    )

  def to[V](inner: ProdLens[I, V]): ProdLens[C, V] =
    CreateProdLens.check(ProdLensStrict[C, V](
      metaList ::: inner.metaList,
      tkFrom, inner.tkTo
    )(
      clFrom, inner.clTo,
      container => inner.of(of(container)),
      item => modify(inner.set(item))
    ))
}