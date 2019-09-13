package ee.cone.c4gate

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.{N_Update, S_Firstborn}
import ee.cone.c4actor.Types.{NextOffset, SrcId}
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import ee.cone.c4gate.HttpProtocol.{N_Header, S_HttpPublication, S_HttpRequest}
import ee.cone.c4gate.HttpProtocolBase.{S_HttpRequest, S_HttpResponse}
import ee.cone.c4proto.ToByteString
import okio.ByteString

import scala.annotation.tailrec
import scala.collection.JavaConverters.iterableAsScalaIterableConverter
import scala.concurrent.{ExecutionContext, Future}

class HttpGetSnapshotHandler(snapshotLoader: SnapshotLoader, httpResponseFactory: RHttpResponseFactory, next: RHttpHandler) extends RHttpHandler with LazyLogging {
  def handle(request: S_HttpRequest, local: Context): RHttpResponse =
    if(request.method == "GET" && request.path.startsWith("/snapshot")){
      val path = request.path
      logger.debug(s"Started loading snapshot ${path.tail}")
      snapshotLoader.load(RawSnapshot(path.tail)) // path will be checked inside loader
        .fold(next.handle(request,local))(ev⇒httpResponseFactory.directResponse(request,_.copy(body=ev.data)))
    } else next.handle(request,local)
}

@assemble class SnapshotMakingAssembleBase(actorName: String, snapshotMaking: SnapshotMakerImpl, signatureChecker: Signer[SnapshotTask], signedReqUtil: SignedReqUtil)   {
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
    if(post.path == SnapshotMakingUtil.url) List(actorName→post) else Nil

