package ee.cone.c4actor

trait Condition[Model] extends Product {
  def check(model: Model): Boolean
}

trait ModelConditionFactory[Model] {
  def of[OtherModel<:Product]: ModelConditionFactory[OtherModel]
  def ofWithCl[OtherModel <: Product]: Class[OtherModel] ⇒ ModelConditionFactory[OtherModel]
  def intersect: (Condition[Model],Condition[Model]) ⇒ Condition[Model]
  def union: (Condition[Model],Condition[Model]) ⇒ Condition[Model]
  def any: Condition[Model]
  def leaf[By<:Product,Field](lens: ProdLens[Model,Field], by: By, byOptions: List[AbstractMetaAttr])(
    implicit check: ConditionCheck[By,Field]
  ): Condition[Model]
  def filterMetaList[Field]: ProdLens[Model,Field] ⇒ List[AbstractMetaAttr]
}
trait ConditionCheck[By<:Product,Field] extends Product {
  def prepare: List[AbstractMetaAttr] ⇒ By ⇒ By
  def check: By ⇒ Field ⇒ Boolean
  /*
    Checks if incoming By has default value and equals true always
   */
  def defaultBy: Option[By ⇒ Boolean]
}
trait ProdCondition[By<:Product,Model] extends Condition[Model] {
  def by: By
  def metaList: List[AbstractMetaAttr]
}
