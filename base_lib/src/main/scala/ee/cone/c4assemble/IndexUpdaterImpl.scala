package ee.cone.c4assemble

import ee.cone.c4assemble.Types.{DMap, Index}
import ee.cone.c4di.c4

import scala.collection.immutable.Seq

@c4("AssembleApp") final class ReadModelUtilImpl(indexUtil: IndexUtil) extends ReadModelUtil {
  def updated(pairs: Iterable[(AssembledKey,Index)]): ReadModel=>ReadModel = {
    case from: ReadModelImpl =>
      new ReadModelImpl(from.inner ++ pairs)
  }
  def toMap: ReadModel=>Map[AssembledKey,Index] = {
    case model: ReadModelImpl => model.inner
  }
}

class ReadModelImpl(val inner: DMap[AssembledKey,Index]) extends ReadModel {
  def getIndex(key: AssembledKey): Option[Index] = inner.get(key)
}