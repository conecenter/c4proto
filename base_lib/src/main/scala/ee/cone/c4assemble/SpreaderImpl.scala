package ee.cone.c4assemble

import ee.cone.c4di.c4

@c4("AssembleApp") final class SpreaderImpl extends Spreader {
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
}
