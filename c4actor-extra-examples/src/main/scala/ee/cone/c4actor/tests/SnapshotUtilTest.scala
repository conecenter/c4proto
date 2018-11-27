package ee.cone.c4actor.tests

import ee.cone.c4actor.{RawSnapshot, SnapshotUtil}

object SnapshotUtilTest {
  def main(args: Array[String]): Unit = {
    val sn = "snapshots/0000000000009b70-ff5a4654-8a0f-377e-9616-d3d64cfa0131-gzip-a-u-u"
    println(SnapshotUtil.hashFromName(RawSnapshot(sn)))
  }
}
