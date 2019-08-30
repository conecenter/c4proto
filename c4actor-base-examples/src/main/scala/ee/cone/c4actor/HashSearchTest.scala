
package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.HashSearchTestProtocol.{D_SomeModel, D_SomeRequest}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import ee.cone.c4proto.{Id, Protocol, protocol}

case class StrEq(value: String) //todo proto
case object StrEqCheck extends ConditionCheck[StrEq,String] {
  def prepare: List[AbstractMetaAttr] ⇒ StrEq ⇒ StrEq = _ ⇒ identity[StrEq]
  def check: StrEq ⇒ String ⇒ Boolean = by ⇒ value ⇒ value == by.value

  def defaultBy: Option[StrEq => Boolean] = None
}
case object StrEqRanger extends Ranger[StrEq,String] {
  def ranges: StrEq ⇒ (String ⇒ List[StrEq], PartialFunction[Product,List[StrEq]]) = {
    case StrEq("") ⇒ (
      value ⇒ List(StrEq(value)),
      { case p@StrEq(v) ⇒ List(p) }
    )
    case _ ⇒ ???
  }
}
object DefaultConditionChecks {
  implicit lazy val strEq: ConditionCheck[StrEq,String] = StrEqCheck
}
object DefaultRangers {
  implicit lazy val strEq: Ranger[StrEq,String] = StrEqRanger
}

@protocol object HashSearchTestProtocolBase   {
  @Id(0x0001) case class D_SomeModel(
    @Id(0x0003) srcId: String,
    @Id(0x0004) fieldA: String,
    @Id(0x0005) fieldB: String,
    @Id(0x0006) fieldC: String
  )
  @Id(0x0002) case class D_SomeRequest(
    @Id(0x0003) srcId: String,
    @Id(0x0007) pattern: Option[D_SomeModel]
  )
}

@fieldAccess object SomeModelAccessBase {
  lazy val fieldA: ProdLens[D_SomeModel,String] = ProdLens.of(_.fieldA)
  lazy val fieldB: ProdLens[D_SomeModel,String] = ProdLens.of(_.fieldB)
  lazy val fieldC: ProdLens[D_SomeModel,String] = ProdLens.of(_.fieldC)
}

import HashSearch.{Request,Response}
import SomeModelAccess._

@assemble class HashSearchTestAssembleBase(
  modelConditionFactory: ModelConditionFactory[Unit],
  hashSearchFactory: HashSearch.Factory
)   {
  def joinReq(
    srcId: SrcId,
    request: Each[D_SomeRequest]
  ): Values[(SrcId,Request[D_SomeModel])] =
    List(WithPK(hashSearchFactory.request(HashSearchTestMain.condition(modelConditionFactory,request))))

  def joinResp(
    srcId: SrcId,
    response: Each[Response[D_SomeModel]]
  ): Values[(SrcId,SomeResponse)] =
    List(WithPK(SomeResponse(response.srcId,response.lines)))
}


case class SomeResponse(srcId: SrcId, lines: List[D_SomeModel])
//todo reg
class HashSearchTestApp extends TestRichDataApp
  with TreeIndexValueMergerFactoryApp
  with SimpleAssembleProfilerApp
{
  override def protocols: List[Protocol] =
    HashSearchTestProtocol :: super.protocols
  import DefaultRangers._
  override def assembles: List[Assemble] = List(
    hashSearchFactory.index(classOf[D_SomeModel])
      .add(fieldA, StrEq(""))
      .add(fieldB, StrEq(""))
      .add(fieldC, StrEq(""))
      .assemble,
    new HashSearchTestAssemble(modelConditionFactory,hashSearchFactory)
  ) ::: super.assembles
}

object HashSearchTestMain extends LazyLogging {
  def condition(modelConditionFactory: ModelConditionFactory[Unit], request: D_SomeRequest): Condition[D_SomeModel] = {
    import DefaultConditionChecks._
    val cf = modelConditionFactory.of[D_SomeModel]
    val leafs = for {
      lens ← List(fieldA, fieldB, fieldC)
      pattern ← request.pattern
      value ← Option(lens.of(pattern)) if value.nonEmpty
    } yield cf.leaf(lens, StrEq(value), Nil)
    leafs.reduce(cf.intersect)
  }

  def measure[T](hint: String)(f: ()⇒T): T = {
    val t = System.currentTimeMillis
    val res = f()
    logger.info(s"$hint: ${System.currentTimeMillis-t}")
    res
  }

  def main(args: Array[String]): Unit = test()


  def ask(modelConditionFactory: ModelConditionFactory[Unit]): D_SomeModel⇒Context⇒Unit = pattern ⇒ local ⇒ {
    val request = D_SomeRequest("123",Option(pattern))

    logger.info(s"$request ${ByPK(classOf[D_SomeModel]).of(local).size}")
    val res0 = measure("dumb  find models") { () ⇒
      val pattern = request.pattern.get
      for{
        model ← ByPK(classOf[D_SomeModel]).of(local).values if
          (pattern.fieldA.isEmpty || model.fieldA == pattern.fieldA) &&
          (pattern.fieldB.isEmpty || model.fieldB == pattern.fieldB) &&
          (pattern.fieldC.isEmpty || model.fieldC == pattern.fieldC)
      } yield model //).toList.sortBy(_.srcId)
    }

    val res1 = measure("cond  find models") { () ⇒
      val lenses = List(fieldA,fieldB,fieldC)
      val condition = this.condition(modelConditionFactory,request)
      ByPK(classOf[D_SomeModel]).of(local).values.filter(condition.check)
    }

    val res2 = measure("index find models") { () ⇒
      val local2 = TxAdd(LEvent.update(request))(local)
      Single(ByPK(classOf[SomeResponse]).of(local2).values.toList).lines
    }

    val res = List(res0,res1,res2).map(_.toList.sortBy(_.srcId))
    logger.info(s"${res.map(_.size)} found")

    if(res.distinct.size!=1) throw new Exception(s"$res")
  }

  private def fillWorld(size: Int): Context⇒Context = local ⇒ {
    val models = for{ i ← 1 to size } yield D_SomeModel(s"$i",s"${i%7}",s"${i%59}",s"${i%541}") //
    measure("TxAdd models"){ () ⇒
      TxAdd(models.flatMap(LEvent.update))(local)
    }
  }

  def test(): Unit = {
    val app = new HashSearchTestApp
    val voidContext = app.contextFactory.updated(Nil)
    val contexts = List(
      fillWorld(10000)(voidContext),
      fillWorld(100000)(voidContext),
      fillWorld(1000000)(voidContext)
    )
    for {
      i ← 1 to 2
      local ← contexts
      pattern ← List(
        D_SomeModel("","1","2","3"),
        D_SomeModel("","1","2",""),
        D_SomeModel("","1","","3"),
        D_SomeModel("","","2","3")
      )
    } ask(app.modelConditionFactory)(pattern)(local)







/*
    local2.assembled.foreach{ case (k,v) ⇒
      logger.info(s"$k")
      logger.info(v match {
        case m: Map[_,_] ⇒ s"${m.size} ${m.values.collect{ case s: Seq[_] ⇒ s.size }.sum}"
        case _ ⇒ "???"
      })
    }
*/
  }
}

// sbt ~'c4actor-base-examples/run-main ee.cone.c4actor.HashSearchTestMain'
