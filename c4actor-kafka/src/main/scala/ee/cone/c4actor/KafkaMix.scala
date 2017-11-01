
package ee.cone.c4actor

trait KafkaConfigApp {
  def `the Config`: Config

  private lazy val bootstrapServers: String = `the Config`.get("C4BOOTSTRAP_SERVERS")
  private lazy val inboxTopicPrefix: String = `the Config`.get("C4INBOX_TOPIC_PREFIX")
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
  def `the RawSnapshot`: RawSnapshot
  def progressObserverFactory: ProgressObserverFactory
  //
  private lazy val kafkaConsumer =
    new KafkaActor(kafkaConfig)(`the RawSnapshot`,progressObserverFactory,execution)
  override def toStart: List[Executable] = kafkaConsumer :: super.toStart
}