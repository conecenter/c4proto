package ee.cone.c4actor

import java.util.UUID
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4di.{c4, c4multi, provide}
import ee.cone.c4actor.Types._
import ee.cone.c4assemble.C4UUID

object SnapshotUtilImpl extends SnapshotUtil {
  def hashFromName: RawSnapshot=>Option[SnapshotInfo] = {
    val R = """(snapshot[a-z_]+)/([0-9a-f]{16})-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})([-0-9a-z]+)?""".r;
    {
      case raw@RawSnapshot(R(subDirStr,offsetHex,uuid,flags)) =>
        Option(flags) match {
          case None => Option(SnapshotInfo(subDirStr,offsetHex,uuid,Nil,raw))
          case Some(kvs) =>
            val postParse = kvs.split("-").toList.tail
            if (postParse.size % 2 == 0) {
              val headers = postParse.grouped(2).toList.collect { case key :: value :: Nil => RawHeader(key, value) }
              Option(SnapshotInfo(subDirStr, offsetHex, uuid, headers, raw))
            } else {
              None
            }
        }
      case a =>
        //logger.warn(s"not a snapshot: $a")
        None
    }
  }
  def hashFromData: Array[Byte]=>String = C4UUID.nameUUIDFromBytes(_).toString
}

@c4("SnapshotUtilImplApp") final class SnapshotUtilProvider {
  @provide def provide: Seq[SnapshotUtil] = List(SnapshotUtilImpl)
}


import SnapshotUtilImpl._

//case class Snapshot(offset: NextOffset, uuid: String, raw: RawSnapshot)
@c4multi("SnapshotUtilImplApp") final class SnapshotSaverImpl(subDirStr: String)(inner: RawSnapshotSaver) extends SnapshotSaver {
  def save(offset: NextOffset, data: Array[Byte], headers: List[RawHeader]): RawSnapshot = {
    val snapshot = RawSnapshot(s"$subDirStr/$offset-${hashFromData(data)}${headers.map(h => s"-${h.key}-${h.value}").mkString}")
    assert(hashFromName(snapshot).nonEmpty, s"Not a valid name ${snapshot.relativePath}")
    inner.save(snapshot, data)
    snapshot
  }
}

@c4("SnapshotUtilImplApp") final class SnapshotSaverFactoryImpl(inner: SnapshotSaverImplFactory) extends SnapshotSaverFactory {
  def create(subDirStr: String): SnapshotSaver = inner.create(subDirStr)
}

@c4("SnapshotLoaderImplApp") final class SnapshotLoaderImpl(raw: RawSnapshotLoader) extends SnapshotLoader with LazyLogging {
  def load(snapshot: RawSnapshot): Option[RawEvent] = {
    logger.debug(s"Loading raw snapshot [${snapshot.relativePath}]")
    val res = for {
      snapshotInfo <- hashFromName(snapshot) //goes first, secures fs
      data <- Option(raw.load(snapshot)) if hashFromData(data.toByteArray) == snapshotInfo.uuid
    } yield SimpleRawEvent(snapshotInfo.offset, data, snapshotInfo.headers)
    logger.debug(s"Loaded raw snapshot ${res.nonEmpty}")
    res
  }
}

@c4("SnapshotLoaderFactoryImplApp") final class SnapshotLoaderFactoryImpl extends SnapshotLoaderFactory {
  def create(raw: RawSnapshotLoader): SnapshotLoader =
    new SnapshotLoaderImpl(raw)
}