
package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.HashSearchTestProtocol.{D_SomeModel, D_SomeRequest}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import ee.cone.c4di.{c4, provide}
import ee.cone.c4proto.{Id, protocol}

case class StrEq(value: String) //todo proto
case object StrEqCheck extends ConditionCheck[StrEq,String] {
  def prepare: List[AbstractMetaAttr] => StrEq => StrEq = _ => identity[StrEq]
  def check: StrEq => String => Boolean = by => value => value == by.value

  def defaultBy: Option[StrEq => Boolean] = None
}
case object StrEqRanger extends Ranger[StrEq,String] {
  def ranges: StrEq => (String => List[StrEq], PartialFunction[Product,List[StrEq]]) = {
    case StrEq("") => (
      value => List(StrEq(value)),
      { case p@StrEq(v) => List(p) }
    )
    case _ => ???
  }
}
object DefaultConditionChecks {
  implicit lazy val strEq: ConditionCheck[StrEq,String] = StrEqCheck
}
object DefaultRangers {
  implicit lazy val strEq: Ranger[StrEq,String] = StrEqRanger
}

@protocol("HashSearchTestApp") object HashSearchTestProtocol   {
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
  lazy val fieldA: ProdLensStrict[D_SomeModel,String] = ProdLens.of(_.fieldA)
  lazy val fieldB: ProdLensStrict[D_SomeModel,String] = ProdLens.of(_.fieldB)
  lazy val fieldC: ProdLensStrict[D_SomeModel,String] = ProdLens.of(_.fieldC)
}

import HashSearch.{Request,Response}
import SomeModelAccess._

@c4assemble("HashSearchTestApp") class HashSearchTestAssembleBase(
  modelConditionFactory: ModelConditionFactory[Unit],
  hashSearchFactory: HashSearchFactoryHolder
)   {
  def joinReq(
    srcId: SrcId,
    request: Each[D_SomeRequest]
  ): Values[(SrcId,Request[D_SomeModel])] =
    List(WithPK(hashSearchFactory.value.request(HashSearchTestMain.condition(modelConditionFactory,request))))

  def joinResp(
    srcId: SrcId,
    response: Each[Response[D_SomeModel]]
  ): Values[(SrcId,SomeResponse)] =
    List(WithPK(SomeResponse(response.srcId,response.lines)))
}

@c4("HashSearchTestApp") final class HashSearchTestAddAssembleBase(
  hashSearchFactoryHolder: HashSearchFactoryHolder
) {
  import DefaultRangers._
  @provide def subAssembles: Seq[Assemble] =
    hashSearchFactoryHolder.value.index(classOf[D_SomeModel])
      .add(fieldA, StrEq(""))
      .add(fieldB, StrEq(""))
      .add(fieldC, StrEq(""))
      .assemble :: Nil
}

case class SomeResponse(srcId: SrcId, lines: List[D_SomeModel])

object HashSearchTestMain {
  def condition(modelConditionFactory: ModelConditionFactory[Unit], request: D_SomeRequest): Condition[D_SomeModel] = {
    import DefaultConditionChecks._
    val cf = modelConditionFactory.of[D_SomeModel]
    val leafs = for {
      lens <- List(fieldA, fieldB, fieldC)
      pattern <- request.pattern
      value <- Option(lens.of(pattern)) if value.nonEmpty
    } yield cf.leaf(lens, StrEq(value), Nil)
    leafs.reduce(cf.intersect)
  }
}

@c4("HashSearchTestApp") final class HashSearchTestMain(
  modelConditionFactory: ModelConditionFactory[Unit],
  contextFactory: ContextFactory,
  execution: Execution,
  getD_SomeModel: GetByPK[D_SomeModel],
  getSomeResponse: GetByPK[SomeResponse],
  txAdd: LTxAdd,
) extends Executable with LazyLogging {

  def measure[T](hint: String)(f: ()=>T): T = {
    val t = System.currentTimeMillis
    val res = f()
    logger.info(s"$hint: ${System.currentTimeMillis-t}")
    res
  }

  def ask(modelConditionFactory: ModelConditionFactory[Unit]): D_SomeModel=>Context=>Unit = pattern => local => {
    val request = D_SomeRequest("123",Option(pattern))

    logger.info(s"$request ${getD_SomeModel.ofA(local).size}")
    val res0 = measure("dumb  find models") { () =>
      val pattern = request.pattern.get
      for{
        model <- getD_SomeModel.ofA(local).values if
          (pattern.fieldA.isEmpty || model.fieldA == pattern.fieldA) &&
          (pattern.fieldB.isEmpty || model.fieldB == pattern.fieldB) &&
          (pattern.fieldC.isEmpty || model.fieldC == pattern.fieldC)
      } yield model //).toList.sortBy(_.srcId)
    }

    val res1 = measure("cond  find models") { () =>
      val lenses = List(fieldA,fieldB,fieldC)
      val condition = HashSearchTestMain.condition(modelConditionFactory,request)
      getD_SomeModel.ofA(local).values.filter(condition.check)
    }

    val res2 = measure("index find models") { () =>
      val local2 = txAdd.add(LEvent.update(request))(local)
      Single(getSomeResponse.ofA(local2).values.toList).lines
    }

    val res = List(res0,res1,res2).map(_.toList.sortBy(_.srcId))
    logger.info(s"${res.map(_.size)} found")

    if(res.distinct.size!=1) throw new Exception(s"$res")
  }

  private def fillWorld(size: Int): Context=>Context = local => {
    val models = for{ i <- 1 to size } yield D_SomeModel(s"$i",s"${i%7}",s"${i%59}",s"${i%541}") //
    measure("TxAdd models"){ () =>
      txAdd.add(models.flatMap(LEvent.update))(local)
    }
  }

  def run(): Unit = {
    val voidContext = contextFactory.updated(Nil)
    val contexts = List(
      fillWorld(10000)(voidContext),
      fillWorld(100000)(voidContext),
      fillWorld(1000000)(voidContext)
    )
    for {
      i <- 1 to 2
      local <- contexts
      pattern <- List(
        D_SomeModel("","1","2","3"),
        D_SomeModel("","1","2",""),
        D_SomeModel("","1","","3"),
        D_SomeModel("","","2","3")
      )
    } ask(modelConditionFactory)(pattern)(local)







/*
    local2.assembled.foreach{ case (k,v) =>
      logger.info(s"$k")
      logger.info(v match {
        case m: Map[_,_] => s"${m.size} ${m.values.collect{ case s: Seq[_] => s.size }.sum}"
        case _ => "???"
      })
    }
*/
    execution.complete()
  }
}

// sbt ~'c4actor-base-examples/run-main ee.cone.c4actor.HashSearchTestMain'
