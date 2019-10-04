package ee.cone.c4generator

import scala.meta.Tree

object Utils {
  def parseError(s: Tree, parseContext: ParseContext) =
    throw new Exception(s"Can't parse structure ${s.toString()} at ${parseContext.path}:${s.pos.startLine + 1},${s.pos.startColumn + 1}")
}
