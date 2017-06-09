package ee.cone.c4actor

import java.time.Instant

import ee.cone.c4actor.ManagementProtocol.ManagementConsumer
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Index, Values, World}
import ee.cone.c4assemble.{Assemble, JoinKey, Single, assemble}
import ee.cone.c4proto.{Id, Protocol, protocol}

@protocol object ManagementProtocol extends Protocol {
  @Id(0x0048) case class ManagementConsumer(
    @Id(0x0049) srcId: String,
    @Id(0x004A) slices: List[String],
    @Id(0x004B) sleep: Long
  )
}

@assemble class ManagementConsumerAssemble(consumerKey: String) extends Assemble {
  def join(
    key: SrcId,
    consumers: Values[ManagementConsumer]
  ): Values[(SrcId,TxTransform)] =
    for(c ← consumers if c.srcId == consumerKey)
      yield WithSrcId(ManagementConsumerTx(s"ManagementConsumer-${c.srcId}",c))
}

case class ManagementConsumerTx(srcId: SrcId, consumer: ManagementConsumer) extends TxTransform {
  def transform(local: World): World = {
    val world = TxKey.of(local).world
    val worldKeysByAlias: Map[String, List[JoinKey[Any, Product]]] =
      world.keys.toList.collect{
        case k@JoinKey(alias,keyClassName,valueClassName) ⇒
          (s"$alias,${valueClassName.split("\\.").last}", k)
      }.groupBy(_._1).transform((k,l)⇒l.map(_._2))
    val Slice = """(\w+,\w+)(,.*)?""".r
    val result: List[String] = consumer.slices.flatMap{
      case slice@Slice(worldKeyAlias,rest) ⇒
        val worldKeys = worldKeysByAlias.getOrElse(worldKeyAlias,Nil)
        worldKeys.flatMap{ worldKey ⇒
          val index: Index[Any, Product] = worldKey.of(world)
          val res = if(rest.isEmpty) index.keys else index.getOrElse(rest.tail,Nil)
          s"$worldKey$rest" :: res.map(v⇒s"  $v").toList
        }
    }
    println(result.mkString("\n"))
    SleepUntilKey.set(Instant.now.plusSeconds(consumer.sleep))(local)
  }
}
