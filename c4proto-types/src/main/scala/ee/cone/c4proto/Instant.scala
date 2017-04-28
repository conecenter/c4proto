package ee.cone.c4proto

import java.time.Instant

import com.squareup.wire.{ProtoAdapter, ProtoReader, ProtoWriter}

object InstantProtoAdapter {
  def encode(writer: ProtoWriter, value: Instant): Unit =
    ProtoAdapter.SINT64.encode(writer, value.toEpochMilli)
  def decode(reader: ProtoReader): Instant =
    Instant.ofEpochMilli(ProtoAdapter.SINT64.decode(reader))
  def encodedSize(value: Instant): Int =
    ProtoAdapter.SINT64.encodedSize(value.toEpochMilli)
}
