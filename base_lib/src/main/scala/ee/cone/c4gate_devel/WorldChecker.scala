package ee.cone.c4gate_devel

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.Index
import ee.cone.c4assemble._
import ee.cone.c4di._
import okio.ByteString
import java.util

@c4("WorldCheckerApp") final class WorldCheckerReadModelAdd(
  inner: RichRawWorldReducer,
  readModelUtil: ReadModelUtil,
  indexUtil: IndexUtil,
  config: ListConfig,
) extends RichRawWorldReducer with LazyLogging {
  val postfix: String = Single.option(config.get("C4WORLD_CHECK_ORDER")).fold("")(order => "f" * order.toInt)
  def reduce(context: Option[SharedContext with AssembledContext], events: List[RawEvent]): RichContext = {
    val willContext = inner.reduce(context, events)
    if(Single(events).srcId.endsWith(postfix)) report(willContext.assembled) // from FileConsumer events go 1 by 1
    willContext
  }
  def report(assembled: ReadModel): Unit = {
    for(r <- config.get("C4WORLD_CHECK_SELECT")) reportSelect(assembled, r)
    for(r <- config.get("C4WORLD_CHECK_HASHES")) reportHashes(assembled, r)
    for(r <- config.get("C4WORLD_CHECK_PRODUCTS"))  reportBadProducts(assembled)
  }

  def reportHashes(assembled: ReadModel, opt: String): Unit = {
    readModelUtil.toMap(assembled).toList.collect{
      case (worldKey: JoinKey, index: Index) if opt == "all" || !worldKey.was && worldKey.keyAlias == "SrcId" =>
        val keys = indexUtil.keyIterator(index).toList.sortBy(_.toString)
        (for {
          pk <- keys
          value <- indexUtil.getValues(index,pk,"").toList.sortBy(ToPrimaryKey(_))
        } yield pk -> value).groupBy(_._2.getClass.getName).toList.sortBy(_._1).map{
          case (clName,res) =>
            s"cl3 $worldKey $clName kv-hc ${res.hashCode} size ${res.size}"
        }
    }.flatten.sorted.foreach{ l => logger.info(l) }
  }
  def reportBadProducts(assembled: ReadModel): Unit = {
    val productWorldChecker = new ProductWorldChecker
    readModelUtil.toMap(assembled).toList.collect {
      case (worldKey: JoinKey, index: Index) if !worldKey.was && worldKey.keyAlias == "SrcId" =>
        val res0 = indexUtil.keyIterator(index).toList.sortBy(_.toString).map { pk =>
          (pk, indexUtil.getValues(index, pk, "").toList.sortBy(ToPrimaryKey(_)))
        }
        //val khc = res0.map(_._1).hashCode
        for (l <- productWorldChecker.check(res0))
          logger.warn(s"non-product ${worldKey.valueClassName} : $l")
      //s"cl2 ${worldKey.valueClassName} sz ${res0.size} kv-hc ${res0.hashCode} k-hc: $khc"
    }
  }
  def reportSelect(assembled: ReadModel, opt: String): Unit = {
    val opts = opt.split(' ').toSet
    readModelUtil.toMap(assembled).toList.collect {
      case (worldKey: JoinKey, index: Index) if opts(worldKey.valueClassName) =>
        val keys = indexUtil.keyIterator(index).toList.sortBy(_.toString)
        if(opts.size == 1) for(fk <- keys){
          logger.info(s"$worldKey $fk:")
          for(v <- indexUtil.getValues(index,fk,"").toList.sortBy(ToPrimaryKey(_)))
            logger.info(s"  ${v.hashCode} ${ToPrimaryKey(v)} ${v.getClass.getName}")

        }
        else for(fk <- opts){
          for(v <- indexUtil.getValues(index,fk,"").toList.sortBy(ToPrimaryKey(_)))
            logger.info(s"  ${v.hashCode} $v")
        }
    }
  }
}

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
