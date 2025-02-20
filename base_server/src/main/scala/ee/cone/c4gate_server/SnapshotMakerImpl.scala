package ee.cone.c4gate_server

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol._
import ee.cone.c4actor.Types._
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import ee.cone.c4gate.HttpProtocol._
import ee.cone.c4di.c4
import ee.cone.c4gate._
import ee.cone.c4gate_server.RHttpTypes.RHttpHandlerCreate
import ee.cone.c4gate.Time._
import scala.annotation.tailrec

@c4("SnapshotMakingApp") final class HttpGetSnapshotHandler(snapshotLoader: SnapshotLoader, httpResponseFactory: RHttpResponseFactory) extends LazyLogging {
  def wire: RHttpHandlerCreate = next => (request,local) =>
    if(request.method == "GET" && request.path.startsWith("/snapshot")){
      val path = request.path
      logger.debug(s"Started loading snapshot ${path.tail}")
      snapshotLoader.load(RawSnapshot(path.tail)) // path will be checked inside loader
        .fold(next(request,local))(ev=>httpResponseFactory.directResponse(request,_.copy(body=ev.data)))
    } else next(request,local)
}

@c4assemble("SnapshotMakingApp") class SnapshotMakingAssembleBase(actorName: ActorName, snapshotMaking: SnapshotMaker, maxTime: SnapshotMakerMaxTime, signatureChecker: SnapshotTaskSigner, signedReqUtil: SignedReqUtil)   {
  type NeedSnapshot = SrcId

  def needConsumer(
    key: SrcId,
    first: Each[S_Firstborn]
  ): Values[(SrcId,LocalHttpConsumer)] =
    List(WithPK(LocalHttpConsumer(SnapshotMakingUtil.url)))

  def mapAll(
    key: SrcId,
    post: Each[S_HttpRequest]
  ): Values[(NeedSnapshot,S_HttpRequest)] =
    if(post.path == SnapshotMakingUtil.url) List(actorName.value->post) else Nil

  def mapFirstborn(
    key: SrcId,
    firstborn: Each[S_Firstborn],
    @by[NeedSnapshot] posts: Values[S_HttpRequest]
  ): Values[(SrcId,TxTransform)] = {
    val srcId = "snapshotMaker"
    if(posts.isEmpty) List(WithPK(PeriodicSnapshotMakingTx(srcId)(snapshotMaking,maxTime)))
    else {
      def task(post: S_HttpRequest) =
        signatureChecker.retrieve(check=false)(signedReqUtil.signed(post.headers))
        // check=false <== no system time in joiners
      val taskOpt = task(posts.minBy(_.srcId))
      val similarPosts = posts.toList.filter(post => task(post)==taskOpt).sortBy(_.srcId)
      List(WithPK(RequestedSnapshotMakingTx(srcId, taskOpt, similarPosts)(snapshotMaking, signatureChecker, signedReqUtil)))
    }
  }
}

object SnapshotMakingUtil {
  val url = "/need-snapshot"
}

case object DeferPeriodicSnapshotUntilKey extends TransientLens[Long](0L)

case class PeriodicSnapshotMakingTx(srcId: SrcId)(
  snapshotMaking: SnapshotMakerImpl
) extends TxTransform with LazyLogging {
  def transform(local: Context): Context = if(DeferPeriodicSnapshotUntilKey.of(local) < now){
    if(snapshotMaking.maxTime + 20*minute < now){
      val rawSnapshot = snapshotMaking.make(local)
      logger.debug(s"periodic snapshot created: ${rawSnapshot.relativePath}")
    }
    DeferPeriodicSnapshotUntilKey.set(now+minute)(local)
  } else local
}

case class RequestedSnapshotMakingTx(
  srcId: SrcId, taskOpt: Option[SnapshotTask], requests: List[S_HttpRequest]
)(
  snapshotMaking: SnapshotMaker,
  signatureChecker: SnapshotTaskSigner,
  signedReqUtil: SignedReqUtil
) extends TxTransform {
  import signedReqUtil._
  def transform(local: Context): Context = catchNonFatal {
    val task = taskOpt.get
    val (authorized, nonAuthorized) = requests.partition(post =>
      signatureChecker.retrieve(check=true)(signed(post.headers)).nonEmpty
    )
    val res = if (authorized.nonEmpty) snapshotMaking.makeOrFind(local, task.offsetOpt) else Nil
    val goodResp = List(N_Header("x-r-snapshot-keys", res.map(_.relativePath).mkString(",")))
    val authorizedResponses = authorized.map(au => au -> goodResp)
    val nonAuthorizedResponses = nonAuthorized.map(nau => nau -> "Non authorized request")
    respond(authorizedResponses, nonAuthorizedResponses)(local)
  }("make-snapshot"){ e =>
    val message = e.getMessage
    e.printStackTrace()
    respond(Nil,requests.map(_ -> message))(local)
  }
}



