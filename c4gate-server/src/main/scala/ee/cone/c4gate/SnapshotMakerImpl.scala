package ee.cone.c4gate

import java.io.BufferedInputStream
import java.net.{HttpURLConnection, URL}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}

import com.sun.net.httpserver.HttpExchange
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.{Firstborn, Update}
import ee.cone.c4actor.Types.{NextOffset, SrcId}
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import ee.cone.c4gate.HttpProtocol.{Header, HttpPost, HttpPublication}
import ee.cone.c4proto.{Protocol, ToByteString, protocol}
import okio.ByteString

import scala.annotation.tailrec
import scala.collection.JavaConverters.iterableAsScalaIterableConverter

class HttpGetSnapshotHandler(snapshotLoader: SnapshotLoader, authKey: AuthKey) extends RHttpHandler {
  def handle(httpExchange: HttpExchange): Boolean =
    if(httpExchange.getRequestMethod == "GET"){
      val path = httpExchange.getRequestURI.getPath
      if(path.startsWith("/snapshot")){
        assert(authKey.value == Option(httpExchange.getRequestHeaders.getFirst("X-r-auth-key")).get)
        val bytes =
          if(path == "/snapshots/")
            snapshotLoader.list.map(_.raw.key).mkString("\n").getBytes(UTF_8)
          else snapshotLoader.load(RawSnapshot(path.tail)).get.data.toByteArray
        httpExchange.sendResponseHeaders(200, bytes.length)
        if(bytes.nonEmpty) httpExchange.getResponseBody.write(bytes)
        true
      } else false
    } else false
}

@assemble class SnapshotMakingAssemble(actorName: String, snapshotMaking: SnapshotMakerImpl) extends Assemble {
  type NeedSnapshot = SrcId

  def needConsumer(
    key: SrcId,
    first: Each[Firstborn]
  ): Values[(SrcId,LocalPostConsumer)] =
    List(WithPK(LocalPostConsumer(snapshotMaking.url)))

  def mapAll(
    key: SrcId,
    post: Each[HttpPost]
  ): Values[(NeedSnapshot,HttpPost)] =
    if(post.path == snapshotMaking.url) List(actorName→post) else Nil

  def mapFirstborn(
    key: SrcId,
    firstborn: Each[Firstborn],
    @by[NeedSnapshot] posts: Values[HttpPost]
  ): Values[(SrcId,TxTransform)] = {
    val srcId = "snapshotMaker"
    if(posts.isEmpty) List(WithPK(PeriodicSnapshotMakingTx(srcId)(snapshotMaking)))
    else {
      val taskOpt = SnapshotMakingUtil.task(posts.minBy(_.srcId))
      val similarPosts = posts.toList.filter(post⇒SnapshotMakingUtil.task(post)==taskOpt).sortBy(_.srcId)
      List(WithPK(RequestedSnapshotMakingTx(srcId, taskOpt, similarPosts)(snapshotMaking)))
    }
  }
}

object SnapshotMakingUtil {
  def header(post: HttpPost, key: String): Option[String] =
    post.headers.find(_.key == key).map(_.value)
  private def task(name: Option[String], offsetOpt: Option[NextOffset]): Option[SnapshotTask] = name match {
    case Some("next") ⇒ Option(NextSnapshotTask(offsetOpt))
    case Some("debug") ⇒ Option(DebugSnapshotTask(offsetOpt.get))
    case _ ⇒ None
  }
  def task(post: HttpPost): Option[SnapshotTask] =
    task(header(post,"X-r-snapshot-mode"), header(post,"X-r-offset"))
}

object Time {
  def hour: Long = 60L*minute
  def minute: Long = 60L*1000L
  def now: Long = System.currentTimeMillis
}
import Time._

case object DeferPeriodicSnapshotUntilKey extends TransientLens[Long](0L)

case class PeriodicSnapshotMakingTx(srcId: SrcId)(snapshotMaking: SnapshotMakerImpl) extends TxTransform {
  def transform(local: Context): Context = if(DeferPeriodicSnapshotUntilKey.of(local) < now){
    if(snapshotMaking.maxTime + hour < now) snapshotMaking.make(NextSnapshotTask(None))()
    DeferPeriodicSnapshotUntilKey.set(now+minute)(local)
  } else local
}

case class RequestedSnapshotMakingTx(
  srcId: SrcId, taskOpt: Option[SnapshotTask], posts: List[HttpPost]
)(snapshotMaking: SnapshotMakerImpl) extends TxTransform {
  def transform(local: Context): Context = {
    val res = (ErrorKey.of(local), taskOpt) match {
      case (Seq(), Some(task)) ⇒
        val res = snapshotMaking.make(task)()
        List(Header("X-r-snapshot-keys", res.map(_.key).mkString(",")))
      case (errors, _) if errors.nonEmpty ⇒
        errors.map(e ⇒ Header("X-r-error-message", e.getMessage))
    }
    val updates = for {
      post ← posts
      key ← SnapshotMakingUtil.header(post,"X-r-response-key").toList
      update ← LEvent.update(HttpPublication(s"/response/$key", res, ByteString.EMPTY, Option(now + hour)))
    } yield update
    Function.chain(Seq(
      TxAdd(updates ++ posts.flatMap(LEvent.delete)),
      ErrorKey.set(Nil)
    ))(local)
  }
}

