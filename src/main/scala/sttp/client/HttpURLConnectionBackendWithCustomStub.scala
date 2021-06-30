package sttp.client

import java.math.BigInteger
import java.security.MessageDigest

import com.ing.hashingserviceclient.cli.domain.HashingServiceRequestResponseModel.{HashRequest, HashResponse}
import io.circe.generic.auto._
import io.circe.parser.parse
import io.circe.syntax._
import sttp.client.monad.MonadError
import sttp.client.testing.SttpBackendStub
import sttp.client.ws.WebSocketResponse
import sttp.model.{Method, StatusCode}

import scala.util.Random

class HttpURLConnectionBackendWithCustomStub(delegate: SttpBackend[Identity, Nothing, NothingT])
    extends SttpBackend[Identity, Nothing, NothingT] {

  override def send[T](r: Request[T, Nothing]): Response[T]          = delegate.send(r)
  override def responseMonad:                   MonadError[Identity] = delegate.responseMonad
  override def openWebsocket[T, WR](
      request: Request[T, Nothing],
      handler: NothingT[WR]
  ): NothingT[WebSocketResponse[WR]] =
    handler

  override def close(): Unit = {}

  private def md5HashString(s: String): String = {

    val md           = MessageDigest.getInstance("MD5")
    val digest       = md.digest(s.getBytes)
    val bigInt       = new BigInteger(1, digest)
    val hashedString = bigInt.toString(16)
    hashedString
  }

  def stub: SttpBackendStub[Identity, Nothing, NothingT] =
    SttpBackendStub.synchronous.whenRequestMatches(r => r.method == Method.POST).thenRespondWrapped { r =>
      val response = r.body match {
        case StringBody(s, _, _) =>
          for {
            parsed       <- parse(s)
            hashRequest  <- parsed.as[HashRequest]
            hashResponse <- Right(HashResponse(hashRequest.id, hashRequest.lines.map(md5HashString)).asJson.noSpaces)
          } yield hashResponse
      }

      // Simulate '503 error' 10% of the time
      if (Random.nextInt(10) < 1)
        Response(Left("503"), StatusCode.ServiceUnavailable, "", Nil, Nil)
      else Response(Right(response.right.get), StatusCode.Ok, "", Nil, Nil)

    }

}
