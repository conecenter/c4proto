package ee.cone.c4actor.dep

object DepTypeContainer {
  type DepCtx = Map[DepRequest, _]
  type DepRequest = Product
  type ContextId = String
}