  def mapFirstborn(
    key: SrcId,
    firstborn: Each[S_Firstborn],
    @by[NeedSnapshot] posts: Values[S_HttpRequest]
  ): Values[(SrcId,TxTransform)] = {
    val srcId = "snapshotMaker"
    if(posts.isEmpty) List(WithPK(PeriodicSnapshotMakingTx(srcId)(snapshotMaking)))
    else {
      def task(post: S_HttpRequest) =
        signatureChecker.retrieve(check=false)(signedReqUtil.signed(post.headers))
        // check=false <== no system time in joiners
      val taskOpt = task(posts.minBy(_.srcId))
      val similarPosts = posts.toList.filter(post ⇒ task(post)==taskOpt).sortBy(_.srcId)
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
import ee.cone.c4gate.Time._

case object DeferPeriodicSnapshotUntilKey extends TransientLens[Long](0L)

case class PeriodicSnapshotMakingTx(srcId: SrcId)(snapshotMaking: SnapshotMakerImpl) extends TxTransform {
  def transform(local: Context): Context = if(DeferPeriodicSnapshotUntilKey.of(local) < now){
    if(snapshotMaking.maxTime + hour < now) snapshotMaking.make(NextSnapshotTask(None))
    DeferPeriodicSnapshotUntilKey.set(now+minute)(local)
  } else local
}

case class RequestedSnapshotMakingTx(
  srcId: SrcId, taskOpt: Option[SnapshotTask], requests: List[S_HttpRequest]
)(
  snapshotMaking: SnapshotMaker,
  signatureChecker: Signer[SnapshotTask],
  signedReqUtil: SignedReqUtil
) extends TxTransform {
  import signedReqUtil._
  def transform(local: Context): Context = catchNonFatal {
    val task = taskOpt.get
    val (authorized, nonAuthorized) = requests.partition(post ⇒
      signatureChecker.retrieve(check=true)(signed(post.headers)).nonEmpty
    )
    val res = if (authorized.nonEmpty) snapshotMaking.make(task) else Nil
    val goodResp = List(N_Header("X-r-snapshot-keys", res.map(_.relativePath).mkString(",")))
    val authorizedResponses = authorized.map(au ⇒ au → goodResp)
    val nonAuthorizedResponses = nonAuthorized.map(nau ⇒ nau → "Non authorized request")
    respond(authorizedResponses, nonAuthorizedResponses)(local)
  }("make-snapshot"){ e ⇒
    val message = e.getMessage
    respond(Nil,requests.map(_ → message))(local)
  }
}

//todo new
class SnapshotMakerImpl(
  snapshotConfig: SnapshotConfig,
  snapshotLister: SnapshotLister,
  snapshotLoader: SnapshotLoader,
  rawSnapshotLoader: FileRawSnapshotLoader,
  fullSnapshotSaver: SnapshotSaver,
  txSnapshotSaver: SnapshotSaver,
  consuming: Consuming,
  toUpdate: ToUpdate
) extends SnapshotMaker with LazyLogging {

  private def reduce(events: List[RawEvent]): SnapshotWorld⇒SnapshotWorld = if(events.isEmpty) w⇒w else world⇒{
    val updates = toUpdate.toUpdates(events)
    logger.debug(s"Reduce: got updates ${updates.size}")
    val newState = (world.state /: updates){(state,up)⇒
      if(snapshotConfig.ignore(up.valueTypeId)) state
      else if(up.value.size > 0) state + (toUpdate.toKey(up)→up)
      else state - up
    }
    new SnapshotWorld(newState,events.last.srcId)
  }

  private def load(snapshotFilter: Option[NextOffset⇒Boolean]): SnapshotWorld = {
    val srcId = "0" * OffsetHexSize()
    val emptyRawWorld = new SnapshotWorld(Map.empty, srcId)
    (for{
      snapshot ← snapshotLister.list.toStream if snapshotFilter.forall(_(snapshot.offset))
      event ← snapshotLoader.load(snapshot.raw)
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
    val res = fullSnapshotSaver.save(world.offset, bytes, headers)
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
    val offsetFilter: NextOffset⇒NextOffset⇒Boolean = task match {
      case t: NextSnapshotTask ⇒ end⇒curr⇒curr<=end
      case t: DebugSnapshotTask ⇒ end⇒curr⇒curr<end
    }
    val initialRawWorld = load(offsetOpt.map(offsetFilter))
    logger.debug("Consuming...")
    consuming.process(initialRawWorld.offset, consumer ⇒ {
      @tailrec def iteration(world: SnapshotWorld, endOffset: NextOffset, skipReportUntil: Long): List[RawSnapshot] = {
        val events = consumer.poll()
        val (lEvents, gEvents) = events.span(ev ⇒ offsetFilter(endOffset)(ev.srcId))
        val nWorld = reduce(lEvents)(world)
        val nSkip = progress(skipReportUntil,nWorld.offset,endOffset)
        task match {
          case t: NextSnapshotTask ⇒
            if(nWorld.offset == endOffset) {
              val saved = save(nWorld)
              if(offsetOpt.nonEmpty) List(saved) else snapshotLister.list.map(_.raw)
            } else {
              assert(gEvents.isEmpty)
              iteration(nWorld, endOffset, nSkip)
            }
          case t: DebugSnapshotTask ⇒
            if(gEvents.nonEmpty){
              assert(endOffset == gEvents.head.srcId)
              List(save(nWorld), txSnapshotSaver.save(endOffset, gEvents.head.data.toByteArray, gEvents.head.headers))
            } else iteration(nWorld, endOffset, nSkip)
        }
      }
      iteration(initialRawWorld, offsetOpt.getOrElse(consumer.endOffset),0L)
    })
  }

  def maxTime: Long =
    snapshotLister.list.headOption.fold(0L)(s⇒rawSnapshotLoader.mTime(s.raw))
}

class SafeToRun(snapshotMaker: SnapshotMakerImpl) extends Executable {
  def run(): Unit = concurrent.blocking{
    Thread.sleep(10*minute)
    while(true){
      assert(now < snapshotMaker.maxTime + 3*hour)
      Thread.sleep(hour)
    }
  }
}

class SnapshotWorld(val state: Map[N_Update,N_Update],val offset: NextOffset)

trait SnapshotConfig {
  def ignore: Set[Long]
}

////

class FileSnapshotConfigImpl(dirStr: String)(
  val ignore: Set[Long] =
    Option(Paths.get(dirStr).resolve(".ignore")).filter(Files.exists(_)).toSet.flatMap{
      (path:Path) ⇒
        val content = new String(Files.readAllBytes(path), UTF_8)
        val R = """([0-9a-f]+)""".r
        R.findAllIn(content).map(java.lang.Long.parseLong(_, 16))
    }
) extends SnapshotConfig

class FileRawSnapshotLoader(baseDirStr: String, util: SnapshotUtil) extends RawSnapshotLoader with SnapshotLister {
  private def baseDir = Paths.get(baseDirStr)
  def load(snapshot: RawSnapshot): ByteString = {
    val path = baseDir.resolve(snapshot.relativePath)
    ToByteString(Files.readAllBytes(path))
  }
  def list(subDirStr: String): List[RawSnapshot] = {
    val subDir = baseDir.resolve(subDirStr)
    if(!Files.exists(subDir)) Nil
    else FinallyClose(Files.newDirectoryStream(subDir))(_.asScala.toList)
      .map(path⇒RawSnapshot(baseDir.relativize(path).toString))
  }
  def list: List[SnapshotInfo] = {
    val parseName = util.hashFromName
    val rawList = list("snapshots")
    val res = rawList.flatMap(parseName(_)).sortBy(_.offset).reverse
    res
  }
  def mTime(snapshot: RawSnapshot): Long =
    Files.getLastModifiedTime(baseDir.resolve(snapshot.relativePath)).toMillis
  //remove Files.delete(path)
}

class FileRawSnapshotSaver(baseDirStr: String /*db4*/) extends RawSnapshotSaver {
  def save(snapshot: RawSnapshot, data: Array[Byte]): Unit = {
    val path: Path = Paths.get(baseDirStr).resolve(snapshot.relativePath)
    Files.createDirectories(path.getParent)
    Files.write(path, data)
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
