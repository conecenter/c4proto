package ee.cone.c4gate_devel

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.{Consuming, Executable, ListConfig, NextSnapshotTask, SnapshotMaker, SnapshotUtil, ToUpdate, UpdateCompressionMinSize}
import ee.cone.c4di.{c4, provide}

import java.nio.file.{Files, Path, Paths}
import scala.annotation.tailrec
import scala.jdk.CollectionConverters.ListHasAsScala

@c4("OrigStatReplayApp") final class OrigStatReplay(
  toUpdate: ToUpdate, consuming: Consuming, snapshotMaker: SnapshotMaker, snapshotUtil: SnapshotUtil
) extends Executable with LazyLogging {
  def run(): Unit = {
    val Seq(snapshotInfo) = snapshotMaker.make(NextSnapshotTask(None)).flatMap(snapshotUtil.hashFromName)
    consuming.process(snapshotInfo.offset, consumer => { // excluding C4REPLAY_SNAPSHOT and C4REPLAY_UNTIL
      val endOffset = consumer.endOffset
      @tailrec def iteration(): Unit = {
        val Seq(ev) = consumer.poll()
        toUpdate.toUpdates(List(ev),"stat").foldLeft(Map.empty[Long,(Long,Long)].withDefault(_=>(0L,0L))){ (r,el) =>
          val k = el.valueTypeId
          val (wasCount, wasSize) =  r(k)
          r.updated(k, (wasCount+1L, wasSize+el.value.size))
        }.toSeq.sorted.foreach{ case (valueTypeId, (count, size)) =>
          logger.info(s"t:${java.lang.Long.toHexString(valueTypeId)} c:$count s:$size")
        }
        if(ev.srcId<endOffset) iteration()
      }
      iteration()
    })
  }
}

@c4("OrigStatReplayApp") final class TheUpdateCompressionMinSize extends UpdateCompressionMinSize(0L)

object FileConfigFactory {
  def create(paths: List[Path]): Map[String, List[String]] = (for {
    path <- paths if Files.exists(path)
    l <- Files.readAllLines(path).asScala
    p <- l.indexOf("=") match { case -1 => Nil case pos => Seq(l.substring(0,pos) -> l.substring(pos+1)) }
  } yield p).groupMap(_._1)(_._2)
}

@c4("DevConfigApp") final class DevConfig(inner: ListConfig)(
  fileEnvMap: Map[String, List[String]] = FileConfigFactory.create(inner.get("C4DEV_ENV").map(Paths.get(_)))
) extends ListConfig {
  def get(key: String): List[String] = fileEnvMap.getOrElse(key,Nil) ::: inner.get(key)
}