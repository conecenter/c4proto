package ee.cone.c4generator

import scala.meta.Tree

object Utils {
  def parseError(s: Tree, parseContext: ParseContext, message: String = "") =
    throw GeneratorError(s"Can't parse structure ${s.toString()} ${if (message.nonEmpty) s"Reason: $message" else ""} at ${parseContext.path}:${s.pos.startLine + 1},${s.pos.startColumn + 1}")
}

final case class GeneratorError(
  private val message: String = "",
  private val cause: Throwable = None.orNull
)
  extends Exception(message, cause)
