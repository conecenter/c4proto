package ee.cone.c4actor

trait FilterPredicateApi[Model <: Product] {
  def accesses: List[Access[_]]

  def condition: Condition[Model]
}
