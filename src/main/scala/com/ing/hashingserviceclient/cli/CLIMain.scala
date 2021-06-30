package com.ing.hashingserviceclient.cli
import akka.actor.typed.ActorSystem
import com.ing.hashingserviceclient.cli.actors.HashingJobManager
import com.ing.hashingserviceclient.cli.domain.HashingJobProto.{HashingJobProto, StartHashingJob}

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

object CLIMain extends App {

  private def printHelp(): Unit = {

    println("usage: compute-hash ./input.txt ./output.txt")
  }

  if (args.length == 2) {

    val inputFile  = args(0)
    val outputFile = args(1)

    // Create Actor System
    val system: ActorSystem[HashingJobProto] =
      ActorSystem(HashingJobManager(inputFile, outputFile, false, None), "HashingJobManager")
    system ! StartHashingJob

    system.getWhenTerminated thenRun (() => sys.exit(0))
    Await.result(system.whenTerminated, 1000 seconds)

  } else printHelp()

}
