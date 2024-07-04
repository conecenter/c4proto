package ee.cone.c4assemble

import ee.cone.c4di.c4

@SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.While"))
@c4("AssembleApp") final class ArrayUtilImpl extends ArrayUtil {
  def spread[N](src: Array[N], itemCount: Int, partCount: Int, handler: SpreadHandler[N]): Array[Array[N]] = {
    val dest = handler.createRoot(partCount)
    val ends = new Array[Int](dest.length)
    var sp = 0
    while (sp < itemCount) {
      val it = src(sp)
      val drp = handler.toPos(it)
      ends(drp) += 1
      sp += 1
    }
    {
      var drp = 0
      while (drp < dest.length) {
        val dip = ends(drp)
        dest(drp) = handler.createPart(dip)
        drp += 1
      }
    }
    while (sp > 0) {
      sp -= 1
      val it = src(sp)
      val drp = handler.toPos(it)
      ends(drp) -= 1
      dest(drp)(ends(drp)) = it
    }
    dest
  }
  def flatten[S,F,T](srcs: Array[S], handler: FlattenHandler[S,F,T]): Array[T] = {
    val start = 0
    val end = srcs.length
    var kPos = 0
    var oPos = start
    while (oPos < end) {
      val keys = handler.get(srcs(oPos))
      kPos += keys.length
      oPos += 1
    }
    val res = handler.create(kPos)
    while (oPos > start) {
      oPos -= 1
      val keys = handler.get(srcs(oPos))
      kPos -= keys.length
      if (keys.length > 0) System.arraycopy(keys, 0, res, kPos, keys.length)
    }
    assert(kPos == 0)
    res
  }

  def sum[S](src: Array[S], handler: LongGetter[S]): Long = {
    var res = 0L
    var i = 0
    while (i < src.length) {
      res += handler.get(src(i))
      i += 1
    }
    res
  }

  def max[S](src: Array[S], handler: LongGetter[S], start: Long): Long = {
    var res = start
    var i = 0
    while (i < src.length) {
      res = Math.max(res, handler.get(src(i)))
      i += 1
    }
    res
  }

}