//todo new
class SnapshotMakerImpl(
  snapshotConfig: SnapshotConfig,
  snapshotLoader: SnapshotLoader,
  rawSnapshotLoader: FileRawSnapshotLoader,
  fullSnapshotSaver: SnapshotSaver,
  txSnapshotSaver: SnapshotSaver,
  consuming: Consuming,
  toUpdate: ToUpdate
) extends SnapshotMaker with LazyLogging {
  def url = "/need-snapshot"

  private def reduce(events: List[RawEvent]): SnapshotWorld⇒SnapshotWorld = if(events.isEmpty) w⇒w else world⇒{
    val updates = toUpdate.toUpdates(events)
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
      snapshot ← snapshotLoader.list.toStream if snapshotFilter.forall(_(snapshot.offset))
      event ← snapshotLoader.load(snapshot.raw)
    } yield {
      reduce(List(event))(emptyRawWorld)
    }).headOption.getOrElse(emptyRawWorld)
  }

  @tailrec private def makeStatLine(
    currType: Long, currCount: Long, currSize: Long, updates: List[Update]
  ): List[Update] =
    if(updates.isEmpty || currType != updates.head.valueTypeId) {
      logger.info(s"t:${java.lang.Long.toHexString(currType)} c:$currCount s:$currSize")
      updates
    } else makeStatLine(currType,currCount+1,currSize+updates.head.value.size(),updates.tail)
  @tailrec private def makeStats(updates: List[Update]): Unit =
    if(updates.nonEmpty) makeStats(makeStatLine(updates.head.valueTypeId,0,0,updates))

  private def save(world: SnapshotWorld): RawSnapshot = {
    logger.info("Saving...")
    val updates = world.state.values.toList.sortBy(toUpdate.by)
    makeStats(updates)
    val res = fullSnapshotSaver.save(world.offset, toUpdate.toBytes(updates))
    logger.info("OK")
    res
  }
  private def progress(skipReportUntil: Long, offset: NextOffset, endOffset: NextOffset): Long =
    if(now<skipReportUntil) skipReportUntil else {
      logger.info(s"$offset/$endOffset")
      now + 2000
    }
  def make(task: SnapshotTask): ()⇒List[RawSnapshot] = ()⇒concurrent.blocking {
    val offsetOpt = task.offsetOpt
    val offsetFilter: NextOffset⇒NextOffset⇒Boolean = task match {
      case t: NextSnapshotTask ⇒ end⇒curr⇒curr<=end
      case t: DebugSnapshotTask ⇒ end⇒curr⇒curr<end
    }
    val initialRawWorld = load(offsetOpt.map(offsetFilter))
    consuming.process(initialRawWorld.offset, consumer ⇒ {
      @tailrec def iteration(world: SnapshotWorld, endOffset: NextOffset, skipReportUntil: Long): List[RawSnapshot] = {
        val events = consumer.poll()
        val (lEvents, gEvents) = events.span(ev ⇒ offsetFilter(endOffset)(ev.srcId))
        val nWorld = reduce(lEvents)(world)
        val nSkip = progress(skipReportUntil,nWorld.offset,endOffset)
        task match {
          case t: NextSnapshotTask ⇒
            if(nWorld.offset == endOffset) List(save(nWorld)) else {
              assert(gEvents.isEmpty)
              iteration(nWorld, endOffset, nSkip)
            }
          case t: DebugSnapshotTask ⇒
            if(gEvents.nonEmpty){
              assert(endOffset == gEvents.head.srcId)
              List(save(nWorld), txSnapshotSaver.save(endOffset, gEvents.head.data.toByteArray))
            } else iteration(nWorld, endOffset, nSkip)
        }
      }
      iteration(initialRawWorld, offsetOpt.getOrElse(consumer.endOffset),0L)
    })
  }

  def maxTime: Long =
    snapshotLoader.list.headOption.fold(0L)(s⇒rawSnapshotLoader.mTime(s.raw))
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

class SnapshotWorld(val state: Map[Update,Update],val offset: NextOffset)

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

class FileRawSnapshotLoader(baseDirStr: String) extends RawSnapshotLoader {
  private def baseDir = Paths.get(baseDirStr)
  def load(snapshot: RawSnapshot): ByteString =
    ToByteString(Files.readAllBytes(baseDir.resolve(snapshot.key)))
  def list(subDirStr: String): List[RawSnapshot] = {
    val subDir = baseDir.resolve(subDirStr)
    if(!Files.exists(subDir)) Nil
    else FinallyClose(Files.newDirectoryStream(subDir))(_.asScala.toList)
      .map(path⇒RawSnapshot(baseDir.relativize(path).toString))
  }
  def mTime(snapshot: RawSnapshot): Long =
    Files.getLastModifiedTime(baseDir.resolve(snapshot.key)).toMillis
  //remove Files.delete(path)
}

class FileRawSnapshotSaver(baseDirStr: String/*db4*/) extends RawSnapshotSaver {
  def save(snapshot: RawSnapshot, data: Array[Byte]): Unit = {
    val path = Paths.get(baseDirStr).resolve(snapshot.key)
    Files.createDirectories(path.getParent)
    Files.write(path,data)
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
