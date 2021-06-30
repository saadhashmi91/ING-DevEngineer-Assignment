package com.ing.hashingserviceclient.cli.test

import java.math.BigInteger
import java.security.MessageDigest

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import com.ing.hashingserviceclient.cli.actors.HashingJobManager
import com.ing.hashingserviceclient.cli.domain.HashingJobProto._
import org.scalatest.wordspec.AnyWordSpecLike

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.io.Source
import java.io.PrintWriter

class HashingServiceCLISpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  private def md5HashString(s: String): String = {

    val md           = MessageDigest.getInstance("MD5")
    val digest       = md.digest(s.getBytes)
    val bigInt       = new BigInteger(1, digest)
    val hashedString = bigInt.toString(16)
    hashedString
  }

  "Hashing Actors Pipeline" must {

      "get final results as expected" in {

        val printWriter = new PrintWriter("output.txt")
        printWriter.print("")
        printWriter.close()

        val testProbe  = testKit.createTestProbe[HashingJobProto]()
        val jobManager = testKit.spawn(HashingJobManager("input.txt", "output.txt", true, Some(testProbe.ref)))
        @tailrec
        def testOutput(isDone: Boolean): Unit = {
          if (!isDone) {
            val message = testProbe.receiveMessage(100 seconds)
            message match {
              case JobProgress(_)     => testOutput(isDone = false)
              case JobProcessError(_) => testOutput(isDone = true)
              case Finished =>
                val lines  = Source.fromFile("input.txt").getLines.toList
                val output = Source.fromFile("output.txt").getLines.toList
                (lines.map(md5HashString) zip output) foreach {
                  case (expected, actual) =>
                    expected shouldBe actual
                }
                testOutput(isDone = true)

              case _ => testOutput(isDone = false)
            }
          }
        }

        jobManager ! StartHashingJob
        testOutput(false)

      }

    }

}
