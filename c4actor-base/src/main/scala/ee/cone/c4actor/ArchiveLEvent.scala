package ee.cone.c4actor

import ee.cone.c4actor.Types.SrcId

case class ArchiveLEvent[+M <: Product](srcId: SrcId, className: String) extends LEvent[M]
