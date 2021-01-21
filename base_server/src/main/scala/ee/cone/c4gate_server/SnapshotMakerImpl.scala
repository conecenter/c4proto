package ee.cone.c4gate_server

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.{N_Update, S_Firstborn}
import ee.cone.c4actor.Types.{NextOffset, SrcId}
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import ee.cone.c4gate.HttpProtocol._
import ee.cone.c4proto.ToByteString
import ee.cone.c4di.c4
import ee.cone.c4gate._
import ee.cone.c4gate_server.RHttpTypes.RHttpHandlerCreate
import okio.ByteString

import java.time.ZonedDateTime
import scala.annotation.tailrec
// import scala.collection.JavaConverters.iterableAsScalaIterableConverter
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.IterableHasAsScala

import scala.xml.XML

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

object Time {
  def hour: Long = 60L*minute
  def minute: Long = 60L*1000L
  def now: Long = System.currentTimeMillis
}
import ee.cone.c4gate_server.Time._

case object DeferPeriodicSnapshotUntilKey extends TransientLens[Long](0L)

case class PeriodicSnapshotMakingTx(srcId: SrcId)(snapshotMaking: SnapshotMaker, maxTime: SnapshotMakerMaxTime) extends TxTransform with LazyLogging {
  def transform(local: Context): Context = if(DeferPeriodicSnapshotUntilKey.of(local) < now){
    if(maxTime.maxTime + hour < now){
      val rawSnapshots = snapshotMaking.make(NextSnapshotTask(None))
      rawSnapshots.foreach(s=>logger.debug(s"periodic snapshot created: ${s.relativePath}"))
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
    val res = if (authorized.nonEmpty) snapshotMaking.make(task) else Nil
    val goodResp = List(N_Header("x-r-snapshot-keys", res.map(_.relativePath).mkString(",")))
    val authorizedResponses = authorized.map(au => au -> goodResp)
    val nonAuthorizedResponses = nonAuthorized.map(nau => nau -> "Non authorized request")
    respond(authorizedResponses, nonAuthorizedResponses)(local)
  }("make-snapshot"){ e =>
    val message = e.getMessage
    respond(Nil,requests.map(_ -> message))(local)
  }
}

class SnapshotSavers(val full: SnapshotSaver, val tx: SnapshotSaver)

//todo new
@c4("SnapshotMakingApp") final class SnapshotMakerImpl(
  snapshotConfig: SnapshotConfig,
  snapshotLister: SnapshotLister,
  snapshotLoader: SnapshotLoader,
  snapshotSavers: SnapshotSavers,
  consuming: Consuming,
  toUpdate: ToUpdate
) extends SnapshotMaker with SnapshotMakerMaxTime with LazyLogging {

  private def reduce(events: List[RawEvent]): SnapshotWorld=>SnapshotWorld = if(events.isEmpty) w=>w else world=>{
    val updates = toUpdate.toUpdates(events)
    logger.debug(s"Reduce: got updates ${updates.size}")
    val newState = updates.foldLeft(world.state){(state,up)=>
      if(snapshotConfig.ignore(up.valueTypeId)) state
      else if(up.value.size > 0) state + (toUpdate.toKey(up)->up)
      else state - up
    }
    new SnapshotWorld(newState,events.last.srcId)
  }

  private def load(snapshotFilter: Option[NextOffset=>Boolean]): SnapshotWorld = {
    val srcId = "0" * OffsetHexSize()
    val emptyRawWorld = new SnapshotWorld(Map.empty, srcId)
    (for{
      snapshot <- snapshotLister.list.toStream if snapshotFilter.forall(_(snapshot.offset))
      event <- snapshotLoader.load(snapshot.raw)
    } yield {
      reduce(List(event))(emptyRawWorld)
    }).headOption.getOrElse(emptyRawWorld)
  }

  @tailrec private def makeStatLine(
    currType: Long, currCount: Long, currSize: Long, updates: List[N_Update]
  ): List[N_Update] =
    if(updates.isEmpty || currType != updates.head.valueTypeId) {
      logger.debug(s"t:${java.lang.Long.toHexString(currType)} c:$currCount s:$currSize")
      updates
    } else makeStatLine(currType,currCount+1,currSize+updates.head.value.size(),updates.tail)
  @tailrec private def makeStats(updates: List[N_Update]): Unit =
    if(updates.nonEmpty) makeStats(makeStatLine(updates.head.valueTypeId,0,0,updates))

  private def save(world: SnapshotWorld): RawSnapshot = {
    logger.debug("Saving...")
    val updates = world.state.values.toList.sortBy(toUpdate.by)
    makeStats(updates)
    val (bytes, headers) = toUpdate.toBytes(updates)
    val res = snapshotSavers.full.save(world.offset, bytes, headers)
    logger.debug("Saved")
    res
  }
  private def progress(skipReportUntil: Long, offset: NextOffset, endOffset: NextOffset): Long =
    if(now<skipReportUntil) skipReportUntil else {
      logger.info(s"$offset/$endOffset")
      now + 2000
    }

  def make(task: SnapshotTask): List[RawSnapshot] = concurrent.blocking {
    val result = makeInner(task)
    System.gc()
    result
  }

  def makeInner(task: SnapshotTask): List[RawSnapshot] = {
    logger.debug(s"Loading snapshot for $task...")
    val offsetOpt = task.offsetOpt
    val offsetFilter: NextOffset=>NextOffset=>Boolean = task match {
      case t: NextSnapshotTask => end=>curr=>curr<=end
      case t: DebugSnapshotTask => end=>curr=>curr<end
    }
    val initialRawWorld = load(offsetOpt.map(offsetFilter))
    logger.debug("Consuming...")
    consuming.process(initialRawWorld.offset, consumer => {
      @tailrec def iteration(world: SnapshotWorld, endOffset: NextOffset, skipReportUntil: Long): List[RawSnapshot] = {
        val events = consumer.poll()
        val (lEvents, gEvents) = events.span(ev => offsetFilter(endOffset)(ev.srcId))
        val nWorld = reduce(lEvents)(world)
        val nSkip = progress(skipReportUntil,nWorld.offset,endOffset)
        task match {
          case t: NextSnapshotTask =>
            if(nWorld.offset == endOffset) {
              val saved = save(nWorld)
              if(offsetOpt.nonEmpty) List(saved) else snapshotLister.list.map(_.raw)
            } else {
              assert(gEvents.isEmpty)
              iteration(nWorld, endOffset, nSkip)
            }
          case t: DebugSnapshotTask =>
            if(gEvents.nonEmpty){
              assert(endOffset == gEvents.head.srcId)
              List(save(nWorld), snapshotSavers.tx.save(endOffset, gEvents.head.data.toByteArray, gEvents.head.headers))
            } else iteration(nWorld, endOffset, nSkip)
        }
      }
      iteration(initialRawWorld, offsetOpt.getOrElse(consumer.endOffset),0L)
    })
  }

  def maxTime: Long =
    snapshotLister.listWithMTime.headOption.fold(0L)(s=>s.mTime)
}

trait SnapshotMakerMaxTime {
  def maxTime: Long
}

@c4("SafeToRunApp") final class SafeToRun(snapshotMaker: SnapshotMakerMaxTime) extends Executable with Early {
  def run(): Unit = concurrent.blocking{
    Thread.sleep(10*minute)
    iter()
  }
  @tailrec private def iter(): Unit = {
    assert(now < snapshotMaker.maxTime + 3*hour)
    Thread.sleep(hour)
    iter()
  }
}

class SnapshotWorld(val state: Map[N_Update,N_Update],val offset: NextOffset)

trait SnapshotConfig {
  def ignore: Set[Long]
}

////

@c4("SnapshotMakingApp") final class FileSnapshotConfigImpl(config: ListConfig)(
  val ignore: Set[Long] = (for {
    content <- config.get("C4IGNORE_IDS")
    found <- """([0-9a-f]+)""".r.findAllIn(content)
  } yield java.lang.Long.parseLong(found, 16)).toSet
) extends SnapshotConfig

@c4("S3RawSnapshotLoaderApp") final class S3RawPrefix(config: Config)(
  value: String = s"/${config.get("C4INBOX_TOPIC_PREFIX")}."
){
  def ++(that: String): String = s"$value$that"
  def ++(that: RawSnapshot): String = this ++ that.relativePath
}

@c4("S3RawSnapshotLoaderApp") final class S3RawSnapshotLoaderImpl(
  s3: S3Manager, util: SnapshotUtil, prefix: S3RawPrefix,
) extends RawSnapshotLoader with SnapshotLister with LazyLogging {
  def load(snapshot: RawSnapshot): ByteString = {
    ToByteString(s3.get(prefix++snapshot).get)
  }
  private def infix = "snapshots"
  def listInner(): List[(RawSnapshot,String)] = for {
    xmlBytes <- s3.get(prefix++infix).toList
    xml = XML.loadString(new String(xmlBytes,UTF_8))
    _ = logger.debug(s"$xml")
    item <- xml \ "Contents"
  } yield (RawSnapshot(s"$infix/${(item \ "Key").text}"), (item \ "LastModified").text)
  def list: List[SnapshotInfo] = (for{
    (rawSnapshot,_) <- listInner()
    _ = logger.debug(s"$rawSnapshot")
    snapshotInfo <- util.hashFromName(rawSnapshot)
  } yield snapshotInfo).sortBy(_.offset).reverse
  def listWithMTime: List[TimedSnapshotInfo] = (for{
    (rawSnapshot,mTimeStr) <- listInner()
    snapshotInfo <- util.hashFromName(rawSnapshot)
  } yield {
    val mTime = ZonedDateTime.parse(mTimeStr).toInstant.toEpochMilli
    TimedSnapshotInfo(snapshotInfo,mTime)
  }).sortBy(_.snapshot.offset).reverse
}

@c4("S3RawSnapshotSaverApp") final class S3RawSnapshotSaver(
  s3: S3Manager, util: SnapshotUtil, prefix: S3RawPrefix,
) extends RawSnapshotSaver with SnapshotRemover with LazyLogging {
  def save(snapshot: RawSnapshot, data: Array[Byte]): Unit = {
    val bucket = prefix ++ util.hashFromName(snapshot).get.subDirStr
    if(s3.get(bucket).isEmpty) s3.put(bucket,Array.empty)
    s3.put(prefix++snapshot, data)
  }
  def deleteIfExists(snapshot: SnapshotInfo): Boolean =
    s3.delete(prefix++snapshot.raw)
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
