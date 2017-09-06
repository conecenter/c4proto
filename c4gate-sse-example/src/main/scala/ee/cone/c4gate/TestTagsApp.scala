package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4vdom.{ChildPairFactory, TagJsonUtils, Tags}

trait TestTagsApp extends ToInjectApp {
  def childPairFactory: ChildPairFactory
  def tagJsonUtils: TagJsonUtils
  def tags: Tags

  private lazy val testTags =
    new TestTags[Context](childPairFactory, tagJsonUtils, tags)
  override def toInject: List[ToInject] = new TestTagsInit(testTags) :: super.toInject
}

class TestTagsInit(testTags: TestTags[Context]) extends ToInject {
  def toInject: List[Injectable] = TestTagsKey.set(testTags)
}

case object TestTagsKey extends SharedComponentKey[TestTags[Context]]