
package ee.cone.c4actor

trait KafkaProducerApp extends `The KafkaConfigImpl` with `The KafkaRawQSenderExecutable` with `The KafkaRawQSender`

trait KafkaConsumerApp extends `The KafkaConfigImpl` with `The KafkaActor`