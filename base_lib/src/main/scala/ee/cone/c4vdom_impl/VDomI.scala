package ee.cone.c4vdom_impl

import ee.cone.c4vdom.Types.VDomKey
import ee.cone.c4vdom._

trait JsonToString {
  def apply(value: VDomValue): String
}

trait WasNoVDomValue extends VDomValue

trait VPair {
  def jsonKey: String
  def sameKey(other: VPair): Boolean
  def value: VDomValue
  def withValue(value: VDomValue): VPair
}

trait MapVDomValue extends VDomValue {
  def pairs: List[VPair]
}

trait Diff {
  def diff(prevValue: VDomValue, currValue: VDomValue): Option[MapVDomValue]
}

trait DuplicateKeysException extends Exception
trait FixDuplicateKeys {
  def fix(ex: DuplicateKeysException, value: VDomValue): VDomValue
}

trait SeedVDomValue extends VDomValue {
  def seed: Product
}