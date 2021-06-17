package ee.cone.c4actor_kafka_impl

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.NextOffset
import ee.cone.c4actor.{FinallyClose, InboxTopicName, QPurger, QPurging}
import ee.cone.c4di.c4
import org.apache.kafka.clients.admin.{AdminClient, AlterConfigOp, ConfigEntry, RecordsToDelete}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.config.{ConfigResource, TopicConfig}

import java.util
import scala.jdk.CollectionConverters.MapHasAsJava

@c4("KafkaPurgerApp") final class KafkaPurging(
  conf: KafkaConfig
) extends QPurging {
  def process[R](body: QPurger => R): R = FinallyClose(
    AdminClient.create(conf.ssl.transform{ case (_,v) => v:Object}.asJava)
  ) { client =>
    keepRecordsForever(client)
    body(new KafkaPurger(this,client))
  }
  def topicName: String = conf.topicNameToString(InboxTopicName())
  def keepRecordsForever(client: AdminClient): Unit = {
    val commands: Map[ConfigResource, util.Collection[AlterConfigOp]] = Map(
      new ConfigResource(ConfigResource.Type.TOPIC, topicName) ->
        util.Collections.singleton(
          new AlterConfigOp(
            new ConfigEntry(
              TopicConfig.RETENTION_MS_CONFIG, "-1"
            ), AlterConfigOp.OpType.SET
          )
        )
    )
    ignoreVoid(client.incrementalAlterConfigs(commands.asJava).all.get)
  }
  def ignoreVoid(v: Void): Unit = ()
}

class KafkaPurger(
  purging: KafkaPurging, client: AdminClient
) extends QPurger with LazyLogging {
  def delete(beforeOffset: NextOffset): Unit = {
    val numOffset = java.lang.Long.parseLong(beforeOffset,16)
    val partition = new TopicPartition(purging.topicName, 0)
    val commands = Map(partition -> RecordsToDelete.beforeOffset(numOffset))
    purging.ignoreVoid(client.deleteRecords(commands.asJava).all.get)
    logger.info(s"deleted records before $beforeOffset ($numOffset)")
  }
}
