package com.ing.hashingserviceclient.cli.utils

import com.ing.hashingserviceclient.cli.domain.FileContentProto.FileLine

object MinOrderSet extends Ordering[FileLine] {

  def compare(x: FileLine, y: FileLine) = x.lineNumber compare y.lineNumber

}
