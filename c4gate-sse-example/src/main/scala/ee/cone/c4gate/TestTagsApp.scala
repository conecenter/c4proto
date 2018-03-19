package ee.cone.c4gate

import ee.cone.c4actor._
import ee.cone.c4vdom.{ChildPairFactory, TagJsonUtils, Tags}

trait TestTagsApp {
  def childPairFactory: ChildPairFactory
  def tagJsonUtils: TagJsonUtils
  def tags: Tags

  lazy val testTags: TestTags[Context] =
    new TestTags[Context](childPairFactory, tagJsonUtils, tags)
}

//case object TestTagsKey extends SharedComponentKey[TestTags[Context]]