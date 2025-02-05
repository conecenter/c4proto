package ee.cone.c4gate

import ee.cone.c4gate.AuthProtocol.N_SecureHash

trait AuthOperations {
  def createHash(password: String, userHashOpt: Option[N_SecureHash]): N_SecureHash
  def verify(password: String, correctHash: N_SecureHash): Boolean
}
