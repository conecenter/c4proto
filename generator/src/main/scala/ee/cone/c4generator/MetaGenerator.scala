package ee.cone.c4generator

import scala.collection.immutable.Seq
import scala.meta._

case class OrigMeta()

class MetaGenerator(statTransformers: List[ProtocolStatsTransformer]) extends Generator {
  def get(parseContext: ParseContext): List[Generated] =
    parseContext.stats.flatMap{
      case q"@protocol(..$exprss) object ${objectNameNode@Term.Name(objectName)} extends ..$ext { ..$stats }" =>
        val c4ann = if (exprss.isEmpty) "@c4" else mod"@c4(...$exprss)".syntax
        val transformers = statTransformers.map(_.transform(parseContext))
        val preparedStats = transformers.foldLeft(stats.toList){(list, transformer) => transformer(list)}
        def getMeta()
    }

  def getMeta(parseContext: ParseContext, objectName: String, stats: List[Stat], c4ann: String): Seq[Generated] = {
    val classes = Util.matchClass(stats)
    classes.flatMap{classDef =>

    }
  }
}
