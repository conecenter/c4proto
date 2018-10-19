package ee.cone.c4gate

import java.io.BufferedInputStream
import java.net.{HttpURLConnection, URL, URLConnection}
import java.nio.file.{Files, Paths}
import java.time.Instant
import java.time.temporal.TemporalAmount

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.{DebugTx, Update, Updates}
import ee.cone.c4actor.Types.NextOffset
import ee.cone.c4actor._
import ee.cone.c4assemble.Single
import ee.cone.c4proto.ToByteString
import okio.ByteString

import scala.annotation.tailrec

class FileRawSnapshotSaver(dirStr: String) extends RawSnapshotSaver {
  def save(filename: String, data: Array[Byte]): Unit =
    Files.write(Files.createDirectories(Paths.get(dirStr)).resolve(filename),data)
}

class SnapshotMakingRawWorldFactory(
  config: SnapshotConfig, toUpdate: ToUpdate, rawSnapshot: SnapshotSaver
) extends RawWorldFactory {
  def create(): RawWorld = {
    val srcId = "0" * OffsetHexSize()
    new SnapshotMakingRawWorld(rawSnapshot, toUpdate, config.ignore, Map.empty, srcId)
  }
}

class SnapshotMakingRawWorld(
  rawSnapshot: SnapshotSaver,
  toUpdate: ToUpdate,
  ignore: Set[Long],
  state: Map[Update,Update],
  val offset: NextOffset
) extends RawWorld with LazyLogging {
  def reduce(events: List[RawEvent]): RawWorld = if(events.isEmpty) this else {
    val updates = events.map(_.data).flatMap(toUpdate.toUpdates)
    val newState = (state /: updates){(state,up)⇒
      if(ignore(up.valueTypeId)) state
      else if(up.value.size > 0) state + (updKey(up)→up)
      else state - up
    }
    new SnapshotMakingRawWorld(rawSnapshot,toUpdate,ignore,newState,events.last.srcId)
  }
  def hasErrors: Boolean = false

  private def updKey(up: Update): Update = up.copy(value=ByteString.EMPTY)
  private def updBy(up: Update) = (up.valueTypeId,up.srcId)

  @tailrec private def makeStatLine(
    currType: Long, currCount: Long, currSize: Long, updates: List[Update]
  ): List[Update] =
    if(updates.isEmpty || currType != updates.head.valueTypeId) {
      logger.info(s"t:${java.lang.Long.toHexString(currType)} c:$currCount s:$currSize")
      updates
    } else makeStatLine(currType,currCount+1,currSize+updates.head.value.size(),updates.tail)
  @tailrec private def makeStats(updates: List[Update]): Unit =
    if(updates.nonEmpty) makeStats(makeStatLine(updates.head.valueTypeId,0,0,updates))

  def save(): Unit = {
    logger.info("Saving...")
    val updates = state.values.toList.sortBy(updBy)
    makeStats(updates)
    rawSnapshot.save(offset, toUpdate.toBytes(updates))
    logger.info("OK")
  }

  def diff(target: ByteString): Array[Byte] = {
    val targetUpdates = toUpdate.toUpdates(target)
    val updates = targetUpdates.filterNot{ up ⇒ state.get(updKey(up)).contains(up) }
    val deletes = (state.keySet -- targetUpdates.map(updKey)).toList.sortBy(updBy)
    toUpdate.toBytes(deletes ::: updates)
  }
}

/*
class OnceSnapshotMakingRawObserver(completing: RawObserver) extends RawObserver {
  def activate(rawWorld: RawWorld): RawObserver = {
    rawWorld.save()
    completing.activate(rawWorld)
  }
}
*/

////////

class DoubleRawObserver(a: RawObserver, b: RawObserver) extends RawObserver {
  def activate(rawWorld: RawWorld): RawObserver = {
    val nA = a.activate(rawWorld)
    val nB = b.activate(rawWorld)
    if((a eq nA) && (b eq nB)) this else new DoubleRawObserver(nA,nB)
  }
}

