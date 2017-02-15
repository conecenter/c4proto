package ee.cone.c4actor

import Function.chain
import LEvent._
import ee.cone.c4actor.PCProtocol.{RawChildNode, RawParentNode}

object Measure {
  def apply(f: ()⇒Unit): Option[Long] = {
    val start = System.currentTimeMillis
    f()
    Option(System.currentTimeMillis-start)
  }
}

object NotEffectiveAssemblerTest extends App {
  val app = new AssemblerTestApp
  val emptyWorld = app.qReducer.createWorld(Map())
  val nodes = Seq(RawParentNode("1","P-1")) ++
    (1 to 10000).map(_.toString).map(srcId⇒RawChildNode(srcId,"0",s"C-$srcId"))
  val local = app.qReducer.createTx(emptyWorld)(Map())

  Measure { () ⇒
    chain(nodes.map(update).map(add))(local)
  }.foreach(t⇒println(s"bad join with many add-s takes $t ms"))

  Measure { () ⇒
    chain(Seq(add(nodes.flatMap(update))))(local)
  }.foreach(t⇒println(s"bad join with single add takes $t ms"))
}
