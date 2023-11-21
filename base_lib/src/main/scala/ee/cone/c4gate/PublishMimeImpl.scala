package ee.cone.c4gate

import ee.cone.c4di.c4

@c4("PublishingCompApp") final class DefMimeTypesProvider extends PublishMimeTypesProvider {
  def get: List[(String, String)] = List( //not finished on gate-server side
    "html" -> "text/html; charset=UTF-8",
    "js" -> "application/javascript",
    "ico" -> "image/x-icon",
    "svg" -> "image/svg+xml",
    "zip" -> "application/zip",
  )
}
