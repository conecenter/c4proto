package ee.cone.c4actor

import ee.cone.c4actor.EachTestProtocol.Item
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.{Assemble, IndexUtil, assemble, by}
import ee.cone.c4assemble.Types._
import ee.cone.c4proto.{Id, Protocol, protocol}

class EachTestApp extends RichDataApp
  with SimpleAssembleProfilerApp
  with VMExecutionApp with ToStartApp with ExecutableApp with AssemblesApp
{
  override def protocols: List[Protocol] = EachTestProtocol :: super.protocols
  override def toStart: List[Executable] = new EachTestExecutable(execution, rawWorldFactory, indexUtil) :: super.toStart
  override def assembles = new EachTestNotEffectiveAssemble :: super.assembles
}

@protocol object EachTestProtocol extends Protocol {
  @Id(0x0001) case class Item(@Id(0x0001) srcId: String, @Id(0x0002) parent: String)
}

case class EachTestItem(item: Item, valueItem: Item)

@assemble class EachTestAssemble extends Assemble {
  type ByParent = SrcId
  def joinByVal(
    key: SrcId,
    item: Each[Item]
  ): Values[(ByParent, Item)] = List(item.parent -> item)
  def join(
    key: SrcId,
    vItem: Each[Item],
    @by[Item] item: Each[Item]
  ): Values[(SrcId,EachTestItem)] = {
    List(WithPK(EachTestItem(item,vItem)))
  }
}

@assemble class EachTestNotEffectiveAssemble extends Assemble {
  type ByVal = SrcId
  def joinByVal(
    key: SrcId,
    items: Values[Item]
  ): Values[(ByVal, Item)] = for {
    item ← items
  } yield item.parent -> item
  def join(
    key: SrcId,
    vItems: Values[Item],
    @by[Item] items: Values[Item]
  ): Values[(SrcId,EachTestItem)] = for {
    vItem ← vItems
    item ← vItem
  } yield WithPK(EachTestItem(item,vItem))
}


class EachTestExecutable(execution: Execution, rawWorldFactory: RawWorldFactory, indexUtil: IndexUtil) extends Executable {
  def run(): Unit = {
    val rawWorld = rawWorldFactory.create()
    val voidContext = rawWorld match { case w: RichRawWorld ⇒ w.context }

    Function.chain[Context](Seq(
      TxAdd(LEvent.update(Item("1","2"))),
      TxAdd(LEvent.update(Item("1","3"))),
      l ⇒ {
        assert(ByPK.apply(classOf[Item]).of(l)("1").parent=="3","last stored item wins")
        l
      }
    ))(voidContext)

    assert(emptyIndex==indexUtil.mergeIndex(Seq(
      indexUtil.result("1",Item("1","2"),-1),
      indexUtil.result("1",Item("1","2"),+1)
    )))

    /*println(indexUtil.mergeIndex(Seq(
      indexUtil.result("1",Item("1","2"),-1),
      indexUtil.result("1",Item("1","3"),+1)
    )))*/

    Function.chain[Context](Seq(
      Seq(TxAdd(LEvent.update(Item(s"V","")))),
      (1 to 1000).map(n⇒TxAdd(LEvent.update(Item(s"$n","V")))),
      Seq{ (l:Context) ⇒
        val r = ByPK(classOf[EachTestItem]).of(l)
        println(r.keys.size)
        println(r.values.forall(_.valueItem.parent.isEmpty))
        l
      }
    ).flatten)(voidContext)


    execution.complete()
  }
}

// C4STATE_TOPIC_PREFIX=ee.cone.c4actor.EachTestApp sbt ~'c4actor-base-examples/run-main ee.cone.c4actor.ServerMain'