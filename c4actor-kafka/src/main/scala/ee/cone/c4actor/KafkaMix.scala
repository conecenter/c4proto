
package ee.cone.c4actor

trait KafkaConfigApp {
  def config: Config

  private lazy val bootstrapServers: String = config.get("C4BOOTSTRAP_SERVERS")
  lazy val inboxTopicPrefix: String = config.get("C4INBOX_TOPIC_PREFIX")
  private lazy val maxRequestSize: String = config.get("C4MAX_REQUEST_SIZE")
  private lazy val keyStorePath: String = config.get("C4KEYSTORE_PATH")
  private lazy val trustStorePath: String = config.get("C4TRUSTSTORE_PATH")
  private lazy val keyPassPath: String = config.get("C4AUTH_KEY_FILE")
  lazy val kafkaConfig: KafkaConfig = KafkaConfig(
    bootstrapServers,inboxTopicPrefix,maxRequestSize,
    keyStorePath,trustStorePath,keyPassPath
  )()
}

trait KafkaProducerApp extends KafkaConfigApp with ToStartApp {
  def execution: Execution
  //
  private lazy val kafkaProducer: KafkaRawQSender = new KafkaRawQSender(kafkaConfig,execution)()
  def rawQSender: RawQSender with Executable = kafkaProducer
  override def toStart: List[Executable] = rawQSender :: super.toStart
}

trait KafkaConsumerApp extends KafkaConfigApp with LZ4DeCompressorApp {
  def execution: Execution
  //
  lazy val consuming: Consuming = KafkaConsuming(kafkaConfig)(execution)
}
