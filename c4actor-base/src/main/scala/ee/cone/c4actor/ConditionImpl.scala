
package ee.cone.c4actor

class ModelConditionFactoryImpl[Model] extends ModelConditionFactory[Model] {
  def of[OtherModel]: ModelConditionFactory[OtherModel] =
    new ModelConditionFactoryImpl[OtherModel]

  def ofWithCl[OtherModel]: Class[OtherModel] ⇒ ModelConditionFactory[OtherModel] = cl ⇒
    new ModelConditionFactoryImpl[OtherModel]

  def intersect: (Condition[Model], Condition[Model]) ⇒ Condition[Model] = {
    case (AnyCondition(), left) ⇒ left
    case (right, AnyCondition()) ⇒ right
    case (left, right) ⇒ IntersectCondition(left, right)
  }

  def union: (Condition[Model], Condition[Model]) ⇒ Condition[Model] = {
    case (AnyCondition(), _) ⇒ AnyCondition()
    case (_, AnyCondition()) ⇒ AnyCondition()
    case (left, right) ⇒ UnionCondition(left, right)
  }

  def any: Condition[Model] =
    AnyCondition()

  def leaf[By <: Product, Field](lens: ProdLens[Model, Field], by: By, byOptions: List[MetaAttr])(
    implicit check: ConditionCheck[By, Field]
  ): Condition[Model] = {
    val preparedBy = check.prepare(byOptions)(by)
    if (check.defaultBy.exists(default ⇒ default(preparedBy)))
      AnyCondition()
    else
      ProdConditionImpl(filterMetaList(lens), preparedBy)(check.check(preparedBy), lens.of)
  }

  def filterMetaList[Field]: ProdLens[Model, Field] ⇒ List[MetaAttr] =
    _.metaList.collect { case l: NameMetaAttr ⇒ l }
}

case class ProdConditionImpl[By <: Product, Model, Field](
  metaList: List[MetaAttr], by: By
)(
  fieldCheck: Field ⇒ Boolean, of: Model ⇒ Field
) extends ProdCondition[By, Model] {
  def check(model: Model): Boolean = fieldCheck(of(model))
}

case class IntersectCondition[Model](
  left: Condition[Model],
  right: Condition[Model]
) extends Condition[Model] {
  def check(line: Model): Boolean = left.check(line) && right.check(line)
}

case class UnionCondition[Model](
  left: Condition[Model],
  right: Condition[Model]
) extends Condition[Model] {
  def check(line: Model): Boolean = left.check(line) || right.check(line)
}

case class AnyCondition[Model]() extends Condition[Model] {
  def check(line: Model): Boolean = true
}
