package ee.cone.c4gate_devel

import java.nio.file.Path

trait FileConsumerDir {
  def resolve(p: String): Path
}
