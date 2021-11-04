package ee.cone.c4actor.rdb

import ee.cone.c4actor.UniversalProp

trait UniversalPropHandler {
  def handledType: String
  def handle(tag: Int, value: String): UniversalProp
}
