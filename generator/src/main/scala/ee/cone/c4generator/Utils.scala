package ee.cone.c4generator

import scala.meta.Tree

object Utils {
  def parseError(s: Tree, macros: String, fileName: String) =
    throw new Exception(s"@${macros}: Can't parse structure ${s.toString()} at ${fileName}:${s.pos.startLine + 1},${s.pos.startColumn + 1}")
}
