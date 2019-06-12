
package ee.cone.c4gate

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.Firstborn
import ee.cone.c4actor._
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types._
import ee.cone.c4assemble._
import ee.cone.c4gate.HttpProtocol.S_HttpPost

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

@assemble class ManagementPostAssembleBase(actorName: String, indexUtil: IndexUtil, readModelUtil: ReadModelUtil, defaultAssembleOptions: AssembleOptions)   {
  def joinHttpPostHandler(
    key: SrcId,
    post: Each[S_HttpPost]
  ): Values[(SrcId, TxTransform)] =
    if(post.path == s"/manage/$actorName")
      List(WithPK(ManageHttpPostTx(post.srcId, post, defaultAssembleOptions)(indexUtil,readModelUtil))) else Nil

  def joinConsumers(
    key: SrcId,
    first: Each[Firstborn]
  ): Values[(SrcId,LocalPostConsumer)] =
    List(WithPK(LocalPostConsumer(s"/manage/$actorName")))
}

case class ManageHttpPostTx(srcId: SrcId, post: S_HttpPost, defaultAssembleOptions: AssembleOptions)(indexUtil: IndexUtil, readModelUtil: ReadModelUtil) extends TxTransform with LazyLogging {
  private def indent(l: String) = s"  $l"
  private def valueLines(index: Index, options: AssembleOptions)(k: Any): List[String] =
    indexUtil.getValues(index,k,"",options).flatMap(v⇒s"$v".split("\n")).map(indent).toList
  private def report(local: Context): String = {
    val options = ByPK(classOf[AssembleOptions]).of(local)
      .getOrElse(ToPrimaryKey(defaultAssembleOptions),defaultAssembleOptions)
    val headers: Map[String, String] = post.headers.map(h⇒h.key→h.value).toMap
    val world = readModelUtil.toMap(local.assembled)
    val WorldKeyAlias = """(\w+),(\w+)""".r
    val worldKeyAlias = headers("X-r-world-key")
    val WorldKeyAlias(alias,keyClassAlias) = worldKeyAlias
    val (indexStr,index): (String,Index) = Single.option(world.collect{
      case (worldKey: JoinKey, index) if !worldKey.was && worldKey.keyAlias == alias &&
        worldKey.valueClassName.split("\\W").last == keyClassAlias ⇒
        (s"$worldKey",index)
    }.toList).getOrElse(("[index not found]",emptyIndex))
    val res: List[String] = headers("X-r-selection") match {
      case k if k.startsWith(":") ⇒ k.tail :: valueLines(index, options)(k.tail)
      case "keys" ⇒ indexUtil.keySet(index).map(_.toString).toList.sorted
      case "all" ⇒ indexUtil.keySet(index).map(k⇒k.toString→k).toList.sortBy(_._1).flatMap{
        case(ks,k) ⇒ ks :: valueLines(index, options)(k)
      }
    }
    (s"REPORT $indexStr" :: res.map(indent) ::: "END" :: Nil).mkString("\n")
  }
  def transform(local: Context): Context = {
    if(ErrorKey.of(local).isEmpty) logger.info(report(local))
    TxAdd(LEvent.delete(post))(local)
  }
}