class PeriodicRawObserver(
  period: TemporalAmount, inner: RawObserver, until: Instant=Instant.MIN
) extends RawObserver {
  def activate(rawWorld: RawWorld): RawObserver =
    if(Instant.now.isBefore(until)) this else {
      val nInner = inner.activate(rawWorld)
      new PeriodicRawObserver(period, nInner, Instant.now.plus(period))
    }
}

class SnapshotMakingRawObserver() extends RawObserver {
  def activate(rawWorld: RawWorld): RawObserver = {
    rawWorld.asInstanceOf[SnapshotMakingRawWorld].save()
    this
  }
}

class SnapshotMergingRawObserver(
  rawQSender: RawQSender, loader: RawSnapshotLoader
) extends RawObserver with LazyLogging {
  def activate(rawWorld: RawWorld): RawObserver = loader.list match {
    case Seq() ⇒ this
    case Seq(targetSnapshot: RawSnapshot with Removable) ⇒
      val bytes = rawWorld.asInstanceOf[SnapshotMakingRawWorld].diff(targetSnapshot.load())
      rawQSender.send(List(new QRecordImpl(InboxTopicName(),bytes)))
      targetSnapshot.remove()
      this
    case t ⇒
      logger.error(s"conflicting targets: $t")
      this
  }
}

////////

object HttpUtil {
  def get(url: String): ByteString = {
    FinallyClose[HttpURLConnection,ByteString](_.disconnect())(
      new URL(url).openConnection().asInstanceOf[HttpURLConnection]
    ){ conn ⇒
      val res = FinallyClose(new BufferedInputStream(conn.getInputStream)){ is ⇒
        FinallyClose(new okio.Buffer){ buffer ⇒
          buffer.readFrom(is)
          buffer.readByteString()
        }
      }
      assert(conn.getResponseCode == 200)
      res
    }
  }
}

class RemoteRawSnapshotLoader(url: String) extends RawSnapshotLoader {
  def list: List[RawSnapshot] =
    """([0-9a-f\-]+)""".r.findAllIn(HttpUtil.get(s"$url/").utf8())
      .toList.distinct.map(new RemoteRawSnapshot(url,_))
}

class RemoteRawSnapshot(url: String, val name: String) extends RawSnapshot with LazyLogging {
  def load(): ByteString = {
    val tm = NanoTimer()
    val res = HttpUtil.get(s"$url/$name")
    logger.info(s"downloaded ${res.size} in ${tm.ms} ms")
    res
  }
}

object NoSnapshotConfig extends SnapshotConfig {
  def ignore: Set[Long] = Set.empty
}

class DebugSavingRawWorldFactory(
  val debugOffset: String, val toUpdate: ToUpdate, val execution: Execution,
  inner: RawWorldFactory
) extends RawWorldFactory {
  def create(): RawWorld = new DebugSavingRawWorld(inner.create(), this)
}
class DebugSavingRawWorld(
  inner: RawWorld, parent: DebugSavingRawWorldFactory
) extends RawWorld {
  import parent._
  def offset: NextOffset = inner.offset
  def reduce(events: List[RawEvent]): RawWorld = {
    val (ltEvents,geEvents) = events.span(ev ⇒ ev.srcId < debugOffset)
    val ltInner = inner.reduce(ltEvents)
    if(geEvents.isEmpty) new DebugSavingRawWorld(ltInner,parent)
    else {
      val event = geEvents.head
      assert(event.srcId == debugOffset)
      val nInner = ltInner.reduce(List(RawEvent(ltInner.offset,ToByteString(
        toUpdate.toBytes(
          LEvent.update(
            DebugTx(event.srcId, toUpdate.toUpdates(event.data))
          ).map(toUpdate.toUpdate).toList
        )
      ))))
      nInner.asInstanceOf[SnapshotMakingRawWorld].save()
      execution.complete()
      new DebugSavingRawWorld(nInner,parent)
    }
  }
  def hasErrors: Boolean = inner.hasErrors
}

////

class SafeToRun(snapshotLoader: SnapshotLoader) extends Executable {
  def run() = concurrent.blocking{
    val now = System.currentTimeMillis
    val hour = 3600*1000
    while(true){
      assert(snapshotLoader.list.map(_.raw).exists{ case s: SnapshotTime ⇒ now < s.mTime + 3*hour })
      Thread.sleep(hour)
    }
  }
}