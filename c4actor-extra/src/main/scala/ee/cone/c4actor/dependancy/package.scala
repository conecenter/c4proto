package ee.cone.c4actor


package object CtxType {
  type Ctx = Map[Request, _]
  type Request = Product
}