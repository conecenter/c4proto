package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.CtxType.Ctx
import ee.cone.c4actor.DepDraft._
import ee.cone.c4actor.LULProtocol.LULNode
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.dependancy._
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble, by, was}
import ee.cone.c4proto.{Id, Protocol, protocol}


@protocol object LULProtocol extends Protocol {

  @Id(0x0001) case class LULNode(@Id(0x0003) srcId: String, @Id(0x0005) parentId: String)

}

//  C4STATE_TOPIC_PREFIX=ee.cone.c4actor.DepTestApp sbt ~'c4actor-base-examples/runMain ee.cone.c4actor.ServerMain'
@assemble class DepAssemble(handlerRegistry: RequestHandlerRegistry) extends Assemble {

  def sparkJoiner
  (
    key: SrcId,
    nodes: Values[LULNode]
  ): Values[(SrcId, Request)] =
    for (
      node ← nodes
    ) yield {
      //println("Root rq Handle")
      WithPK(RootDepRequest("root", "kek"))
    }

  type ToResponse = SrcId

  def request
  (
    key: SrcId,
    @was requests: Values[Request],
    @was @by[ToResponse] responses: Values[Response]
  ): Values[(SrcId, UpResolvable)] =
    for (
      request <- requests
    ) yield {
      //println(s"RQ $key = $request")
      val dep: Dep[_] = handlerRegistry.handle(request)
      val ctx: Ctx = buildContext(responses)
      //println(s"CTX $key = $ctx")
      //println(s"UpResolvable $key = ${dep.asInstanceOf[InnerDep[_]].resolve(ctx)}")
      WithPK(UpResolvable(request.asInstanceOf[DepRequest[_]], dep.asInstanceOf[InnerDep[_]].resolve(ctx)))
    }

  def RSOtoRequest
  (
    key: SrcId,
    resolvable: Values[UpResolvable]
  ): Values[(SrcId, Request)] =
    for (
      rs ← resolvable;
      rq ← rs.resolvable.requests
    ) yield {
      //println(s"RSOtoRequest $key= ${rq.extendPrev(rs.request.srcId)}")
      WithPK(rq.extendPrev(rs.request.srcId))
    }

  def ResponseToLUL
  (
    key: SrcId,
    upResolvable: Values[UpResolvable]
  ): Values[(ToResponse, Response)] =
    upResolvable.flatMap { upRes ⇒
      val response = Response(upRes.request, upRes.resolvable.value, upRes.request.prevSrcId)
      WithPK(response) ::
        (for (srcId ← response.rqList) yield (srcId, response))
    }

}

class DepTestStart(
  execution: Execution, toUpdate: ToUpdate, contextFactory: ContextFactory
) extends Executable with LazyLogging {
  def run() = {
    import LEvent.update

    val recs = update(LULNode("1", ""))
    /*
          update(Node("12","1")) ++ update(Node("13","1")) ++
          update(Node("124","12")) ++ update(Node("125","12"))*/
    val updates = recs.map(rec ⇒ toUpdate.toUpdate(rec)).toList
    val context = contextFactory.create()
    val nGlobal = ReadModelAddKey.of(context)(updates)(context)

    //logger.info(s"${nGlobal.assembled}")
    logger.debug("asddfasdasdasdas")
    println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")
    println(s"Final result: ${ByPK(classOf[UpResolvable]).of(nGlobal)("root").resolvable.value.get}")
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
  with RqHandlerRegistryImplApp {
  override def handlers: List[RequestHandler[_]] = FooRequestHandler :: RootRequestHandler :: super.handlers

  override def protocols: List[Protocol] = LULProtocol :: super.protocols

  override def assembles: List[Assemble] = new DepAssemble(handlerRegistry) :: super.assembles

  override def toStart: List[Executable] = new DepTestStart(execution, toUpdate, contextFactory) :: super.toStart
}