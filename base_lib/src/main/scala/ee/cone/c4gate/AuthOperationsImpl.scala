package ee.cone.c4gate

import ee.cone.c4di.c4
import ee.cone.c4gate.AuthProtocol.N_SecureHash
import ee.cone.c4proto.ToByteString

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

@c4("AuthOperationsApp") final class AuthOperationsImpl extends AuthOperations {
  private def generateSalt(size: Int): okio.ByteString = {
    val random = new SecureRandom()
    val salt = new Array[Byte](size)
    random.nextBytes(salt)
    ToByteString(salt)
  }
  private def pbkdf2(password: String, template: N_SecureHash): N_SecureHash = {
    val spec = new PBEKeySpec(password.toCharArray, template.salt.toByteArray, template.iterations, template.hashSizeInBytes * 8)
    val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
    template.copy(hash=ToByteString(skf.generateSecret(spec).getEncoded))
  }
  def createHash(password: String, userHashOpt: Option[N_SecureHash]): N_SecureHash =
    pbkdf2(password, userHashOpt.getOrElse(N_SecureHash(64000, 18, generateSalt(24), okio.ByteString.EMPTY)))
  def verify(password: String, correctHash: N_SecureHash): Boolean =
    correctHash == pbkdf2(password, correctHash)
}
