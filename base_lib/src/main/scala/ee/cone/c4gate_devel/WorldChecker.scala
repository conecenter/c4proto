package ee.cone.c4gate_devel

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.Index
import ee.cone.c4assemble._
import ee.cone.c4di._
import okio.ByteString
import java.util

@c4("WorldCheckerApp") final class WorldCheckerReadModelAdd(
  inner: ReadModelAdd,
  readModelUtil: ReadModelUtil,
  indexUtil: IndexUtil,
  config: ListConfig,
) extends ReadModelAdd with LazyLogging {
  val postfix: String = Single.option(config.get("C4WORLD_CHECK_ORDER")).fold("")(order => "f" * order.toInt)
  def add(executionContext: OuterExecutionContext, events: Seq[RawEvent]): ReadModel=>ReadModel =
    inner.add(executionContext,events).andThen{ assembled =>
      if(Single(events).srcId.endsWith(postfix)) report(assembled) // from FileConsumer events go 1 by 1
      assembled
    }
  def report(assembled: ReadModel): Unit = {
    val productWorldChecker = new ProductWorldChecker
    readModelUtil.toMap(assembled).toList.collect{
      case (worldKey: JoinKey, index: Index) if !worldKey.was && worldKey.keyAlias == "SrcId" =>
        (for {
          pk <- indexUtil.keyIterator(index).toList.sortBy(_.toString)
          value <- indexUtil.getValues(index,pk,"").toList.sortBy(ToPrimaryKey(_))
        } yield pk -> value).groupBy(_._2.getClass.getName).toList.sortBy(_._1).map{
          case (clName,res) =>
            s"cl3 ${worldKey.valueClassName} $clName kv-hc ${res.hashCode}"
        }
    }.flatten.sorted.foreach{ l => logger.info(l) }
  }
}

/*
//val res0 = comp.keyIterator(index).toList.sortBy(_.toString).map{ pk =>
//  (pk, indexUtil.getValues(index,pk,"").toList.sortBy(ToPrimaryKey(_)))
//}
//val khc = res0.map(_._1).hashCode
//for(l <- productWorldChecker.check(res0))
//  logger.warn(s"non-product-6 ${worldKey.valueClassName} : $l")
//s"cl2 ${worldKey.valueClassName} sz ${res0.size} kv-hc ${res0.hashCode} k-hc: $khc"
*/
class ProductWorldChecker extends LazyLogging {
  def check(l: Seq[Product]): Seq[String] = {
    val wasObj = new util.IdentityHashMap[Any,Boolean]
    val wasBad = new scala.collection.mutable.HashSet[String]
    val okSet = Seq(
      classOf[String],classOf[okio.ByteString],
      classOf[Integer],classOf[java.lang.Long],classOf[BigDecimal],
      classOf[java.lang.Boolean], /*ok?*/classOf[java.lang.Double],
      classOf[PreHashedMurMur3[_]],classOf[PreHashedImpl[_]],
    ).map(_.getName).toSet
    def chk(el: Any, path: List[String]): Unit = if(!wasObj.containsKey(el)){
      wasObj.put(el,true)
      /*if(depthLeft<=0) wasBad.add(s"too deep ${el.getClass.getName}") else */
      el match {
        case l: List[_] => for(e <- l) chk(e, "List"::path)
        case l: Vector[_] => for(e <- l) chk(e, "Vector"::path)
        /*ok?*/case l: Set[_] => for(e <- l) chk(e, "Set"::path)
        /*ok?*/case l: Map[_,_] => for(e <- l) chk(e, "Map"::path)
        /*ok?*/case s: ByteString => ()
        case p: PreHashed[_] => chk(p.value, "PreHashed"::path)
        case p: Product => for(i <- 0 until p.productArity) chk(p.productElement(i), p.productPrefix::path)
        case o if okSet(o.getClass.getName) => ()
        case _ => wasBad.add(s"${el.getClass.getName} in $path")
      }
    }
    for(el <- l) chk(el,Nil)
    wasBad.toList.sorted
  }
}
