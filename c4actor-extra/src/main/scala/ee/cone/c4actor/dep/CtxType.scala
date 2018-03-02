package ee.cone.c4actor.dep

object CtxType {
  type Ctx = Map[Request, _]
  type Request = Product
  type ContextId = String
}