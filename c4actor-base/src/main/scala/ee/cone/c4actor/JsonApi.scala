package ee.cone.c4actor

import java.text.DecimalFormat

trait MutableJsonBuilding {
  def process(body: RMutableJsonBuilder⇒Unit): String
}

trait RMutableJsonBuilder {
  def startArray(): RMutableJsonBuilder
  def startObject(): RMutableJsonBuilder
  def end(): RMutableJsonBuilder
  def append(value: String): RMutableJsonBuilder
  def append(value: BigDecimal, decimalFormat: DecimalFormat): RMutableJsonBuilder
  def append(value: Boolean): RMutableJsonBuilder
}
