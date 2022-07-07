package ee.cone.c4actor

import ee.cone.c4di.{ProductCheck, c4, provide}
import okio.ByteString

@c4("ProductCheckerApp") final class ProductCheckerImpl(
  checkList: List[ProductCheck],
  allowList: List[ProductCheckMore]
)(
  allow: Map[String,ProductCheckMore] = CheckedMap(allowList.map(v=>v.className->v)),
) extends Executable with Early {
  def check(cl: Class[_], e: Any): Boolean = allow.get(cl.getName) match {
    case None => classOf[Product].isAssignableFrom(cl)
    case Some(h) => h.more(e).forall(check)
  }
  def check(e: Any): Boolean = e match {
    case cl: Class[_] => check(cl, e)
    case (s,a,b) if s == "OR" => check(a) || check(b)
    case p: Product => p.productElement(0) match {
      case cCl: Class[_] => check(cCl, e)
      case o => throw new Exception(s"$e")
    }
  }
  def run(): Unit = {
    val alerts = (for {
      c <- checkList
      e <- c.list if !check(e)
    } yield (c.getClass.getName,s"$e"))
      .groupMap(_._2)(_._1).transform((k,v)=>v.length).toList.sortBy(_._2)
    alerts.foreach{ case (t,c) => println(s"ProductCheck alert: $c $t") }
    if(alerts.size > 0)
      throw new Exception(s"ProductCheck alerts: ${alerts.map(_._2).sum}")
  }
}

trait ProductCheckMore {
  def className: String
  def more(e: Any): List[_]
}

class DefProductCheckMore(cl: Class[_]) extends ProductCheckMore {
  def className: String = cl.getName
  def more(e: Any): List[_] = e match {
    case _:Class[_] => Nil
    case p:Product => p.productIterator.toList.tail
  }
}

@c4("ProductCheckerApp") final class ProductCheckMoreProvider {
  @provide def get: Seq[ProductCheckMore] = Seq(
    classOf[String], classOf[Boolean],
    classOf[Long], classOf[Int], classOf[BigDecimal], classOf[Double],
    classOf[ByteString],
    classOf[List[_]]

  ).map(new DefProductCheckMore(_))
}