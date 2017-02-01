package ee.cone.c4gate

import ee.cone.c4actor.{InitLocal, InitLocalsApp}
import ee.cone.c4actor.LEvent._
import ee.cone.c4assemble.Types.World
import ee.cone.c4assemble.WorldKey
import ee.cone.c4vdom.{ChildPairFactory, TagJsonUtils}

trait TestTagsApp extends InitLocalsApp {
  def childPairFactory: ChildPairFactory
  def tagJsonUtils: TagJsonUtils

  private lazy val testTags =
    new TestTags[World](childPairFactory, tagJsonUtils, (m:Product)⇒add(update(m)))
  override def initLocals: List[InitLocal] = new TestTagsInit(testTags) :: super.initLocals
}

class TestTagsInit(testTags: TestTags[World]) extends InitLocal {
  def initLocal: World ⇒ World = TestTagsKey.set(Option(testTags))
}

case object TestTagsKey extends WorldKey[Option[TestTags[World]]](None)