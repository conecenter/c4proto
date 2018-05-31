package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.DepTestProtocol.Spark
import ee.cone.c4actor.TestProtocol.{TestNode, ValueNode}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.dep._
import ee.cone.c4actor.dep.request.ContextIdRequestProtocol
import ee.cone.c4actor.dep_impl.{AskByPKsApp, ByPKRequestHandlerApp, DepAssembleApp}
import ee.cone.c4assemble.Assemble
import ee.cone.c4proto.{Id, Protocol, protocol}

import scala.collection.immutable


@protocol object TestProtocol extends Protocol {

  @Id(0x0001) case class TestNode(@Id(0x0003) srcId: String, @Id(0x0005) parentId: String)

  @Id(0x0010) case class ValueNode(@Id(0x0013) srcId: String, @Id(0x0015) value: Int)

}

object DefaultPffNode extends DefaultModelFactory(classOf[ValueNode], ValueNode(_, 0))

//  C4STATE_TOPIC_PREFIX=ee.cone.c4actor.DepTestApp sbt ~'c4actor-extra-examples/runMain ee.cone.c4actor.ServerMain'
/*@assemble class DepAssemble(handlerRegistry: RequestHandlerRegistry, adapterRegistry: QAdapterRegistry) extends Assemble {
  type ToResponse = SrcId

  def reponsesTest
  (
    key: SrcId,
    @by[ToResponse] responses: Values[Response]
  ): Values[(SrcId, Request)] = {
    //println()
    //println(s"Responses: $key:${responses.head}")
    Nil
  }

  def transformTest
  (
    key: SrcId,
    resolvable: Values[UpResolvable]
  ): Values[(SrcId, TxTransform)] =
    for {
      res ← resolvable
      if res.request.srcId == "c151e7dd-2ac6-3d34-871a-dbe77a155abc"
    } yield {
      WithPK(TestTransform(res.request.srcId, res.resolvable.value.getOrElse("LUL")))
    }
}*/

case class TestTransform(srcId: SrcId, access: Any) extends TxTransform {
  override def transform(local: Context): Context = access.asInstanceOf[Access[ValueNode]].updatingLens.get.set(access.asInstanceOf[Access[ValueNode]].initialValue.copy(value = 666))(local)
}


class DepTestStart(
  execution: Execution, toUpdate: ToUpdate, contextFactory: ContextFactory
) extends Executable with LazyLogging {
  def run(): Unit = {
    import LEvent.update

    val recs = update(TestNode("1", "")) ++ update(ValueNode("123", 239)) ++ update(ValueNode("124", 666)) ++ update(Spark("test"))
    /*
          update(Node("12","1")) ++ update(Node("13","1")) ++
          update(Node("124","12")) ++ update(Node("125","12"))*/
    val updates: List[QProtocol.Update] = recs.map(rec ⇒ toUpdate.toUpdate(rec)).toList
    val context: Context = contextFactory.create()
    val nGlobal: Context = ReadModelAddKey.of(context)(updates)(context)

    //logger.info(s"${nGlobal.assembled}")
    logger.debug("asddfasdasdasdas")
    println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")
    println(ByPK(classOf[DepTestResponse]).of(nGlobal).values.toList)
    /*println(ByPK(classOf[UpResolvable]).of(nGlobal).values.map(test ⇒ test.resolvable.value → test.request.srcId))
    val access: Access[PffNode] = ByPK(classOf[UpResolvable]).of(nGlobal)("c151e7dd-2ac6-3d34-871a-dbe77a155abc").resolvable.value.get.asInstanceOf[Option[Access[PffNode]]].get
    println(s"Final result1: ${ByPK(classOf[UpResolvable]).of(nGlobal)("c151e7dd-2ac6-3d34-871a-dbe77a155abc").resolvable.value}")
    println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")
    println(s"Final result2: ${access.initialValue}")
    println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")
    val newContext = access.updatingLens.get.set(access.initialValue.copy(value = 666))(nGlobal)
    println(s"Final result3: ${ByPK(classOf[UpResolvable]).of(newContext)("c151e7dd-2ac6-3d34-871a-dbe77a155abc").resolvable.value}")

    println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")
    println(s"Unresolved: \n${ByPK(classOf[UnresolvedDep]).of(nGlobal).toList.mkString("\n")}")
    println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")*/
    execution.complete()

    /*
    Map(
      ByPK(classOf[PCProtocol.RawParentNode]) -> Map(
        "1" -> RawParentNode("1","P-1")
      ),
      ByPK(classOf[PCProtocol.RawChildNode]) -> Map(
        "2" -> RawChildNode("2","1","C-2"),
        "3" -> RawChildNode("3","1","C-3")
      ),
      ByPK(classOf[ParentNodeWithChildren]) -> Map(
        "1" -> ParentNodeWithChildren("1",
          "P-1",
          List(RawChildNode("2","1","C-2"), RawChildNode("3","1","C-3"))
        )
      )
    ).foreach{
      case (k,v) ⇒ assert(k.of(nGlobal).toMap==v)
    }*/
  }
}

class DepTestApp extends RichDataApp
  with ExecutableApp
  with VMExecutionApp
  with TreeIndexValueMergerFactoryApp
  with SimpleAssembleProfilerApp
  with ToStartApp
  with MortalFactoryApp
  with ModelAccessFactoryApp
  with DepTestAssemble
with CommonRequestUtilityMix
with ByPKRequestHandlerApp
with DepAssembleApp with AskByPKsApp{

  def uuidUtil: UUIDUtil = UUIDUtilImpl()

  def depRequestHandlers: immutable.Seq[DepHandler] = depHandlers


  override def askByPKs: List[AbstractAskByPK] = askByPKFactory.forClass(classOf[ValueNode]) :: super.askByPKs

  def depDraft: DepDraft = DepDraft(commonRequestUtilityFactory, askByPKFactory.forClass(classOf[ValueNode]), depAskFactory)

  override def defaultModelFactories: List[DefaultModelFactory[_]] = DefaultPffNode :: super.defaultModelFactories

  override def depHandlers: List[DepHandler] = {
    println(super.depHandlers.mkString("\n"))
    depDraft.handlerLUL :: depDraft.FooRequestHandler :: super.depHandlers
  }

  override def protocols: List[Protocol] = ContextIdRequestProtocol :: TestProtocol :: TestRequests :: super.protocols

  override def toStart: List[Executable] = new DepTestStart(execution, toUpdate, contextFactory) :: super.toStart

  def testDep: Dep[Any] = depDraft.serialView.asInstanceOf[Dep[Any]]

  override def assembles: List[Assemble] = {
    println(super.assembles.mkString("\n"))
    super.assembles
  }
}