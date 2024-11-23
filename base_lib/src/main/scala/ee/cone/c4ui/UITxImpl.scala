package ee.cone.c4ui

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.Types.{LEvents, SrcId}
import ee.cone.c4actor._
import ee.cone.c4actor_branch._
import ee.cone.c4actor_branch.BranchProtocol.{N_BranchResult, N_RestPeriod}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{by, c4assemble}
import ee.cone.c4di.{c4, c4multi}
import ee.cone.c4gate.AlienProtocol.U_FromAlienState
import ee.cone.c4gate.AuthProtocol.U_AuthenticatedSession
import ee.cone.c4gate.{BranchWish, EventLogUtil, FromAlienWishUtil, SessionUtil}
import ee.cone.c4vdom.Types.{ElList, ViewRes}
import ee.cone.c4vdom._
import ee.cone.c4vdom_impl.VPair
import okio.ByteString

import java.net.URL
import java.time.Instant
import scala.Function.chain

@c4assemble("UICompApp") class UIAssembleBase(
  txFactory: UITxFactory, taskFactory: FromAlienTaskImplFactory, purgerFactory: UIAlienPurgerTxFactory,
){
  def mapTask(
    key: SrcId, session: Each[U_AuthenticatedSession], fromAlien: Each[U_FromAlienState]
  ): Values[(SrcId, FromAlienTask)] = if(fromAlien.location.isEmpty) Nil else {
    val url = new URL(fromAlien.location)
    List(WithPK(taskFactory.create(
      session.logKey, fromAlien, Option(url.getQuery).getOrElse(""), Option(url.getRef).getOrElse("")
    )))
  }
  //
  type ByBranch = SrcId
  def mapSession(key: SrcId, session: Each[U_AuthenticatedSession]): Values[(ByBranch, U_AuthenticatedSession)] =
    Seq(session.logKey -> session)
  def joinHandler(key: SrcId, @by[ByBranch] sessions: Values[U_AuthenticatedSession]): Values[(SrcId,TxTransform)] =
    Seq(WithPK(txFactory.create(key, sessions.map(_.sessionKey).min)))
  //
  def purger(key: SrcId, firstborn: Each[S_Firstborn]): Values[(SrcId,TxTransform)] =
    Seq(WithPK(purgerFactory.create()))
}

@c4multi("UICompApp") final case class UIAlienPurgerTx(srcId: SrcId = "UIAlienPurgerTx")(
  fromAlienWishUtil: FromAlienWishUtil, txAdd: LTxAdd
) extends TxTransform {
  def transform(local: Context): Context = {
    val lEvents = fromAlienWishUtil.purgeAllExpired(local)
    txAdd.add(lEvents).andThen(SleepUntilKey.set(Instant.now.plusSeconds(300)))(local)
  }
}

@c4multi("AlienExchangeCompApp") final case class FromAlienTaskImpl(
  branchKey: SrcId, fromAlienState: U_FromAlienState, locationQuery: String, locationHash: String
)(txAdd: LTxAdd, sessionUtil: SessionUtil) extends FromAlienTask with BranchTask {
  def branchTask: BranchTask = this
  def product: Product = fromAlienState
  def relocate(to: String): Context => Context = local => {
    val sessionKey = fromAlienState.sessionKey
    val locationWithoutHash = sessionUtil.location(local, sessionKey).split("#")
    match { case Array(l) => l case Array(l,_) => l }
    val lEvents = sessionUtil.setLocation(local, sessionKey, s"$locationWithoutHash#$to")
    txAdd.add(lEvents)(local)
  }
}

@c4("UICompApp") final class EnableBranchScaling extends EnableSimpleScaling(classOf[UITx])

@c4("UICompApp") final case class UIPostHandler(
  branchErrorSaver: Option[BranchErrorSaver], catchNonFatal: CatchNonFatal, fromAlienWishUtil: FromAlienWishUtil,
  vDomResolver: VDomResolver,
) extends LazyLogging {
  // do
  private def handleError(local: Context, branchKey: String, err: Throwable) =
    (local, branchErrorSaver.toSeq.flatMap(_.saveErrors(local, branchKey, err)))
  private def measure[T](before: String)(f: ()=>T)(after: Long=>String): T = {
    logger.debug(before)
    val end = NanoTimer()
    val res = f()
    logger.debug(after(end.ms))
    res
  }
  //dispatches incoming message // can close / set refresh time
  private def dispatch(local: Context, message: VDomMessage): (Context, LEvents) =
    message.header("x-r-op") match {
      case "redraw" => (resetUntil(local), Nil)
      case "" =>
        val path = message.header("x-r-vdom-path")
        if(path.nonEmpty) vDomResolver.resolve(path)(VDomStateKey.of(local).map(_.value)) match {
          case Some(v: Receiver[_]) =>
            (resetUntil(v.asInstanceOf[Receiver[Context]].receive(message)(local)), Nil)
          case v =>
            logger.warn(s"$path ($v) can not receive")
            (local, Nil)
        } else (local, Nil)
    }
  private def resetUntil: Context => Context = VDomStateKey.modify(_.map(st=>st.copy(until = 0)))
  def handle(local: Context, wish: BranchWish): (Context, LEvents) =
    measure(s"branch ${wish.branchKey} tx begin ${wish.index}"){() =>
      val (nLocal, lEvents) = catchNonFatal{
        val message = VDomMessageImpl(wish, fromAlienWishUtil.parsePairs(wish.value).toMap)
        dispatch(local, message)
      }(s"branch ${wish.branchKey} dispatch failed")(handleError(local, wish.branchKey, _))
      (nLocal, lEvents ++ fromAlienWishUtil.addAck(wish))
    }(t => s"branch ${wish.branchKey} tx done in $t ms")
}

