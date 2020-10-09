package ee.cone.c4actor

import ee.cone.c4actor.Types.NextOffset
import ee.cone.c4di.c4

@c4("SnapshotListProtocolApp") final class LastSnapshotGetterImpl(consuming: Consuming) extends LastSnapshotGetter {
  def get(): NextOffset = consuming.process("0" * OffsetHexSize(), _.beginningOffset)
}