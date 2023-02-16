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

@c4("PublisherApp") final class PublisherImpl(
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

case class ByPathHttpPublicationUntilCase(srcId: SrcId, path: String, until: Long)

@c4assemble("PublisherApp") class PublicationByPathAssembleBase(idGenUtil: IdGenUtil)(
  val max: Values[S_HttpPublicationV2] => Option[S_HttpPublicationV2] =
    _.maxByOption(p => (p.time, p.srcId))
) {
  type ByPath = String

  def mapListedByPath(
    key: SrcId,
    manifest: Each[S_Manifest]
  ): Values[(ByPath,ByPathHttpPublicationUntilCase)] = {
    val untilStr = manifest.until.toString
    manifest.paths.map(p=>p->ByPathHttpPublicationUntilCase(idGenUtil.srcIdFromStrings(p,untilStr),p,manifest.until))
  }

  def selectMaxUntil(
    key: SrcId,
    @by[ByPath] listed: Values[ByPathHttpPublicationUntilCase],
  ): Values[(SrcId,ByPathHttpPublicationUntil)] = {// SrcId here is path
    val maxCase = listed.maxBy(_.until)
    List(WithPK(ByPathHttpPublicationUntil(maxCase.path, maxCase.until)))
  }

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
