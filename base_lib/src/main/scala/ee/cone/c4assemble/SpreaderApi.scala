package ee.cone.c4assemble

trait SpreadHandler[N] {
  def partCount: Int
  def toPos(it: N): Int
  def createPart(sz: Int): Array[N]
  def createRoot(sz: Int): Array[Array[N]]
}

trait Spreader {
  def spread[N](src: Array[N], handler: SpreadHandler[N]): Array[Array[N]]
}
