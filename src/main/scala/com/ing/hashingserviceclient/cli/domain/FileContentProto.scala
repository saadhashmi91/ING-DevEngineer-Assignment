package com.ing.hashingserviceclient.cli.domain

object FileContentProto {

  sealed trait FileContentProto

  final case object AbortJob extends FileContentProto

  final case class FileLine(lineNumber: Long, lineStr: String) extends FileContentProto

  final case object FileStart extends FileContentProto

  final case object FileEnd extends FileContentProto

}
