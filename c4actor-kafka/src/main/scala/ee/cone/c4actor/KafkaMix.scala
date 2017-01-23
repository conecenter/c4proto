
package ee.cone.c4actor

trait KafkaProducerApp extends ToStartApp {
  def config: Config
  lazy val bootstrapServers: String = config.get("C4BOOTSTRAP_SERVERS")
  lazy val inboxTopicPrefix: String = config.get("C4INBOX_TOPIC_PREFIX")
  lazy val kafkaProducer: KafkaRawQSender = new KafkaRawQSender(bootstrapServers,inboxTopicPrefix)()
  def rawQSender: RawQSender with Executable = kafkaProducer
  override def toStart: List[Executable] = rawQSender :: super.toStart
}

trait KafkaConsumerApp extends ToStartApp {
  def config: Config
  def qMessages: QMessages
  def qReducer: Reducer
  def initialObservers: List[Observer]
  def bootstrapServers: String
  def kafkaProducer: KafkaRawQSender
  //
  private lazy val mainActorName = ActorName(config.get("C4STATE_TOPIC_PREFIX"))
  lazy val kafkaConsumer: Executable =
    new KafkaActor(bootstrapServers, mainActorName)(qMessages, qReducer, kafkaProducer, initialObservers)()
  //
  override def toStart: List[Executable] = kafkaConsumer :: super.toStart
}