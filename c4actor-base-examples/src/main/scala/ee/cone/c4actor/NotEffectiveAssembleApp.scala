package ee.cone.c4actor

import Function.chain
import LEvent._
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.PCProtocol.{RawChildNode, RawParentNode}



object Measure {
  def apply(f: ()⇒Unit): Option[Long] = {
    val start = System.currentTimeMillis
    f()
    Option(System.currentTimeMillis-start)
  }
}

object NotEffectiveAssemblerTest extends App with LazyLogging {
  val app = new AssemblerTestApp
  val nodes = List(RawParentNode("0","P-1")) ++
    (1 to 10000).map(_.toString).map(srcId⇒RawChildNode(srcId,"0",s"C-$srcId"))
  val local = app.contextFactory.updated(Nil)

  Measure { () ⇒
    chain(nodes.map(update).map(TxAdd(_)))(local)
  }.foreach(t⇒logger.info(s"bad join with many add-s takes $t ms"))

  Measure { () ⇒
    chain(List(TxAdd(nodes.flatMap(update))))(local)
  }.foreach(t⇒logger.info(s"bad join with single add takes $t ms"))
}

/*
import scala.collection.immutable.TreeMap

  val orange = Seq(1) //0 to 500000
  val range = (0 to 5000000).reverse //Seq(1,2,3,4,5)

  val s: Seq[Int] = range.toArray.toSeq
  Measure { () ⇒
    var res = 0
    orange.foreach { _ ⇒
      s.foreach{ case(k) ⇒
        res += k
      }
    }
    println(res)
  }.foreach(t⇒println(s"$t"))


  val l = range.toList
  Measure { () ⇒
    var res = 0
    orange.foreach { _ ⇒
      l.foreach { k ⇒
        res += k
      }
    }
    println(res)
  }.foreach(t⇒println(s"$t"))



  val m: TreeMap[Int, Int] = (TreeMap.empty[Int,Int] /: range)((m,i)⇒m + (i→1))
  Measure { () ⇒
    var res = 0
    orange.foreach { _ ⇒
      m.foreach { case (k, v) ⇒
        res += k
      }
    }
    println(res)
  }.foreach(t⇒println(s"$t"))


  val a = range.toArray
  Measure { () ⇒

    var res = 0
    orange.foreach { _ ⇒
      var i = 0
      while(i < a.length) {
        res += a(i)
        i += 1
      }
    }
    println(res)
  }.foreach(t⇒println(s"$t"))


*/

/*
          def getId(e: R) = e.product Element(0) match {
            case s: String ⇒ s
            case _ ⇒ throw new Exception(s"1st field of ${e.getClass.getName} should be primary key")
          }
          val toAddB = new collection.mutable.ArrayBuffer[R]
          val toDelB = new collection.mutable.ArrayBuffer[R]
          d.foreach{ case (node,count)=>
            if(count<0) {
              ???
            } else {
              var i = count
              while(i>0) {
                toAddB += node
                i -= 1
              }
            }
          }
          val toAdd = toAddB.sortBy(getId)
          /*java.util.Arrays.sort(toAdd, new Comparator[R] {
            def compare(o1: R, o2: R): Int = getId(o1).compareTo(getId(o2))
          })*/
          val res = new collection.mutable.ArrayBuffer[R]
          var items = v
          var index = 0
          while(index < toAdd.length || items.nonEmpty) {
            if (items.nonEmpty && (index >= toAdd.length || getId(items.head) < getId(toAdd(index)))) {
              if(toDel.contains(items.head)) toDel(items.head) = toDel(items.head) + 1
              else res += items.head
              items = items.tail
            } else {
              res += toAdd(index)
              index += 1
            }
          }
          res.result().toList
 */


/*
def getId(e: R) = e.product Element(0) match {
  case s: String ⇒ s
  case _ ⇒ throw new Exception(s"1st field of ${e.getClass.getName} should be primary key")
}
def fill(node: R, count: Int) = List.fill(Math.abs(count))(node)
@tailrec def merge(a: List[R], b: List[R], t: List[R]): List[R] = {
  if(a.isEmpty)
    t.reverse ::: b
  else if(b.isEmpty)
    t.reverse ::: a
  else if(getId(a.head)<getId(b.head))
    merge(a.tail, b, a.head :: t)
  else
    merge(a, b.tail, b.head :: t)
}
val(toAdd, toDel) = d.toList.partition{ case (_,count)=> count>0}
val toDelList = toDel.flatMap(tupled(fill))
val toAddList = toAdd.flatMap(tupled(fill))
merge(v.diff(toDelList), toAddList.sortBy(getId), Nil)
old 2k madd 2499 ms sadd 67 ms
old 5k madd 12891 ms sadd 123 ms
new 2k madd 1096 ms sadd 98 ms
new 5k madd 3128 ms sadd 173 ms
new 10k madd 11581 ms sadd 334 ms
*/