//todo new

@c4("SnapshotMakingApp") final class SnapshotMakerImpl(
  snapshotLister: SnapshotLister,
  snapshotSaverFactory: SnapshotSaverFactory, toUpdate: ToUpdate, reducer: RichRawWorldReducer, getOffset: GetOffset,
) extends SnapshotMaker with SnapshotMakerMaxTime with LazyLogging {
  @tailrec private def makeStatLine(
    currType: Long, currCount: Long, currSize: Long, updates: List[N_UpdateFrom]
  ): List[N_UpdateFrom] =
    if(updates.isEmpty || currType != updates.head.valueTypeId) {
      logger.debug(s"t:${java.lang.Long.toHexString(currType)} c:$currCount s:$currSize")
      updates
    } else {
      val size = currSize + toUpdate.getInnerSize(updates.head)
      makeStatLine(currType,currCount+1,size,updates.tail)
    }
  @tailrec private def makeStats(updates: List[N_UpdateFrom]): Unit =
    if(updates.nonEmpty) makeStats(makeStatLine(updates.head.valueTypeId,0,0,updates))
  private def save(local: Context): RawSnapshot = {
    logger.debug("Saving...")
    val updates = reducer.toUpdates(local)
    makeStats(updates)
    val (bytes, headers) = toUpdate.toBytes(updates)
    val res = snapshotSaverFactory.create("snapshots").save(getOffset.of(local), bytes, headers)
    logger.debug("Saved")
    res
  }
  def make(local: Context): RawSnapshot = {
    val result = save(local)
    System.gc()
    result
  }
  def makeOrFind(local: Context, offsetOpt: Option[NextOffset]): List[RawSnapshot] =
    offsetOpt.fold(List(make(local)))(offset=>snapshotLister.list.filter(_.offset == offset).map(_.raw))
  def maxTime: Long = snapshotLister.listWithMTime.headOption.fold(0L)(s=>s.mTime)
}

trait SnapshotMakerMaxTime {
  def maxTime: Long
}

@c4("DisableDefaultSafeToRunApp") final class DisableDefaultSafeToRun

@c4("SafeToRunApp") final class SafeToRun(
  snapshotMaker: SnapshotMakerMaxTime, disable: Option[DisableDefaultSafeToRun],
) extends Executable with Early {
  def run(): Unit = if(disable.isEmpty){
    Thread.sleep(10*minute)
    iter()
  }
  @tailrec private def iter(): Unit = {
    assert(now < snapshotMaker.maxTime + 3*hour)
    Thread.sleep(hour)
    iter()
  }
}

////

@c4("SnapshotMakingApp") final class FileSnapshotConfigImpl(config: ListConfig)(
  val ignore: Set[Long] = (for {
    content <- config.get("C4IGNORE_IDS")
    found <- """([0-9a-f]+)""".r.findAllIn(content)
  } yield java.lang.Long.parseLong(found, 16)).toSet
) extends SnapshotConfig

@c4("S3RawSnapshotSaverApp") final class S3RawSnapshotSaver(
  s3: S3Manager, util: SnapshotUtil, execution: Execution,
  currentTxLogName: CurrentTxLogName,
) extends RawSnapshotSaver with SnapshotRemover with LazyLogging {
  def save(snapshot: RawSnapshot, data: Array[Byte]): Unit =
    s3.put(currentTxLogName, snapshot.relativePath, data)
  def deleteIfExists(snapshot: SnapshotInfo): Boolean =
    execution.aWait{ implicit ec =>
      s3.delete(currentTxLogName, snapshot.raw.relativePath)
    }
}

/*
req snap:
  gen resp id
  post
  get resp, if(!200) sleep, repeat

start:
  req snap
  loader load le ref

merge:
  2
    req snap

start gate:
  gate: ls-stream ld local
start app:
  app: order state, ls-stream ld remote
  gate: ls-head ld local, save local
periodic:
  gate: ls-head ld local, save local
merge:
  app: order state, ld remote , diff
  gate-s: ls-head ld local, save local
debug:
  app: order state +tx, ld remote +tx, diff
  gate-s: ls-head ld local, save local +tx

 */
