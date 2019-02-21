package ee.cone.c4actor

trait HashGen {
  def generate[Model](m: Model): String
}
