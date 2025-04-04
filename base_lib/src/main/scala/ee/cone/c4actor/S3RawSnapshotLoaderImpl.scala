package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4di.{c4, c4multi, provide}
import ee.cone.c4proto.ToByteString
import okio.ByteString

@c4multi("S3RawSnapshotLoaderApp") final class S3RawSnapshotLoaderImpl()(
  s3: S3Manager, util: SnapshotUtil, execution: Execution,
  currentTxLogName: CurrentTxLogName, s3Lister: S3Lister,
) extends RawSnapshotLoader with SnapshotLister with LazyLogging {
  def getSync(resource: String): Option[Array[Byte]] =
    execution.aWait{ implicit ec =>
      s3.get(s3.join(currentTxLogName, resource))
    }
  def load(snapshot: RawSnapshot): ByteString = getSync(snapshot.relativePath).fold{
    Thread.sleep(1000)
    load(snapshot)
  }(ToByteString(_))
  private def infix = "snapshots"
  def listInner(): List[(RawSnapshot,String)] = for {
    (name,timeStr) <- execution.aWait{ implicit ec => s3Lister.list(currentTxLogName, infix) }.toList.flatten
  } yield (RawSnapshot(s"$infix/${name}"), timeStr)
  def list: List[SnapshotInfo] = (for{
    (rawSnapshot,_) <- listInner()
    _ = logger.debug(s"$rawSnapshot")
    snapshotInfo <- util.hashFromName(rawSnapshot)
  } yield snapshotInfo).sortBy(_.offset).reverse
  def listWithMTime: List[TimedSnapshotInfo] = (
    for{
      (rawSnapshot,mTimeStr) <- listInner()
      snapshotInfo <- util.hashFromName(rawSnapshot)
    } yield TimedSnapshotInfo(snapshotInfo,s3Lister.parseTime(mTimeStr))
    ).sortBy(_.snapshot.offset).reverse
}

@c4("S3RawSnapshotLoaderApp") final class EnvRemoteRawSnapshotProvider(
  disable: Option[DisableDefaultS3RawSnapshot], loaderFactory: S3RawSnapshotLoaderImplFactory,
)(
  loaders: Seq[S3RawSnapshotLoaderImpl] = Seq(loaderFactory.create())
){
  @provide def getLoader: Seq[RawSnapshotLoader] = if(disable.nonEmpty) Nil else loaders
  @provide def getLister: Seq[SnapshotLister] = loaders
  @provide def getLast: Seq[SnapshotLast] = if(disable.nonEmpty) Nil else loaders.map(new SnapshotLastImpl(_))
}

@c4("DisableDefaultS3RawSnapshotApp") final class DisableDefaultS3RawSnapshot

class SnapshotLastImpl(lister: SnapshotLister) extends SnapshotLast {
  def get: Option[RawSnapshot] = lister.list.headOption.map(_.raw)
}