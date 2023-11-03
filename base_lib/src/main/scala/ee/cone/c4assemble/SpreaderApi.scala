package ee.cone.c4assemble

trait SpreadHandler[N] {
  def toPos(it: N): Int
  def createPart(sz: Int): Array[N]
  def createRoot(sz: Int): Array[Array[N]]
}

trait Spreader {
  def spread[N](src: Array[N], itemCount: Int, partCount: Int, handler: SpreadHandler[N]): Array[Array[N]]
}
