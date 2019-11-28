
package ee.cone.c4gate_akka_s3

import ee.cone.c4actor_s3_minio.MinioS3App
import ee.cone.c4di.c4app
import ee.cone.c4gate_akka.AkkaGatewayAppBase

@c4app class AkkaGatewayWithMinioAppBase extends AkkaGatewayAppBase with MinioS3App
