package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.DepDraft._
import ee.cone.c4actor.LULProtocol.LULNode
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble, by, was}
import ee.cone.c4proto.{Id, Protocol, protocol}


@protocol object LULProtocol extends Protocol {

  @Id(0x0001) case class LULNode(@Id(0x0003) srcId: String, @Id(0x0005) parentId: String)

}

case class A(src: String)

//  C4STATE_TOPIC_PREFIX=ee.cone.c4actor.DepTestApp sbt ~'c4actor-base-examples/runMain ee.cone.c4actor.ServerMain'
@assemble class DepAssemble(handlerRegistry: List[RequestHandler[_]]) extends Assemble {

  type ToResponse = SrcId

  def sparkJoiner
  (
    key: SrcId,
    nodes: Values[LULNode]
  ): Values[(SrcId, DepRequest)] =
    for (
      node ← nodes
    ) yield {
      println("Root rq Handle"); WithPK(RootRequest("root", "kek"))
    }

  def resultGrabber
    (
      key: SrcId,
      nodes: Values[UpResolvable]
    ): Values[(SrcId, A)] =
    for (
      node ← nodes
      if node.request.srcId == "root"
    ) yield {
      println(s"Final result: ${node.resolvable.value}")
      WithPK(A("kek"))
    }

  def request
  (
    key: SrcId,
    @was requests: Values[DepRequest],
    @was @by[ToResponse] responses: Values[Response]
  ): Values[(SrcId, UpResolvable)] =
    for (
      request <- requests
    ) yield {
      println(s"RQ $key = $request")
      val handler = handlerRegistry.filter(rqHandler => rqHandler.isDefinedAt == request.getClass).head.asInstanceOf[RequestHandler[Request[_]]]
      val dep: Dep[_] = handler.handle(request.asInstanceOf[Request[_]])
      val ctx: Ctx = buildContext(responses)
      println(s"CTX $key = $ctx")
      println(s"UpResolvable $key = ${dep.asInstanceOf[InnerDep[_]].resolve(ctx)}")
      WithPK(UpResolvable(request.asInstanceOf[Request[_]], dep.asInstanceOf[InnerDep[_]].resolve(ctx)))
    }


  type ToRequest = SrcId

  /*
  rq, List[resp] -> List[subRq], Resp
  subRq → List[subSubRq], Resp


  настакивает сет srcId parentRq


    WithPK where possible, if WithPK then to custom SrcId
    key → no plz no

   */

  def RSOtoRequest
  (
    key: SrcId,
    resolvable: Values[UpResolvable]
  ): Values[(SrcId, DepRequest)] =
    for (
      rs ← resolvable;
      rq ← rs.resolvable.requests
    ) yield {
      println(s"RSOtoRequest $key= ${rq.extendPrev(rs.request.srcId)}")
      (rq.srcId, rq.extendPrev(rs.request.srcId))
    }

  type ToResponseIntermin = SrcId

  def RSOtoResponse
  (
    key: SrcId,
    upResolvable: Values[UpResolvable]
  ): Values[(ToResponseIntermin, Response)] =
    for (
      rs ← upResolvable
    ) yield {
      println(s"RSOtoResponse $key= ${Response(rs.request, rs.resolvable.value, rs.request.prevSrcId)}")
      (rs.request.srcId, Response(rs.request, rs.resolvable.value, rs.request.prevSrcId))
    }

  def ResponseToRqKeyStacked
  (
    key: SrcId,
    @by[ToResponseIntermin] responses: Values[Response]
  ): Values[(ToResponse, Response)] =
    for (
      resp ← responses;
      srcId ← resp.rqList
    ) yield {
      println(s"ResponseToRqKeyStacked $srcId:$resp")
      (srcId, resp)
    }

  def ResponseToRqKey
  (
    key: SrcId,
    @by[ToResponseIntermin] responses: Values[Response]
  ): Values[(ToResponse, Response)] =
    for (
      resp ← responses
    ) yield {
      println(s"ResponseToRqKey $key:$resp")
      (resp.request.srcId, resp)
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
    execution.complete()
    println(s"Final result: ${ByPK(classOf[UpResolvable]).of(nGlobal)}")
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
  with ToStartApp {
  override def protocols: List[Protocol] = LULProtocol :: super.protocols

  override def assembles: List[Assemble] = new DepAssemble(FooRequestHandler :: RootRequestHandler :: Nil) :: super.assembles

  override def toStart: List[Executable] = new DepTestStart(execution, toUpdate, contextFactory) :: super.toStart
}