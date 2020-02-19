package ee.cone.c4gate

import java.util.UUID

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.LifeTypes.Alive
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import ee.cone.c4di._
import ee.cone.c4gate.HttpProtocol._

@c4("PublisherApp") class PublisherImpl(
  idGenUtil: IdGenUtil,
  actorName: ActorName,
  updateIfChanged: UpdateIfChanged,
  getByPathHttpPublication: GetByPK[ByPathHttpPublication],
  getS_Manifest: GetByPK[S_Manifest]
) extends Publisher with LazyLogging {
  def genId(): SrcId = idGenUtil.srcIdFromStrings(UUID.randomUUID.toString)
  def publish(publication: ByPathHttpPublication, until: Long=>Long): Seq[LEvent[Product]] = {
    val now = System.currentTimeMillis
    LEvent.update(S_Manifest(genId(),List(publication.path),until(now))) ++
    publishInner(publication,now)
  }
  def publishInner(publication: ByPathHttpPublication, now: Long): Seq[LEvent[Product]] = {
    logger.debug(s"publishing ${publication.path} (${publication.body.size})")
    LEvent.update(S_HttpPublicationV2(genId(),publication.path,publication.headers,publication.body,now))
  }
  def publish(man: String, publications: List[ByPathHttpPublication]): Context=>Seq[LEvent[Product]] = local => {
    logger.debug(s"publish $man started")
    val now = System.currentTimeMillis
    val existingPublications = getByPathHttpPublication.ofA(local)
    val pubEvents = for {
      publication <- publications if !existingPublications.get(publication.path).contains(publication)
      event <- publishInner(publication,now)
    } yield event
    val paths = for { publication <- publications } yield publication.path
    logger.debug(s"paths: $paths")
    val manSrcId = s"${actorName.value}-PublishingManifest-$man"
    val manifest = S_Manifest(manSrcId,paths,Long.MaxValue)
    val manEvents = updateIfChanged.updateSimple(getS_Manifest)(local)(Seq(manifest))
    logger.debug(s"publish $man finishing")
    manEvents ++ pubEvents
  }
}

@c4assemble("PublisherApp") class PublicationByPathAssembleBase(
  val max: Values[S_HttpPublicationV2] => Option[S_HttpPublicationV2] =
    _.maxByOption(p => (p.time, p.srcId))
) {
  type ByPath = String

  def mapListedByPath(
    key: SrcId,
    manifest: Each[S_Manifest]
  ): Values[(ByPath,ByPathHttpPublicationUntil)] =
    manifest.paths.map(p=>WithPK(ByPathHttpPublicationUntil(p,manifest.until)))

  def selectMaxUntil(
    key: SrcId,
    @by[ByPath] listed: Values[ByPathHttpPublicationUntil],
  ): Values[(SrcId,ByPathHttpPublicationUntil)] =
    List(WithPK(listed.maxBy(_.until)))

  ///

  def mapPublicationByPath(
    key: SrcId,
    publication: Each[S_HttpPublicationV2]
  ): Values[(ByPath,S_HttpPublicationV2)] =
    List(publication.path->publication)

  def selectMax(
    key: SrcId,
    @by[ByPath] publications: Values[S_HttpPublicationV2],
    listed: Each[ByPathHttpPublicationUntil]
  ): Values[(SrcId,ByPathHttpPublication)] = for {
    p <- max(publications).toList
  } yield WithPK(ByPathHttpPublication(p.path,p.headers,p.body))

  def life(
    key: SrcId,
    @by[ByPath] publications: Values[S_HttpPublicationV2],
    listed: Each[ByPathHttpPublicationUntil]
  ): Values[(Alive,S_HttpPublicationV2)] = for {
    p <- max(publications).toList
  } yield WithPK(p)

}
