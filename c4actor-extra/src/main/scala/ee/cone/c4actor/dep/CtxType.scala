package ee.cone.c4actor.dep

object CtxType {
  type DepCtx = Map[DepRequest, _]
  type DepRequest = Product
  type ContextId = String
}