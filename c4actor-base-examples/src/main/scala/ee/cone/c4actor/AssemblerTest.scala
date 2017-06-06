
package ee.cone.c4actor

import PCProtocol.{RawChildNode, RawParentNode}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4proto.{Id, Protocol, protocol}
import ee.cone.c4actor.LEvent._
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, _}
import ee.cone.c4proto

@protocol object PCProtocol extends c4proto.Protocol {
  @Id(0x0003) case class RawChildNode(@Id(0x0003) srcId: String, @Id(0x0005) parentSrcId: String, @Id(0x0004) caption: String)
  @Id(0x0001) case class RawParentNode(@Id(0x0003) srcId: String, @Id(0x0004) caption: String)
}

case class ParentNodeWithChildren(srcId: String, caption: String, children: Values[RawChildNode])
@assemble class TestAssemble extends Assemble {
  type ParentSrcId = SrcId
  def joinChildNodeByParent(
    key: SrcId,
    rawChildNode: Values[RawChildNode]
  ): Values[(ParentSrcId,RawChildNode)] =
    rawChildNode.map(child ⇒ child.parentSrcId → child)
  def joinParentNodeWithChildren(
    key: SrcId,
    @by[ParentSrcId] childNodes: Values[RawChildNode],
    rawParentNode: Values[RawParentNode]
  ): Values[(SrcId,ParentNodeWithChildren)] =
    rawParentNode.map(parent ⇒
      parent.srcId → ParentNodeWithChildren(parent.srcId, parent.caption, childNodes)
    )
  /* todo:
  IO[SrcId,ParentNodeWithChildren](
    for(parent <- IO[SrcId,RawParentNode])
      yield ParentNodeWithChildren(parent.srcId, parent.caption, IO[ParentSrcId,RawChildNode](
        key => for(child <- IO[SrcId,RawChildNode]) yield child.parentSrcId → child
      ))
  )
  ////////

  Pairs[ParentSrcId,RawChildNode] =
    for(child <- Values[SrcId,RawChildNode]) yield child.parentSrcId → child

  Values[SrcId,ParentNodeWithChildren] =
    for(parent <- Values[SrcId,RawParentNode])
      yield ParentNodeWithChildren(parent.srcId, parent.caption, Values[ParentSrcId,RawChildNode])
  */

}

/*
object LifeTypes {
  type MortalSrcId = SrcId
}

@assemble class LifeAssemble[Parent,Child](
  classOfParent: Class[Parent],
  classOfChild: Class[Child]
) extends Assemble {
  import LifeTypes.ParentSrcId
  def join(
    key: SrcId,
    parents: Values[Parent],
    @by[ParentSrcId] children: Values[Child]
  ): Values[(SrcId,TxTransform)] = ???
}
*/




/*
object LifeTypes {
  type MortalSrcId = SrcId
}

trait GiveLifeRulesApp {
  def emptyGiveLifeRules: GiveLifeRules
  def giveLifeRules: GiveLifeRules = emptyGiveLifeRules
}
trait GiveLifeRules {
  def add[Giver,Mortal](rule: GiveLifeRule[Giver,Mortal]): GiveLifeRules
}
abstract class GiveLifeRule[Giver,Mortal](to: Giver ⇒ List[Mortal]) extends Product


case class GiveLifeRulesImpl() extends GiveLifeRules {
  def add[Giver, Mortal](rule: GiveLifeRule[Giver, Mortal]): GiveLifeRules = ???
}


object ToPrimaryKey {
  def apply(p: Product): SrcId = p.productElement(0) match{ case s: String ⇒ s }
}
@assemble class GiveLifeAssemble[Giver<:Product,Mortal<:Product](
  classOfGiver: Class[Giver],
  classOfMortal: Class[Mortal],
  f: Giver ⇒ List[Mortal]
) extends Assemble {
  import LifeTypes.MortalSrcId
  def join(
    key: SrcId,
    givers: Values[Giver]
  ): Values[(MortalSrcId,Mortal)] =
    for(giver ← givers; mortal ← f(giver)) yield ToPrimaryKey(mortal) → mortal
}

@assemble class MortalAssemble[Mortal<:Product](
  classOfMortal: Class[Mortal]
) extends Assemble {
  import LifeTypes.MortalSrcId
  def join(
    key: SrcId,
    mortals: Values[Mortal],
    @by[MortalSrcId] lives: Values[Mortal]
  ): Values[(SrcId,TxTransform)] =
    if(mortals.nonEmpty && lives.isEmpty) {
      val pk = s"kill/${classOfMortal.getName}/$key"
      List(pk → SimpleTxTransform[Product](pk, mortals.flatMap(LEvent.delete)))
    } else Nil
}
*/

