package ee.cone.c4actor.rdb

import ee.cone.c4actor.UniversalProp

trait CustomFieldAdapter {
  def supportedCl: Class[_]
  def encode(value: Object): String
  def toUniversalProp(tag: Int, value: String): UniversalProp
}