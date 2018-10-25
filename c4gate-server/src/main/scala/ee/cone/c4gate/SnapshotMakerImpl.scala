package ee.cone.c4gate

import java.io.BufferedInputStream
import java.net.{HttpURLConnection, URL}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}

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
  ): Values[(SrcId,TxTransform)] =
    List(WithPK(SnapshotMakingTx("snapshotMaker", posts.sortBy(_.srcId).headOption)(snapshotMaking)))
}


object Time {
  def hour: Long = 60L*minute
  def minute: Long = 60L*1000L
  def now: Long = System.currentTimeMillis
}
import Time._

case object DeferPeriodicSnapshotUntilKey extends TransientLens[Long](0L)
case class SnapshotMakingTx(srcId: SrcId, postOpt: Option[HttpPost])(snapshotMaking: SnapshotMakerImpl) extends TxTransform {
  private def header(post: HttpPost, key: String): Option[String] =
    post.headers.find(_.key == key).map(_.value)
  def transform(local: Context): Context = {
    if(DeferPeriodicSnapshotUntilKey.of(local) < now){
      if(snapshotMaking.maxTime + hour < now) snapshotMaking.make(NextSnapshotTask(None))()
      DeferPeriodicSnapshotUntilKey.set(now+minute)(local)
    } else if(postOpt.nonEmpty) {
      val Some(post) = postOpt
      val responseKey = header(post,"X-r-response-key")
      val response = responseKey.toList.map{ k ⇒
        val res = ErrorKey.of(local) match {
          case errors if errors.nonEmpty ⇒
            errors.map(e⇒Header("X-r-error-message",e.getMessage))
          case _ ⇒
            val modeStr = header(post,"X-r-snapshot-mode").get
            val offsetOpt = header(post,"X-r-offset")
            val task = snapshotMaking.task(modeStr,offsetOpt)
            val res = snapshotMaking.make(task)()
            List(Header("X-r-snapshot-keys",res.map(_.key).mkString(",")))
        }
        HttpPublication(s"/response/$k",res,ByteString.EMPTY,Option(now+hour))
      }
      TxAdd(response.flatMap(LEvent.update) ++ LEvent.delete[Product](post))(local)
    } else local
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

  def task(name: String, offsetOpt: Option[NextOffset]): SnapshotTask = name match {
    case "next" ⇒ NextSnapshotTask(offsetOpt)
    case "debug" ⇒ DebugSnapshotTask(offsetOpt.get)
  }

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

  def make(task: SnapshotTask): ()⇒List[RawSnapshot] = ()⇒concurrent.blocking {
    val offsetOpt = task.offsetOpt
    val offsetFilter: NextOffset⇒NextOffset⇒Boolean = task match {
      case t: NextSnapshotTask ⇒ end⇒curr⇒curr<=end
      case t: DebugSnapshotTask ⇒ end⇒curr⇒curr<end
    }
    val initialRawWorld = load(offsetOpt.map(offsetFilter))
    consuming.process(initialRawWorld.offset, consumer ⇒ {
      @tailrec def iteration(world: SnapshotWorld, endOffset: NextOffset): List[RawSnapshot] = {
        val events = consumer.poll()
        val (lEvents, gEvents) = events.span(ev ⇒ offsetFilter(endOffset)(ev.srcId))
        val nWorld = reduce(lEvents)(world)
        if(gEvents.isEmpty) iteration(nWorld, endOffset) else {
          val endEvent = task match {
            case t: NextSnapshotTask ⇒ lEvents.last
            case t: DebugSnapshotTask ⇒ gEvents.head
          }
          assert(endOffset == endEvent.srcId)
          task match {
            case t: NextSnapshotTask ⇒
              List(save(nWorld))
            case t: DebugSnapshotTask ⇒
              List(save(nWorld), txSnapshotSaver.save(endEvent.srcId, endEvent.data.toByteArray))
          }
        }
      }
      iteration(initialRawWorld, offsetOpt.getOrElse(consumer.endOffset))
    })
  }

  def maxTime: Long =
    snapshotLoader.list.headOption.fold(0L)(s⇒rawSnapshotLoader.mTime(s.raw))
}

class SafeToRun(snapshotMaker: SnapshotMakerImpl) extends Executable {
  def run(): Unit = concurrent.blocking{
    Thread.sleep(5*minute)
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
