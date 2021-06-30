package com.ing.hashingserviceclient.cli.domain

object FileStreamProto {

  sealed trait FileStreamProto

  final case object AbortJob extends FileStreamProto

  final case class CreateFileInputStream(filename: String) extends FileStreamProto

}
