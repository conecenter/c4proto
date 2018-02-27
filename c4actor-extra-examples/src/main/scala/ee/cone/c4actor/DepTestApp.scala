package ee.cone.c4actor

import java.util.UUID

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.CtxType.{Ctx, Request}
import ee.cone.c4actor.DepDraft._
import ee.cone.c4actor.LULProtocol.{LULNode, PffNode}
import ee.cone.c4actor.TestRequests.RootDepRequest
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.dependancy._
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble, by, was}
import ee.cone.c4gate.AlienProtocol.FromAlienState
import ee.cone.c4gate.{SessionAttr, SessionAttrApp}
import ee.cone.c4proto.{Id, Protocol, protocol}

import scala.collection.immutable


@protocol object LULProtocol extends Protocol {

  @Id(0x0001) case class LULNode(@Id(0x0003) srcId: String, @Id(0x0005) parentId: String)

  @Id(0x0010) case class PffNode(@Id(0x0013) srcId: String, @Id(0x0015) value: Int)

}

object DefaultPffNode extends DefaultModelFactory(classOf[PffNode], PffNode(_, 0))

//  C4STATE_TOPIC_PREFIX=ee.cone.c4actor.DepTestApp sbt ~'c4actor-extra-examples/runMain ee.cone.c4actor.ServerMain'
@assemble class DepAssemble(handlerRegistry: RequestHandlerRegistry, adapterRegistry: QAdapterRegistry) extends Assemble {

  def sparkJoiner
  (
    key: SrcId,
    sessions: Values[FromAlienState]
  ): Values[(SrcId, RequestWithSrcId)] =
    for (
      session ← sessions
    ) yield {
      //println("Root rq Handle")
      WithPK(RequestWithSrcId("root", RootDepRequest("kek"),session.sessionKey )) // TODO generate srcId instead of "root"
    }

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
}


class DepTestStart(
  execution: Execution, toUpdate: ToUpdate, contextFactory: ContextFactory
) extends Executable with LazyLogging {
  def run() = {
    import LEvent.update

    val recs: immutable.Seq[LEvent[Request with Serializable]] = update(LULNode("1", "")) ++ update(PffNode("123", 239)) ++ update(PffNode("124", 666))
    /*
          update(Node("12","1")) ++ update(Node("13","1")) ++
          update(Node("124","12")) ++ update(Node("125","12"))*/
    val updates: List[QProtocol.Update] = recs.map(rec ⇒ toUpdate.toUpdate(rec)).toList
    val context: Context = contextFactory.create()
    val nGlobal: Context = ReadModelAddKey.of(context)(updates)(context)

    //logger.info(s"${nGlobal.assembled}")
    logger.debug("asddfasdasdasdas")
    println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")
    val access: Access[PffNode] = ByPK(classOf[UpResolvable]).of(nGlobal)("root").resolvable.value.get.asInstanceOf[Option[Access[PffNode]]].get
    println(s"Final result: ${ByPK(classOf[UpResolvable]).of(nGlobal)("root").resolvable.value}")
    println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")
    println(s"Final result: ${access.initialValue}")
    println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")
    val newContext = access.updatingLens.get.set(access.initialValue.copy(value = 666))(nGlobal)
    println(s"Final result: ${ByPK(classOf[UpResolvable]).of(newContext)("root").resolvable.value}")

    println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")
    println(s"Unresolved: \n${ByPK(classOf[UnresolvedDep]).of(nGlobal).toList.mkString("\n")}")
    println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")
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
  with RqHandlerRegistryImplApp
  with ByPKRequestHandlerApp
  with DepAssembleApp
  with ByClassNameRequestHandlerApp
  with SessionAttributeRequestHandlerApp {

  override def defaultModelFactories: List[DefaultModelFactory[_]] = DefaultPffNode :: super.defaultModelFactories

  override def sessionAttrs: List[SessionAttr[_ <: Product]] = SessionAttr(classOf[PffNode].getName, 0x0f1a, "", NameMetaAttr((0x0f1a).toString) :: Nil) :: super.sessionAttrs

  override def byClassNameClasses: List[Class[_ <: Product]] = classOf[PffNode] :: super.byClassNameClasses

  override def byPKClasses: List[Class[_]] = classOf[PffNode] :: super.byPKClasses

  override def handlers: List[RequestHandler[_]] = FooRequestHandler :: RootRequestHandler :: super.handlers

  override def protocols: List[Protocol] = LULProtocol :: TestRequests :: super.protocols

  override def assembles: List[Assemble] = new DepAssemble(handlerRegistry, QAdapterRegistryFactory(protocols)) :: super.assembles

  override def toStart: List[Executable] = new DepTestStart(execution, toUpdate, contextFactory) :: super.toStart
}