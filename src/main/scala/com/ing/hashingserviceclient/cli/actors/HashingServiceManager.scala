package com.ing.hashingserviceclient.cli.actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import com.ing.hashingserviceclient.cli.config.AppSettings
import com.ing.hashingserviceclient.cli.domain.FileContentProto._
import com.ing.hashingserviceclient.cli.domain.FileWriteProto.FileWriteProto
import com.ing.hashingserviceclient.cli.domain.HashingJobProto.HashingJobProto
import com.ing.hashingserviceclient.cli.domain.HashingServiceCommandProto.{
  EndSession,
  GetHashFromCache,
  HashingServiceCommandProto,
  SendHashingRequest
}

import scala.collection.immutable.HashSet

/**
  *<h1>HashingServiceManager Actor</h1>
  * The HashingServiceManager actor is responsible for spawning configurable number of instances of {@link com.ing.hashingserviceclient.cli.actors.HashingServiceClient}
  * actors. It shards the incoming lines from {@link com.ing.hashingserviceclient.cli.actors.FileStreamer} actor to be sent to each instance of the child {@link com.ing.hashingserviceclient.cli.actors.HashingServiceClient} in ranged partitions. It follows the protocol defined at {@link com.ing.hashingserviceclient.cli.domain.FileContentProto}
  *
  * @author Saad Hashmi
  * @version 1.0
  * @since 2020-09-13
  */
object HashingServiceManager {

  val appSettings = new AppSettings()

  import appSettings._

  /**
    *
    * @param numLines The total number of lines to be hashed.
    * @param numClients Number of instances of {@link com.ing.hashingserviceclient.cli.actors.HashingServiceClient} actor to spawn.
    * @param jobManager An ActorRef of {@link com.ing.hashingserviceclient.cli.actors.HashingJobManager} actor.
    * @param fileWriter An ActorRef  of {@link com.ing.hashingserviceclient.cli.actors.FileWriter} actor.
    * @return The starting Behaviour of this actor.
    */
  def apply(
      numLines: Long,
      numClients: Int,
      jobManager: ActorRef[HashingJobProto],
      fileWriter: ActorRef[FileWriteProto],
      isTest: Boolean
  ): Behavior[FileContentProto] = {
    Behaviors.setup { ctx =>
      val clients = {
        for {
          i <- (1 to numClients)
          client = ctx.spawnAnonymous(
            HashingServiceClient(i, blockLength, hashingServiceAddress, jobManager, fileWriter, isTest)
          )

        } yield client
      }.toList

      val numLinesPerNode = (numLines / numClients).toInt
      def nodeHash(key: FileLine): Int = {
        if (key.lineNumber % numLinesPerNode > 0)
          (key.lineNumber / numLinesPerNode).toInt
        else ((key.lineNumber / numLinesPerNode) - 1).toInt
      }

      manageHashingSession(nodeHash, clients, HashSet.empty[String])
    }
  }

  /**
    *
    * @param sharder The sharding function
    * @param clients The list of child {@link com.ing.hashingserviceclient.cli.actors.HashingServiceClient} ActorRefs.
    * @param processedLines A cache of currently processed lines.
    * @return The running Behaviour of this actor.
    */
  private def manageHashingSession(
      sharder: FileLine => Int,
      clients: List[ActorRef[HashingServiceCommandProto]],
      processedLines: HashSet[String]
  ): Behavior[FileContentProto] = {
    Behaviors.receive { (context, message) =>
      message match {
        case FileStart =>
          Behaviors.same
        case line @ FileLine(_, lineStr) =>
          if (processedLines.contains(lineStr)) {
            val client = sharder(line)
            //println(s"Sending to client: ${client} with Address: ${clients(client)}")
            clients(client) ! GetHashFromCache(line)
            Behaviors.same
          } else {
            val client = sharder(line)
            //println(s"Sending to client: ${client} with Address: ${clients(client)}")
            clients(client) ! SendHashingRequest(Array(line))
            manageHashingSession(sharder, clients, processedLines + lineStr)
          }
        case FileEnd =>
          clients.foreach(_ ! EndSession)
          Behaviors.same

        case AbortJob => Behaviors.stopped

      }
    }
  }
}
