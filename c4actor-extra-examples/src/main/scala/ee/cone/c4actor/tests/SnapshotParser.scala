package ee.cone.c4actor.tests

import java.nio.file.{Files, Paths}

import com.squareup.wire.ProtoAdapter
import ee.cone.c4actor._
import ee.cone.c4external.ExternalProtocol
import ee.cone.c4external.ExternalProtocol.ExternalUpdates
import ee.cone.c4proto.{HasId, Protocol, ToByteString}
import okio.ByteString

//C4STATE_TOPIC_PREFIX=ee.cone.c4actor.tests.SnapshotParserApp sbt ~'c4actor-extra-examples/runMain ee.cone.c4actor.ServerMain'

class SnapshotParser(execution: Execution, toUpdate: ToUpdate, snapshotLoader: SnapshotLoader, qAdapterRegistry: QAdapterRegistry) extends Executable {
  def run(): Unit = {
    val adapter = qAdapterRegistry.byName(classOf[ExternalUpdates].getName).asInstanceOf[ProtoAdapter[ExternalUpdates] with HasId]
    val sn = snapshotLoader.load(RawSnapshot("snapshots/0000000000000000-9f4d7985-dcee-331f-a010-fa9eb61063fa"))
    val updates = toUpdate.toUpdates(sn.toList)
    println(updates.filter(_.flags != 0L))
    println(updates.filter(_.valueTypeId == adapter.id).map(u ⇒ adapter.decode(u.value)).map(_.txId).map(txId ⇒ s"|$txId|"))
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
{
  val loader = new RawSnapshotLoader {
    def load(snapshot: RawSnapshot): ByteString = {
      val path = Paths.get(config.get("C4DATA_DIR")).resolve(snapshot.relativePath)
      ToByteString(Files.readAllBytes(path))
    }
  }

  override def toStart: List[Executable] = new SnapshotParser(execution, toUpdate, new SnapshotLoaderImpl(loader), qAdapterRegistry) :: super.toStart
  override def protocols: List[Protocol] = ExternalProtocol :: super.protocols
  def assembleProfiler: AssembleProfiler = NoAssembleProfiler
}
