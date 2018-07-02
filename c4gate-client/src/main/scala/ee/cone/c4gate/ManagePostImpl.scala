
package ee.cone.c4gate

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.Firstborn
import ee.cone.c4actor._
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types._
import ee.cone.c4assemble._
import ee.cone.c4gate.HttpProtocol.HttpPost

@assemble class ManagementPostAssemble(actorName: String, indexUtil: IndexUtil) extends Assemble {
  def joinHttpPostHandler(
    key: SrcId,
    posts: Values[HttpPost]
  ): Values[(SrcId, TxTransform)] =
    for(post ← posts if post.path == s"/manage/$actorName")
      yield WithPK(ManageHttpPostTx(post.srcId, post)(indexUtil))

  def joinConsumers(
    key: SrcId,
    firsts: Values[Firstborn]
  ): Values[(SrcId,LocalPostConsumer)] =
    for(_ ← firsts)
      yield WithPK(LocalPostConsumer(s"/manage/$actorName"))
}

case class ManageHttpPostTx(srcId: SrcId, post: HttpPost)(indexUtil: IndexUtil) extends TxTransform with LazyLogging {
  private def indent(l: String) = s"  $l"
  private def valueLines(index: Index)(k: Any): List[String] =
    indexUtil.getValues(index,k,"").flatMap(v⇒s"$v".split("\n")).map(indent).toList
  private def report(local: Context): String = {
    val headers: Map[String, String] = post.headers.map(h⇒h.key→h.value).toMap
    val world = local.assembled
    val WorldKeyAlias = """(\w+),(\w+)""".r
    val worldKeyAlias = headers("X-r-world-key")
    val WorldKeyAlias(alias,keyClassAlias) = worldKeyAlias
    val (indexStr,index): (String,Index) = Single.option(world.keys.toList.collect{
      case worldKey: JoinKey if !worldKey.was && worldKey.keyAlias == alias &&
        worldKey.valueClassName.split("\\W").last == keyClassAlias ⇒
        (s"$worldKey",worldKey.of(world))
    }).getOrElse(("[index not found]",emptyIndex))
    val res: List[String] = headers("X-r-selection") match {
      case k if k.startsWith(":") ⇒ k.tail :: valueLines(index)(k.tail)
      case "keys" ⇒ indexUtil.keySet(index).map(_.toString).toList.sorted
      case "all" ⇒ indexUtil.keySet(index).map(k⇒k.toString→k).toList.sortBy(_._1).flatMap{
        case(ks,k) ⇒ ks :: valueLines(index)(k)
      }
    }
    (s"REPORT $indexStr" :: res.map(indent) ::: "END" :: Nil).mkString("\n")
  }
  def transform(local: Context): Context = {
    if(ErrorKey.of(local).isEmpty) logger.info(report(local))
    TxAdd(LEvent.delete[Product](post))(local)
  }
}
