package ee.cone.c4actor

trait HashGen {
  def generate[Model](m: Model): String
  def generateLong[Model](m: Model): (Long, Long)
}