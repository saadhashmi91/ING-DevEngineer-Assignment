package com.ing.hashingserviceclient.cli.domain

object HashingJobProto {

  sealed trait HashingJobProto extends Throwable

  final case object StartHashingJob extends HashingJobProto

  final case class JobProcessError(val message: String) extends HashingJobProto

  final case class JobProgress(linesProcessed: Long) extends HashingJobProto

  final case object Started extends HashingJobProto

  final case object Finished extends HashingJobProto

}