class AssemblerTestApp extends ServerApp with ToStartApp with InitLocalsApp with ParallelObserversApp with UMLClientsApp {
  override def indexValueMergerFactory: IndexValueMergerFactory =
    //new CachingIndexValueMergerFactory(16)
    new TreeIndexValueMergerFactory(16)
  def rawQSender: RawQSender =
    new RawQSender { def send(recs: List[QRecord]): List[Long] = Nil }
  override def protocols: List[Protocol] = PCProtocol :: super.protocols
  override def assembles: List[Assemble] = new TestAssemble :: super.assembles
}

object AssemblerTest extends App {
  val app = new AssemblerTestApp
  val recs = update(RawParentNode("1","P-1")) ++
    List("2","3").flatMap(srcId ⇒ update(RawChildNode(srcId,"1",s"C-$srcId")))
  val rawRecs = recs.map(rec⇒app.qMessages.toRecord(NoTopicName,app.qMessages.toUpdate(rec))).toList
  //println(app.qMessages.toTree(rawRecs))
  val emptyWorld = app.qReducer.createWorld(Map())
  val world = app.qReducer.reduceRecover(emptyWorld, rawRecs)
  /*
  val shouldDiff = Map(
    By.srcId(classOf[PCProtocol.RawParentNode]) -> Map(
      "1" -> List(RawParentNode("1","P-1"))
    ),
    By.srcId(classOf[PCProtocol.RawChildNode]) -> Map(
      "2" -> List(RawChildNode("2","1","C-2")),
      "3" -> List(RawChildNode("3","1","C-3"))
    )
  )
  assert(diff==shouldDiff)*/
  println(world)
  Map(
    By.srcId(classOf[PCProtocol.RawParentNode]) -> Map(
      "1" -> List(RawParentNode("1","P-1"))
    ),
    By.srcId(classOf[PCProtocol.RawChildNode]) -> Map(
      "2" -> List(RawChildNode("2","1","C-2")),
      "3" -> List(RawChildNode("3","1","C-3"))
    ),
    By.srcId(classOf[ParentNodeWithChildren]) -> Map(
      "1" -> List(ParentNodeWithChildren("1",
        "P-1",
        List(RawChildNode("2","1","C-2"), RawChildNode("3","1","C-3"))
      ))
    )
  ).foreach{
    case (k,v) ⇒ assert(world(k)==v)
  }
}

///////////////////////// generic assembles -- compiling is tested only ////////

trait TestGenericFactory[InT,OutT<:Product,KeyT] extends Product {
  def create(in: Values[InT]): Values[(KeyT,OutT)]
}

@assemble class TestGenericAssemble[InT,OutT<:Product,KeyT](
  classInT: Class[InT],
  classOutT: Class[OutT],
  classKeyT: Class[KeyT],
  f: TestGenericFactory[InT,OutT,KeyT]
) extends Assemble {
  def joinChildNodeByParent(
    key: SrcId,
    inT: Values[InT]
  ): Values[(KeyT,OutT)] =
    f.create(inT)
}

