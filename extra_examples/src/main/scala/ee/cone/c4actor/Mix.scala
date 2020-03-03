package ee.cone.c4actor

trait ActivateContextAppBase extends ComponentProviderApp {
  def activateContext: ActivateContext = resolveSingle(classOf[ActivateContext])
}
