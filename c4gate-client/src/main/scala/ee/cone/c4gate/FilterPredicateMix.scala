package ee.cone.c4gate

trait FilterPredicateBuilderApp {
  def sessionAttrAccessFactory: SessionAttrAccessFactory
  lazy val filterPredicateBuilder: FilterPredicateBuilder =
    new FilterPredicateBuilderImpl(sessionAttrAccessFactory)
}
