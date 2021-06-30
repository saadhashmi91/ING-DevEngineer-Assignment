package com.ing.hashingserviceclient.cli.domain

object HashingServiceRequestResponseModel {

  final case class HashRequest(id: String, lines: Array[String])

  final case class HashResponse(id: String, lines: Array[String])

}