case class VDomMessageImpl(wish: BranchWish, headerMap: Map[String, String]) extends VDomMessage {
  def header: String => String = {
    case "x-r-auth" => throw new Exception("not supported, work with reqId on react level")
    case "x-r-session" => wish.sessionKey
    case "x-r-branch" => wish.branchKey
    case "x-r-index" => wish.index.toString
    case k => headerMap.getOrElse(k, "")
  }
  def body: Object = ByteString.encodeUtf8(headerMap.getOrElse("value", ""))
}

@c4multi("UICompApp") final case class UIViewer(branchKey: String, sessionKey: String)(
  catchNonFatal: CatchNonFatal,
  sessionUtil: SessionUtil, eventLogUtil: EventLogUtil, fromAlienWishUtil: FromAlienWishUtil,
  branchOperations: BranchOperations, getView: GetByPK[View], vDomHandler: VDomHandler, vDomUntil: VDomUntil,
  rootTagsProvider: RootTagsProvider, setLocationReceiverFactory: SetLocationReceiverFactory,
){
  private val rootTags: RootTags[Context] = rootTagsProvider.get[Context]
  private def eventLogChanges(local: Context, res: PostViewResult): LEvents =
    if(res.diff.isEmpty && res.snapshot.isEmpty) Nil
    else eventLogUtil.write(local, branchKey, res.diff, Option(res.snapshot).filter(_.nonEmpty))
  private def handleReView(local: Context, preViewRes: PreViewResult): (Context, LEvents) = {
    val location = sessionUtil.location(local, sessionKey)
    val locationChange = setLocationReceiverFactory.create(sessionKey)
    val viewOpt = getView.ofA(local).get(branchKey)
    val (children, failure) = catchNonFatal{ (viewOpt.fold(Nil:ViewRes)(_.view(local)), "") }("view failed"){ err =>
      (Nil, err match { case b: BranchError => b.message(local) case _ => "Internal Error" })
    }
    val ackEls = fromAlienWishUtil.ackList(local, branchKey).map{ case (k,v) => rootTags.ackElement(k,k,v.toString) }
    val locationEl = rootTags.location("location", location, locationChange).toChildPair
    val nextDom = rootTags.rootElement(
      key = "root", failure = failure, ackList = ackEls, children = locationEl :: children,
    ).toChildPair.asInstanceOf[VPair].value
    val res = vDomHandler.postView(preViewRes, nextDom)
    val seeds = res.seeds.collect{ case r: N_BranchResult => r }
    val nState = res.cache.copy(until = System.currentTimeMillis + vDomUntil.get(seeds))
    val seedChanges = branchOperations.saveChanges(local, branchKey, seeds)
    (VDomStateKey.set(Option(nState))(local), seedChanges ++ eventLogChanges(local, res))
  }
  def handle(local: Context): (Context, LEvents) =
    vDomHandler.preView(VDomStateKey.of(local)).fold((local,Nil:LEvents)){ preViewRes =>
      handleReView(chain(Seq(
        VDomStateKey.set(Option(preViewRes.clean)),
        CurrentBranchKey.set(branchKey),
      ))(local), preViewRes)
    }
}

@c4multi("UICompApp") final case class UITx(branchKey: String, sessionKey: String)(
  txAdd: LTxAdd, sessionUtil: SessionUtil, branchOperations: BranchOperations,
  uiViewerFactory: UIViewerFactory, uiPostHandler: UIPostHandler, fromAlienWishUtil: FromAlienWishUtil,
) extends TxTransform with LazyLogging {
  private def purge(local: Context) =
    (local, sessionUtil.purge(local, sessionKey) ++ branchOperations.purge(local, branchKey))
  def transform(local: Context): Context = {
    val (nLocal, lEvents): (Context, LEvents) =
      if(sessionUtil.expired(local, sessionKey)) purge(local)
      else fromAlienWishUtil.getBranchWish(local, branchKey)
        .fold(uiViewerFactory.create(branchKey, sessionKey).handle(local))(wish => uiPostHandler.handle(local, wish))
    txAdd.add(lEvents)(nLocal)
  }
}

@c4("UICompApp") final class VDomUntilImpl(branchOperations: BranchOperations) extends VDomUntil {
  def get(seeds: Seq[N_BranchResult]): Long = branchOperations.collect(seeds, classOf[N_RestPeriod]) match {
    case l if l.isEmpty => 0L case l => l.map(_.value).min
  }
}

@c4multi("UICompApp") final case class SetLocationReceiver(sessionKey: String)(
  sessionUtil: SessionUtil, txAdd: LTxAdd,
) extends Receiver[Context] {
  def receive: Handler = message => local => {
    val value = message.body match { case b: ByteString => b.utf8() }
    val lEvents = sessionUtil.setLocation(local, sessionKey, value)
    txAdd.add(lEvents)(local)
  }
}

trait AckEl extends ToChildPair
@c4tags("UICompApp") trait RootTags[C] {
  @c4el("RootElement") def rootElement(
    key: String, failure: String, ackList: ElList[AckEl], children: ViewRes
  ): ToChildPair
  @c4el("AckElement") def ackElement(key: String, observerKey: String, indexStr: String): AckEl
  @c4el("LocationElement") def location(key: String, value: String, change: Receiver[C]): ToChildPair
}
