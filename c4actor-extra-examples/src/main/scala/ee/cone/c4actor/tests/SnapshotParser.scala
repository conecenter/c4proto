package ee.cone.c4actor.tests

import java.nio.file.{Files, Paths}

import com.squareup.wire.ProtoAdapter
import ee.cone.c4actor._
import ee.cone.c4external.ExternalProtocol
import ee.cone.c4external.ExternalProtocol.S_ExternalUpdate
import ee.cone.c4proto.{HasId, Protocol, ToByteString}
import okio.ByteString

//C4STATE_TOPIC_PREFIX=ee.cone.c4actor.tests.SnapshotParserApp sbt ~'c4actor-extra-examples/runMain ee.cone.c4actor.ServerMain'

class SnapshotParser(execution: Execution, toUpdate: ToUpdate, snapshotLoader: SnapshotLoader, qAdapterRegistry: QAdapterRegistry) extends Executable {
  def run(): Unit = {
    val adapter = qAdapterRegistry.byName(classOf[S_ExternalUpdate].getName).asInstanceOf[ProtoAdapter[S_ExternalUpdate] with HasId]
    val sn = snapshotLoader.load(RawSnapshot("snapshots/0000000000005868-3c8ce3d3-d3b3-3027-9622-e210238e0276-c-lz4"))
    val updates = toUpdate.toUpdates(sn.toList)
    println(updates.filter(_.flags != 0L).mkString("\n"))
    execution.complete()
  }
}

class SnapshotParserApp
  extends ToStartApp
    with VMExecutionApp
    with ProtocolsApp
    with ExecutableApp
    with RichDataApp
    with LZ4DeCompressorApp {
  val loader = new RawSnapshotLoader {
    def load(snapshot: RawSnapshot): ByteString = {
      val path = Paths.get("/c4/c4proto/db4").resolve(snapshot.relativePath)
      ToByteString(Files.readAllBytes(path))
    }
  }

  override def toStart: List[Executable] = new SnapshotParser(execution, toUpdate, new SnapshotLoaderImpl(loader), qAdapterRegistry) :: super.toStart
  override def protocols: List[Protocol] = ExternalProtocol :: super.protocols
  def assembleProfiler: AssembleProfiler = NoAssembleProfiler
  def actorName: String = "SnapshotParser"
}
