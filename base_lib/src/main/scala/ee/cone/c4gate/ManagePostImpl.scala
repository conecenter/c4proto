
package ee.cone.c4gate

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor._
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types._
import ee.cone.c4assemble._
import ee.cone.c4di.c4multi
import ee.cone.c4gate.HttpProtocol.S_HttpRequest

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

@c4assemble("ManagementApp") class ManagementPostAssembleBase(actorName: ActorName, factory: ManageHttpPostTxFactory) {
  def joinHttpPostHandler(
    key: SrcId,
    post: Each[S_HttpRequest]
  ): Values[(SrcId, TxTransform)] =
    if(post.path == s"/manage/${actorName.value}")
      List(WithPK(factory.create(post.srcId, post))) else Nil

  def joinConsumers(
    key: SrcId,
    first: Each[S_Firstborn]
  ): Values[(SrcId,LocalHttpConsumer)] =
    List(WithPK(LocalHttpConsumer(s"/manage/${actorName.value}")))
}

@c4multi("ManagementApp") final case class ManageHttpPostTx(srcId: SrcId, request: S_HttpRequest)(
  indexUtil: IndexUtil,
  readModelUtil: ReadModelUtil,
  catchNonFatal: CatchNonFatal,
  txAdd: LTxAdd,
) extends TxTransform with LazyLogging {
  private def indent(l: String) = s"  $l"
  private def valueLines(index: Index)(k: Any): List[String] =
    indexUtil.getValues(index,k,"").flatMap(v=>s"$v".split("\n")).map(indent).toList
  private def report(local: Context): String = {
    assert(request.method == "POST")
    //val options = GetAssembleOptions.of(local)(local.assembled)
    val headers: Map[String, String] = request.headers.map(h=>h.key->h.value).toMap
    val world = readModelUtil.toMap(local.assembled)
    val WorldKeyAlias = """(\w+),(\w+)""".r
    val worldKeyAlias = headers("x-r-world-key")
    val WorldKeyAlias(alias,keyClassAlias) = worldKeyAlias
    val (indexStr,index): (String,Index) = Single.option(world.collect{
      case (worldKey: JoinKey, index) if !worldKey.was && worldKey.keyAlias == alias &&
        worldKey.valueClassName.split("\\W").last == keyClassAlias =>
        (s"$worldKey",index)
    }.toList).getOrElse(("[index not found]",emptyIndex))
    val res: List[String] = headers("x-r-selection") match {
      case k if k.startsWith(":") => k.tail :: valueLines(index)(k.tail)
      case "keys" => indexUtil.keySet(index).map(_.toString).toList.sorted
      case "all" => indexUtil.keySet(index).map(k=>k.toString->k).toList.sortBy(_._1).flatMap{
        case(ks,k) => ks :: valueLines(index)(k)
      }
    }
    (s"REPORT $indexStr" :: res.map(indent) ::: "END" :: Nil).mkString("\n")
  }
  def transform(local: Context): Context = {
    catchNonFatal{ logger.info(report(local)) }("manage"){ e => ()}
    txAdd.add(LEvent.delete(request))(local)
  }
}
