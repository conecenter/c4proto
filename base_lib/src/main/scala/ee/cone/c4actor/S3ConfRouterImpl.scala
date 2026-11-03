package ee.cone.c4actor

import ee.cone.c4assemble.Single
import ee.cone.c4di.c4

@c4("S3ConfRouterApp") final class S3ConfRouterImpl(routes: List[S3ConfRoute]) extends S3ConfRouter {
  def envKey(bucket: String): String =
    Single.option(routes.flatMap(_.envKey(bucket))).getOrElse("C4S3_CONF_DIR")
}
