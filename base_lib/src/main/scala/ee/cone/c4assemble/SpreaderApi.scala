package ee.cone.c4assemble

trait SumHandler[S] {
  def get(src: S): Long
}

trait FlattenHandler[S,F,T] {
  def get(src: S): Array[F]
  def create(size: Int): Array[T]
}

trait SpreadHandler[N] {
  def toPos(it: N): Int
  def createPart(sz: Int): Array[N]
  def createRoot(sz: Int): Array[Array[N]]
}

trait ArrayUtil {
  def spread[N](src: Array[N], itemCount: Int, partCount: Int, handler: SpreadHandler[N]): Array[Array[N]]
  def flatten[S,F,T](srcs: Array[S], handler: FlattenHandler[S,F,T]): Array[T]
  def sum[S](src: Array[S], handler: SumHandler[S]): Long
}
