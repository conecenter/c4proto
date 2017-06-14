
package ee.cone.c4gate

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Index, Values, World}
import ee.cone.c4assemble.{Assemble, JoinKey, Single, assemble}
import ee.cone.c4gate.HttpProtocol.HttpPost

@assemble class ManagementPostAssemble(actorName: ActorName) extends Assemble {
  def joinHttpPostHandler(
    key: SrcId,
    posts: Values[HttpPost]
  ): Values[(SrcId, TxTransform)] =
    for(post ← posts if post.path == s"/manage/${actorName.value}")
      yield WithSrcId(ManageHttpPostTx(post.srcId, post))
}

case class ManageHttpPostTx(srcId: SrcId, post: HttpPost) extends TxTransform {
  private def indent(l: String) = s"  $l"
  private def valueLines(index: Index[Any, Product])(k: Any): List[String] =
    index.getOrElse(k,Nil).flatMap(v⇒s"$v".split("\n")).map(indent).toList
  private def report(local: World): String = {
    val headers: Map[String, String] = post.headers.map(h⇒h.key→h.value).toMap
    val world = TxKey.of(local).world
    val WorldKeyAlias = """(\w+),(\w+)""".r
    val worldKeyAlias = headers("X-r-world-key")
    val WorldKeyAlias(alias,keyClassAlias) = worldKeyAlias
    val (indexStr,index): (String,Index[Any, Product]) = Single.option(world.keys.toList.collect{
      case worldKey@JoinKey(`alias`,keyClassName,valueClassName)
        if valueClassName.split("\\W").last == keyClassAlias ⇒
        (s"$worldKey",worldKey.of(world))
    }).getOrElse(("[index not found]",Map.empty))
    val res: List[String] = headers("X-r-selection") match {
      case k if k.startsWith(":") ⇒ k.tail :: valueLines(index)(k.tail)
      case "keys" ⇒ index.keys.map(_.toString).toList.sorted
      case "all" ⇒ index.keys.map(k⇒k.toString→k).toList.sortBy(_._1).flatMap{
        case(ks,k) ⇒ ks :: valueLines(index)(k)
      }
    }
    (s"REPORT $indexStr" :: res.map(indent) ::: "END" :: Nil).mkString("\n")
  }
  def transform(local: World): World = {
    if(ErrorKey.of(local).isEmpty) println(report(local))
    LEvent.add(LEvent.delete[Product](post))(local)
  }
}
