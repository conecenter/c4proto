package ee.cone.c4actor

import ee.cone.c4di.{ProductCheck, c4}

@c4("ProductCheckerApp") final class ProductCheckerImpl(
  checkList: List[ProductCheck],
  allowList: List[ProductCheckMore]
)(
  allow: Map[String,ProductCheckMore] = CheckedMap(allowList.map(v=>v.className->v)),
) extends Executable {
  def check(e: Any): Boolean = {
    val cl = e match {
      case cl: Class[_] => cl
      case p: Product => p.productElement(0).asInstanceOf[Class[_]]
    }
    allow.get(cl.getName) match {
      case None => classOf[Product].isAssignableFrom(cl)
      case Some(h) => h.more(e).forall(check)
    }
  }
  def run(): Unit =
    for {
      c <- checkList
      e <- c.list
    } assert(check(e),s"${c.getClass.getName} $e")
}

trait ProductCheckMore {
  def className: String
  def more(e: Any): List[_]
}