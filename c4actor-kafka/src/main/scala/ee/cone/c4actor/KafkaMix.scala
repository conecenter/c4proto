
package ee.cone.c4actor

trait KafkaConfigApp {
  def config: Config

  private lazy val bootstrapServers: String = config.get("C4BOOTSTRAP_SERVERS")
  private lazy val inboxTopicPrefix: String = config.get("C4INBOX_TOPIC_PREFIX")
  lazy val kafkaConfig = KafkaConfig(bootstrapServers,inboxTopicPrefix)
}

trait KafkaProducerApp extends KafkaConfigApp with ToStartApp {
  private lazy val kafkaProducer: KafkaRawQSender = new KafkaRawQSender(kafkaConfig)()
  def rawQSender: RawQSender with Executable = kafkaProducer
  override def toStart: List[Executable] = rawQSender :: super.toStart
}

trait KafkaConsumerApp extends KafkaConfigApp with ToStartApp {
  def rawSnapshot: RawSnapshot
  def rawObserver: RawObserver
  //
  private lazy val kafkaConsumer =
    new KafkaActor(kafkaConfig)(rawSnapshot,rawObserver)
  override def toStart: List[Executable] = kafkaConsumer :: super.toStart
}