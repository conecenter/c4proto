
package ee.cone.c4actor

trait KafkaConfigApp {
  def `the Config`: Config

  private lazy val bootstrapServers: String = `the Config`.get("C4BOOTSTRAP_SERVERS")
  private lazy val inboxTopicPrefix: String = `the Config`.get("C4INBOX_TOPIC_PREFIX")
  lazy val kafkaConfig = KafkaConfig(bootstrapServers,inboxTopicPrefix)
}

trait KafkaProducerApp extends KafkaConfigApp with `The KafkaRawQSenderExecutable` {
  def execution: Execution
  //
  lazy val `the RawQSender`: RawQSender = new KafkaRawQSender(kafkaConfig,execution)()
}

trait KafkaConsumerApp extends KafkaConfigApp with `The Executable` {
  def execution: Execution
  def `the RawSnapshot`: RawSnapshot
  def `the ProgressObserverFactory`: ProgressObserverFactory
  //
  private lazy val kafkaConsumer =
    new KafkaActor(kafkaConfig)(`the RawSnapshot`,`the ProgressObserverFactory`,execution)
  override def `the List of Executable`: List[Executable] = kafkaConsumer :: super.`the List of Executable`
}