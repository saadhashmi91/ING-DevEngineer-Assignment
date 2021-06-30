package com.ing.hashingserviceclient.cli.actors

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import com.ing.hashingserviceclient.cli.config.AppSettings
import com.ing.hashingserviceclient.cli.domain.FileContentProto.FileContentProto
import com.ing.hashingserviceclient.cli.domain.FileStreamProto.{CreateFileInputStream, FileStreamProto}
import com.ing.hashingserviceclient.cli.domain.FileWriteProto.{FileWriteProto, SetFile}
import com.ing.hashingserviceclient.cli.domain.HashingJobProto.{HashingJobProto, _}
import com.ing.hashingserviceclient.cli.domain._
import com.ing.hashingserviceclient.cli.utils.ProgressBar

import scala.util.{Failure, Success, Try}

/**
  * <h1>HashingJobManager Actor</h1>
  * The HashingJobManager actor is responsible for driving the complete hashing job. The lifecycle of this actor is outlined as follows:
  * <ul>
  *   <li>Starts the {@link com.ing.hashingserviceclient.cli.actors.FileStreamer} child actor.</li>
  *   <li>Starts the {@link com.ing.hashingserviceclient.cli.actors.HashingServiceManager} child actor.</li>
  *   <li>Starts the {@link com.ing.hashingserviceclient.cli.actors.FileWriter} child actor.</li>
  *   <li>Waits for hashing job events as defined at {@link com.ing.hashingserviceclient.cli.domain.HashingJobProto}</li>
  * </ul>
  *
  * @author Saad Hashmi
  * @version 1.0
  * @since 2020-09-13
  */
object HashingJobManager {
  val appSettings = new AppSettings()

  // Adapted from https://alvinalexander.com/scala/source-code-for-scala-line-count-functions/
  private def countLines3(filename: String): Try[Long] = {
    val NEWLINE      = 10
    var newlineCount = 0L
    var source: scala.io.BufferedSource = null
    Try {
      source = scala.io.Source.fromFile(filename)
      for (_ <- source.getLines) {
        newlineCount += 1
      }
      newlineCount
    } match {
      case Success(value) =>
        if (source != null) source.close
        Success(value)
      case Failure(exception) => Failure(exception)
    }
  }

  /**
    *
    * @param inputFile The file path containing the lines to hash
    * @param outputFile The file path where to write the hashed lines.
    * @return The starting Behaviour of this actor
    */
  def apply(
      inputFile: String,
      outputFile: String,
      isTest: Boolean,
      replyTo: Option[ActorRef[HashingJobProto]]
  ): Behavior[HashingJobProto] = {
    Behaviors.setup[HashingJobProto] { context =>
      println("Started Job Manager")
      //Count number of Lines of InputFile
      val totalLinesCount = countLines3(inputFile) match {
        case Success(value) => value
        case Failure(exception) =>
          println(exception.getMessage)
          Behaviors.stopped
      }
      val jobManagerActorRef = if (isTest) replyTo.get else context.self
      val fileWriter         = context.spawn(FileWriter(jobManagerActorRef, totalLinesCount.asInstanceOf[Long]), "FileWriter")

      val hashingServiceManager = context.spawn(
        HashingServiceManager(
          numClients = 10,
          numLines = totalLinesCount.asInstanceOf[Long],
          jobManager = jobManagerActorRef,
          fileWriter = fileWriter,
          isTest = isTest
        ),
        "HashingServiceManager"
      )
      val fileStreamer = context.spawn(FileStreamer(context.self, hashingServiceManager), "FileStreamer")

      new HashingJobManager(
        context,
        inputFile,
        new ProgressBar(totalLinesCount.asInstanceOf[Long].toInt),
        outputFile,
        fileStreamer,
        hashingServiceManager,
        fileWriter
      ).manageHashingJob()
    }
  }
}

/**
  *
  * @param context The current actor's {@link akka.actor.typed.scaladsl.ActorContext}
  * @param inputFile  The file path containing the lines to hash
  * @param pb An instance of {@link com.ing.hashingserviceclient.cli.utils.ProgressBar}
  * @param outputFile The file path where to write the hashed lines.
  * @param fileStreamer An ActorRef of {@link com.ing.hashingserviceclient.cli.actors.FileStreamer} actor.
  * @param hashingServiceManager An ActorRef of {@link com.ing.hashingserviceclient.cli.actors.HashingServiceManager} actor.
  * @param fileWriter An ActorRef of {@link com.ing.hashingserviceclient.cli.actors.FileWriter} actor.
  */
class HashingJobManager(
    context: ActorContext[HashingJobProto],
    inputFile: String,
    var pb: ProgressBar,
    outputFile: String,
    fileStreamer: ActorRef[FileStreamProto],
    hashingServiceManager: ActorRef[FileContentProto],
    fileWriter: ActorRef[FileWriteProto]
) {

  /**
    *
    * @return The starting Behaviour of this actor.
    */
  def manageHashingJob(): Behavior[HashingJobProto] = {
    Behaviors.receiveMessage {
      case StartHashingJob =>
        // Tell FileWriter to open output file.
        fileWriter ! SetFile(outputFile)
        // Tell FileStreamer to start steaming contents from input file.
        fileStreamer ! CreateFileInputStream(inputFile)
        //Now wait for job processing status events
        pb += 0
        Behaviors.same

      case JobProgress(linesProcessed) =>
        pb.setCurrent(linesProcessed.toInt)
        Behaviors.same
      case Started =>
        Behaviors.same

      case Finished =>
        Behaviors.stopped
      //Handle stopping actor contexts

      case JobProcessError(error) =>
        context.log.error(error)
        println("Got error:" + error)
        // Notify all children to abort job

        fileStreamer ! FileStreamProto.AbortJob
        hashingServiceManager ! FileContentProto.AbortJob
        //hashingServiceClient ! HashingServiceCommandProto.AbortJob
        fileWriter ! FileWriteProto.AbortJob
        Behaviors.stopped
    }

  }

}
