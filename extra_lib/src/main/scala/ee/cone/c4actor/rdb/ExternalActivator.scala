package ee.cone.c4actor.rdb

import ee.cone.c4di.c4


/**
 * Component which defines if app has connection to external
 */
trait ExternalActivator

trait ExternalActivatorAppBase

@c4("ExternalActivatorApp") final class ExternalIsActive(
  activeMode: Option[ExternalActivator]
) {
  lazy val isActive: Boolean = activeMode.nonEmpty
}
