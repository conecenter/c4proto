package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.DepTestProtocol.D_Spark
import ee.cone.c4actor.TestProtocol.{D_TestNode, D_ValueNode}
import ee.cone.c4actor.TestRequests.D_ChildDepRequest
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.dep._
import ee.cone.c4actor.dep.reponse.filter.{DepCommonResponseForwardMix, DepForwardUserAttributesMix, DepResponseFilterFactoryMix}
import ee.cone.c4actor.dep.request._
import ee.cone.c4actor.dep_impl.{AskByPKsApp, ByPKRequestHandlerApp, DepAssembleApp, DepResponseFiltersApp}
import ee.cone.c4assemble.Assemble
import ee.cone.c4proto.{Id, Protocol, protocol}

import scala.collection.immutable


@protocol(TestCat) object TestProtocolBase   {

  @Id(0x0001) case class D_TestNode(@Id(0x0003) srcId: String, @Id(0x0005) parentId: String)

  @Id(0x0010) case class D_ValueNode(@Id(0x0013) srcId: String, @Id(0x0015) value: Int)

}

object DefaultPffNode extends DefaultModelFactory(classOf[D_ValueNode], D_ValueNode(_, 0))

//  C4STATE_TOPIC_PREFIX=ee.cone.c4actor.DepTestApp sbt ~'c4actor-extra-examples/runMain ee.cone.c4actor.ServerMain'
/*@assemble class DepAssemble(handlerRegistry: RequestHandlerRegistry, adapterRegistry: QAdapterRegistry)   {
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
  override def transform(local: Context): Context = access.asInstanceOf[Access[D_ValueNode]].updatingLens.get.set(access.asInstanceOf[Access[D_ValueNode]].initialValue.copy(value = 666))(local)
}


class DepTestStart(
  execution: Execution, toUpdate: ToUpdate, contextFactory: ContextFactory
) extends Executable with LazyLogging {
  def run(): Unit = {
    import LEvent.update

    val recs = update(D_TestNode("1", "")) ++ update(D_ValueNode("123", 239)) ++ update(D_ValueNode("124", 666)) ++ update(D_Spark("test"))
    /*
          update(D_Node("12","1")) ++ update(D_Node("13","1")) ++
          update(D_Node("124","12")) ++ update(D_Node("125","12"))*/
    val updates: List[QProtocol.Update] = recs.map(rec ⇒ toUpdate.toUpdate(rec)).toList
    val nGlobal = contextFactory.updated(updates)

    //logger.info(s"${nGlobal.assembled}")
    logger.debug("asddfasdasdasdas")
    println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")
    println(ByPK(classOf[DepTestResponse]).of(nGlobal).values.toList)
    /*println(ByPK(classOf[UpResolvable]).of(nGlobal).values.map(test ⇒ test.resolvable.value → test.request.srcId))
    val access: Access[PffNode] = ByPK(classOf[UpResolvable]).of(nGlobal)("c151e7dd-2ac6-3d34-871a-dbe77a155abc").resolvable.value.get.asInstanceOf[Option[Access[PffNode]]].get
    println(s"Final result1: ${ByPK(classOf[UpResolvable]).of(nGlobal)("c151e7dd-2ac6-3d34-871a-dbe77a155abc").resolvable.value}")*/
    println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")
    val newLocal = TxAdd(LEvent.update(D_ValueNode("124", 555)))(nGlobal)
    println(ByPK(classOf[DepTestResponse]).of(newLocal).values.toList)

    val newLocal2 = TxAdd(LEvent.update(D_ValueNode("123", 100)))(newLocal)
    println(ByPK(classOf[DepTestResponse]).of(newLocal2).values.toList)
    /*println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")
    val newContext = access.updatingLens.get.set(access.initialValue.copy(value = 666))(nGlobal)
    println(s"Final result3: ${ByPK(classOf[UpResolvable]).of(newContext)("c151e7dd-2ac6-3d34-871a-dbe77a155abc").resolvable.value}")

    println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")
    println(s"Unresolved: \n${ByPK(classOf[UnresolvedDep]).of(nGlobal).toList.mkString("\n")}")
    println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")*/
    println(ByPK(classOf[DepUnresolvedRequest]).of(newLocal2).values.toList)
    execution.complete()

    /*
    Map(
      ByPK(classOf[PCProtocol.D_RawParentNode]) -> Map(
        "1" -> D_RawParentNode("1","P-1")
      ),
      ByPK(classOf[PCProtocol.D_RawChildNode]) -> Map(
        "2" -> D_RawChildNode("2","1","C-2"),
        "3" -> D_RawChildNode("3","1","C-3")
      ),
      ByPK(classOf[ParentNodeWithChildren]) -> Map(
        "1" -> ParentNodeWithChildren("1",
          "P-1",
          List(D_RawChildNode("2","1","C-2"), D_RawChildNode("3","1","C-3"))
        )
      )
    ).foreach{
      case (k,v) ⇒ assert(k.of(nGlobal).toMap==v)
    }*/
  }
}

class DepTestApp extends TestRichDataApp
  with ExecutableApp
  with VMExecutionApp
  with TreeIndexValueMergerFactoryApp
  with SimpleAssembleProfilerApp
  with ToStartApp
  with ModelAccessFactoryApp
  with DepTestAssemble
  with CommonRequestUtilityMix
  with ByPKRequestHandlerApp
  with DepResponseFiltersApp
  with DepCommonResponseForwardMix
  with DepResponseFilterFactoryMix
with DepForwardUserAttributesMix
  with DepAssembleApp with AskByPKsApp with ByClassNameRequestMix with ByClassNameAllRequestHandlerApp with ByClassNameRequestApp with ContextIdInjectApp {

  def depRequestHandlers: immutable.Seq[DepHandler] = depHandlers


  override def askByPKs: List[AbstractAskByPK] = askByPKFactory.forClass(classOf[D_ValueNode]) :: super.askByPKs


  override def byClNameAllClasses: List[Class[_ <: Product]] = classOf[D_ValueNode] :: super.byClNameAllClasses

  def depDraft: DepDraft = DepDraft(commonRequestUtilityFactory, askByPKFactory.forClass(classOf[D_ValueNode]), depAskFactory, byClassNameAllAsk, depFactory)

  override def defaultModelFactories: List[DefaultModelFactory[_]] = DefaultPffNode :: super.defaultModelFactories

  override def depHandlers: List[DepHandler] = {
    println(super.depHandlers.mkString("\n"))
    depDraft.handlerLUL :: depDraft.FooRequestHandler :: depDraft.handlerKEK :: super.depHandlers
  }

  override def protocols: List[Protocol] = ContextIdRequestProtocol :: TestProtocol :: TestRequests :: super.protocols

  override def childRequests: List[Class[_ <: Product]] = classOf[D_ChildDepRequest] :: super.childRequests

  override def toStart: List[Executable] = new DepTestStart(execution, toUpdate, contextFactory) :: super.toStart

  def testDep: Dep[Any] = depDraft.serialView.asInstanceOf[Dep[Any]]

  override def assembles: List[Assemble] = {
    println(super.assembles.mkString("\n"))
    super.assembles
  }
}