package com.ing.hashingserviceclient.cli.domain

import com.ing.hashingserviceclient.cli.domain.FileContentProto.FileLine

object FileWriteProto {

  sealed trait FileWriteProto

  final case object AbortJob extends FileWriteProto

  final case class SetFile(filename: String) extends FileWriteProto

  final case class WriteLinesToFile(lines: Array[FileLine]) extends FileWriteProto

  final case object CloseFile extends FileWriteProto

}
