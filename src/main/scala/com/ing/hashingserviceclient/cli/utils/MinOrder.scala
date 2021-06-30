package com.ing.hashingserviceclient.cli.utils

import com.ing.hashingserviceclient.cli.domain.FileContentProto.FileLine

object MinOrder extends Ordering[FileLine] {
  def compare(x: FileLine, y: FileLine) = y.lineNumber compare x.lineNumber
}
