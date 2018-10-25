
package ee.cone.c4actor

trait KafkaConfigApp {
  def config: Config

  private lazy val bootstrapServers: String = config.get("C4BOOTSTRAP_SERVERS")
  lazy val inboxTopicPrefix: String = config.get("C4INBOX_TOPIC_PREFIX")
  private lazy val maxRequestSize: String = config.get("C4MAX_REQUEST_SIZE")
  lazy val kafkaConfig: KafkaConfig = KafkaConfig(bootstrapServers,inboxTopicPrefix,maxRequestSize)()
}

trait KafkaProducerApp extends KafkaConfigApp with ToStartApp {
  def execution: Execution
  //
  private lazy val kafkaProducer: KafkaRawQSender = new KafkaRawQSender(kafkaConfig,execution)()
  def rawQSender: RawQSender with Executable = kafkaProducer
  override def toStart: List[Executable] = rawQSender :: super.toStart
}

trait KafkaConsumerApp extends KafkaConfigApp {
  def execution: Execution
  //
  lazy val consuming: Consuming = KafkaConsuming(kafkaConfig)(execution)
}