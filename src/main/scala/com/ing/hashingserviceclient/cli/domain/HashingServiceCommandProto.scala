package com.ing.hashingserviceclient.cli.domain

import com.ing.hashingserviceclient.cli.domain.FileContentProto.FileLine

object HashingServiceCommandProto {

  trait HashingServiceCommandProto

  final case object AbortJob extends HashingServiceCommandProto

  final case class SendHashingRequest(line: Array[FileLine]) extends HashingServiceCommandProto

  final case class GetHashFromCache(line: FileLine) extends HashingServiceCommandProto

  final case object EndSession extends HashingServiceCommandProto

}
