package ee.cone.c4actor
import scala.collection.immutable

class NoExtUpdateProcessor extends ExtUpdateProcessor {
  def process(updates: immutable.Seq[QProtocol.Update]): immutable.Seq[QProtocol.Update] = updates
  val idSet: Set[Long] = Set.empty
}

trait WithDefaultExtProcessor extends ExtUpdateProcessorApp {
  def extUpdateProcessor: ExtUpdateProcessor = new NoExtUpdateProcessor
}