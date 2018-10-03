
package ee.cone.c4actor

trait KafkaConfigApp {
  def config: Config

  private lazy val bootstrapServers: String = config.get("C4BOOTSTRAP_SERVERS")
  private lazy val inboxTopicPrefix: String = config.get("C4INBOX_TOPIC_PREFIX")
  lazy val kafkaConfig = KafkaConfig(bootstrapServers,inboxTopicPrefix)
}

trait KafkaProducerApp extends KafkaConfigApp with ToStartApp {
  def execution: Execution
  //
  private lazy val kafkaProducer: KafkaRawQSender = new KafkaRawQSender(kafkaConfig,execution)()
  def rawQSender: RawQSender with Executable = kafkaProducer
  override def toStart: List[Executable] = rawQSender :: super.toStart
}

trait KafkaConsumerApp extends KafkaConfigApp with ToStartApp {
  def execution: Execution
  def rawSnapshot: RawSnapshot
  def progressObserverFactory: ProgressObserverFactory
  def toUpdate: ToUpdate
  //
  private lazy val kafkaConsumer =
    new KafkaActor(kafkaConfig)(rawSnapshot,progressObserverFactory,execution,toUpdate)
  override def toStart: List[Executable] = kafkaConsumer :: super.toStart
}