package com.ing.hashingserviceclient.cli.actors

import java.nio.file.{Files, Paths}
import java.util.concurrent.atomic.AtomicLong

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.util.ByteString
import com.ing.hashingserviceclient.cli.domain.FileContentProto.{FileContentProto, FileEnd, FileLine, FileStart}
import com.ing.hashingserviceclient.cli.domain.FileStreamProto.{CreateFileInputStream, FileStreamProto}
import com.ing.hashingserviceclient.cli.domain.HashingJobProto.{HashingJobProto, JobProcessError}
import com.ing.hashingserviceclient.cli.domain._


/**
 * <h1>FileStreamer Actor</h1>
 * The FileStreamer actor is responsible for streaming the lines of the input file to the {@link com.ing.hashingserviceclient.cli.actors.HashingServiceManager}
 * actor. It follows the protocol defined at {@link com.ing.hashingserviceclient.cli.domain.FileStreamProto}
 *
 * @author Saad Hashmi
 * @version 1.0
 * @since 2020-09-13
 */

object FileStreamer {
  private case class Chunk(length: Int, bytes: ByteString)

  /**
   *
   * @param jobManager
   * @param hashingServiceManager
   * @return
   */
  def apply(
      jobManager: ActorRef[HashingJobProto],
      hashingServiceManager: ActorRef[FileContentProto]
  ): Behavior[FileStreamProto] = {
        fileStreamer(jobManager, hashingServiceManager)

    }

  /**
   *
   * @param hashingServiceManager An ActorRef of {@link com.ing.hashingserviceclient.cli.actors.HashingServiceClient} actor.
   */
  private def sendFileStart(hashingServiceManager: ActorRef[FileContentProto]):() = {
    hashingServiceManager ! FileStart
  }

  /**
   *
   * @param jobManager An ActorRef of {@link com.ing.hashingserviceclient.cli.actors.HashingJobManager} actor.
   * @param hashingServiceManager An ActorRef of {@link com.ing.hashingserviceclient.cli.actors.HashingServiceManager} actor.
   * @return The updated Behaviour of this actor.
   */
  private def fileStreamer(
      jobManager: ActorRef[HashingJobProto],
      hashingServiceManager: ActorRef[FileContentProto]
  ): Behavior[FileStreamProto] = {
    Behaviors.receive { (context, message) =>
     implicit val ec = context.executionContext
      message match {
        case CreateFileInputStream(filename) =>
          if (Files.exists(Paths.get(filename))) {
            val stream  = Files.lines(Paths.get(filename))
            val indexer = new AtomicLong(0)
            hashingServiceManager ! FileStart
            stream.map(line => FileLine(indexer.incrementAndGet(), line)) forEach( hashingServiceManager ! _)
            hashingServiceManager ! FileEnd
            Behaviors.stopped
          } else {
            jobManager ! JobProcessError("File path is invalid!")
            Behaviors.stopped
          }
        // Here we know that AbortJob Cmd has been issued from Job Manager
        case FileStreamProto.AbortJob =>
          Behaviors.stopped

      }

    }
  }
}
