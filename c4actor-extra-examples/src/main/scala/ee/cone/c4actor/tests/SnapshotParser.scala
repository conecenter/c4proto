package ee.cone.c4actor.tests

import java.nio.file.{Files, Paths}

import com.squareup.wire.ProtoAdapter
import ee.cone.c4actor._
import ee.cone.c4proto.{HasId, Protocol, ToByteString}
import okio.ByteString

//C4STATE_TOPIC_PREFIX=ee.cone.c4actor.tests.SnapshotParserApp sbt ~'c4actor-extra-examples/runMain ee.cone.c4actor.ServerMain'

class SnapshotParser(execution: Execution, toUpdate: ToUpdate, snapshotLoader: SnapshotLoader, qAdapterRegistry: QAdapterRegistry) extends Executable {
  def run(): Unit = {
    println(new java.io.File(".").getCanonicalPath)
    val hashFromData = SnapshotUtilImpl.hashFromData(Files.readAllBytes(Paths.get("/c4db/home/c4proto/c4actor-extra-examples/0000000000000000-92b87c05-294d-3c1d-b443-fb83bdc71d20-c-lz4")))
    println(hashFromData)
    val fromName = SnapshotUtilImpl.hashFromName(RawSnapshot("0000000000000000-92b87c05-294d-3c1d-b443-fb83bdc71d20-c-lz4")).get.uuid
    println(hashFromData, fromName)
    val sn = snapshotLoader.load(RawSnapshot("0000000000000000-92b87c05-294d-3c1d-b443-fb83bdc71d20-c-lz4"))
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
    with EnvConfigApp
    with LZ4DeCompressorApp {
  val loader = new RawSnapshotLoader {
    def load(snapshot: RawSnapshot): ByteString = {
      val path = Paths.get(config.get("C4DATA_DIR")).resolve(snapshot.relativePath)
      ToByteString(Files.readAllBytes(path))
    }
  }

  override def toStart: List[Executable] = new SnapshotParser(execution, toUpdate, new SnapshotLoaderImpl(loader), qAdapterRegistry) :: super.toStart
  def assembleProfiler: AssembleProfiler = NoAssembleProfiler
}
