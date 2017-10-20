
package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.HashSearchTestProtocol.SomeModel
import ee.cone.c4actor.QProtocol.Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble._
import ee.cone.c4proto.{Id, Protocol, protocol}

case class StrEq(value: String) //todo proto
case object StrEqCheck extends ConditionCheck[StrEq,String] {
  def check: StrEq ⇒ String ⇒ Boolean = by ⇒ value ⇒ value == by.value
}
case object StrEqRanger extends Ranger[StrEq,String] {
  def ranges: StrEq ⇒ (String ⇒ List[StrEq], PartialFunction[Product,List[StrEq]]) = {
    case StrEq("") ⇒ (
      value ⇒ List(StrEq(value)),
      { case p@StrEq(v) ⇒ List(p) }
    )
  }
}
object DefaultConditionChecks {
  implicit lazy val strEq: ConditionCheck[StrEq,String] = StrEqCheck
}
object DefaultRangers {
  implicit lazy val strEq: Ranger[StrEq,String] = StrEqRanger
}

@protocol object HashSearchTestProtocol extends Protocol {
  @Id(0x0001) case class SomeModel(
    @Id(0x0003) srcId: String,
    @Id(0x0004) fieldA: String,
    @Id(0x0005) fieldB: String
  )
}

@fieldAccess
object SomeModelAccess {
  lazy val fieldA: ProdLens[SomeModel,String] = ProdLens.of(_.fieldA)
  lazy val fieldB: ProdLens[SomeModel,String] = ProdLens.of(_.fieldB)
}

import HashSearch.{Request,Response}
import SomeModelAccess._

@assemble class HashSearchTestAssemble(
  modelConditionFactory: ModelConditionFactory[Unit],
  hashSearchFactory: HashSearch.Factory
) extends Assemble {
  def joinReq(
    srcId: SrcId,
    firstborns: Values[Firstborn]
  ): Values[(SrcId,Request[SomeModel])] = for {
    firstborn ← firstborns
  } yield {
    import DefaultConditionChecks._
    val cond = modelConditionFactory.of[SomeModel]
    WithPK(hashSearchFactory.request(cond.intersect(
      cond.leaf(fieldA, StrEq("10")),
      cond.leaf(fieldB, StrEq("26"))
    )))
  }

  def joinResp(
    srcId: SrcId,
    responses: Values[Response[SomeModel]]
  ): Values[(SrcId,SomeResponse)] = for {
    response ← responses
  } yield WithPK(SomeResponse(response.srcId,response.lines))
}

case class SomeResponse(srcId: SrcId, lines: List[SomeModel])
//todo reg
class HashSearchTestApp extends RichDataApp
  with TreeIndexValueMergerFactoryApp
  with SimpleAssembleProfilerApp
{
  override def protocols: List[Protocol] =
    HashSearchTestProtocol :: super.protocols
  import DefaultRangers._
  override def assembles: List[Assemble] = List(
    hashSearchFactory.index(classOf[SomeModel])
      .add(fieldA, StrEq(""))
      .add(fieldB, StrEq(""))
      .assemble,
    new HashSearchTestAssemble(modelConditionFactory,hashSearchFactory)
  ) ::: super.assembles
}

object HashSearchTestMain extends App with LazyLogging {
  val app = new HashSearchTestApp
  val rawWorld = app.rawWorldFactory.create()
  val local0 = rawWorld match { case w: RichRawWorld ⇒ w.context }
  val updates = for{
    i ← 0 to 99999
    model = SomeModel(s"$i",s"${i%200}",s"${i%500}")
    update ← LEvent.update(model)
  } yield update
  val local1 = TxAdd(updates)(local0)
  for {
    response ← ByPK(classOf[SomeResponse]).of(local1).values
    line ← response.lines
  } logger.info(line.toString)
}


/*
override def assembles = ...


: Values[(SrcId,Request[SomeModel])] = ...

*/
