package com.ing.hashingserviceclient.cli.config

import java.net.InetAddress

import com.ing.hashingserviceclient.cli.domain.HashingServiceAddress
import com.typesafe.config.{Config, ConfigFactory}

/**
  * Application settings. First attempts to acquire from the deploy environment.
  * If not exists, then from -D java system properties, else a default config.
  *
  *   {{{
  *      hashing_service.address = "some.ip"
  *
  * }}}
  * Settings in the environment such as: HASHING_SERVICE_ADDRESS=SERVICE_BASE_ADDRESS/ENDPOINT is picked up first.
  * Settings from the command line in -D will override settings in the deploy environment.
  * For example:
  *
  *  {{{sbt -D hashing_service.address="http://localhost:9000/api/service" "run input.txt output.txt"}}}
  *
  * If you have not yet used Typesafe Config before, you can pass in overrides like so:
  *
  *  {{{
  *     new Settings(ConfigFactory.parseString("""some settings"""))
  *   }}}
  *
  * Any of these can also be overriden by your own application.conf.
  *
  * @param conf Optional config for test
  * */
final class AppSettings(conf: Option[Config] = None) extends Serializable {

  val localAddress = InetAddress.getLocalHost.getHostAddress

  val rootConfig = conf match {
    case Some(c) => c.withFallback(ConfigFactory.load)
    case _       => ConfigFactory.load
  }

  protected val hashing_cli_config = rootConfig.getConfig("hashing_service")

  val getHashServiceAddress       = (input: String) => HashingServiceAddress(input)
  val getFileBlockLength          = (input: Int) => input
  val getEitherHashServiceAddress = getEither[String, HashingServiceAddress](getHashServiceAddress) _
  val getEitherFileBlockLength    = getEither[Int, Int](getFileBlockLength) _

  val hashingServiceAddress: HashingServiceAddress = {
    getEitherHashServiceAddress(hashing_cli_config.getString("address"))
  } getOrElse (HashingServiceAddress(s"${localAddress}:9000/api/service"))

  val blockLength: Int = {
    getEitherFileBlockLength(
      hashing_cli_config.getInt("file_block_length")
    )
  } getOrElse 50

  /** Attempts to acquire from environment, then java system properties. */
  def getEither[A, B](foo: A => B)(input: A): Either[A, B] = {
    val result = input match {
      case null  => Left(input)
      case value => Right(foo(value))
    }
    result
  }
}
