
package ee.cone.c4actor

@c4component case class ModelConditionFactoryImpl() extends ModelConditionFactory {
  def of[Model]: ModelConditionBuilder[Model] =
    new ModelConditionBuilderImpl[Model]
}

class ModelConditionBuilderImpl[Model] extends ModelConditionBuilder[Model] {
  def intersect: (Condition[Model],Condition[Model]) ⇒ Condition[Model] =
    IntersectCondition(_,_)
  def union: (Condition[Model],Condition[Model]) ⇒ Condition[Model] =
    UnionCondition(_,_)
  def any: Condition[Model] =
    AnyCondition()
  def leaf[By<:Product,Field](lens: ProdLens[Model,Field], by: By)(
    implicit check: ConditionCheck[By,Field]
  ): Condition[Model] =
    ProdConditionImpl(filterMetaList(lens), by)(check.check(by),lens.of)
  def filterMetaList[Field]: ProdLens[Model,Field] ⇒ List[MetaAttr] =
    _.metaList.collect{ case l: NameMetaAttr ⇒ l }
}

case class ProdConditionImpl[By<:Product,Model,Field](
  metaList: List[MetaAttr], by: By
)(
  fieldCheck: Field⇒Boolean, of: Model⇒Field
) extends ProdCondition[By,Model] {
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

