package com.ing.hashingserviceclient.cli.actors

import java.io.BufferedWriter
import java.nio.file.Paths

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import com.ing.hashingserviceclient.cli.domain.FileContentProto.FileLine
import com.ing.hashingserviceclient.cli.domain.FileWriteProto._
import com.ing.hashingserviceclient.cli.domain.HashingJobProto.{Finished, HashingJobProto, JobProgress}
import com.ing.hashingserviceclient.cli.utils.MinOrder

import scala.collection.mutable.{ArrayBuffer, PriorityQueue}
import scala.util.{Failure, Success, Try}

/**
  * <h1>FileWriter Actor</h1>
  * The FileWriter actor is responsible for ordering and appending the hashed lines received from the {@link com.ing.hashingserviceclient.cli.actors.HashingServiceClient}
  * actor(s) to the output file. The ordering is done by internally buffering the out-of-order hashed lines in a {@link scala.collection.mutable.PriorityQueue}. It follows the protocol defined at {@link com.ing.hashingserviceclient.cli.domain.FileWriteProto}.
  * The working of the file writing process is outlined as follows:
  *<ul>
  *   <li> The received hashed lines are only written/appended after matching each line number with a counter to keep track of the last written line number.The counter is also incremented to the last line number of the received lines.</li>
  *   <li> If the received hashed lines have the ending line number immediately leading the head of the
  *   {@link scala.collection.mutable.PriorityQueue} then the lines from the queue are written up to the last matching consecutive line number of the incrementing counter.</li>
  *   <li> If the counter becomes equal to the total number of lines, then the actor closes the output file and stops.</li>
  * </ul>
  * @author Saad Hashmi
  * @version 1.0
  * @since 2020-09-13
  */
object FileWriter {

  type FileWriterType = java.io.FileWriter

  /**
    *
    * @param jobManager The ActorRef of {@link com.ing.hashingserviceclient.cli.actors.HashingJobManager} actor.
    * @param totalLines The total number of lines to be hashed.
    * @return The starting Behavior of this actor.
    */
  def apply(jobManager: ActorRef[HashingJobProto], totalLines: Long): Behavior[FileWriteProto] = {

    handleWriteOps(jobManager, totalLines)
  }

  /**
    * This method uses the current index and the currently used {@link scala.collection.mutable.PriorityQueue} to write matching consecutive lines from the queue.
    * @param indexCounter The line number of the last written hashed line
    * @param minHeap The instance of the currently used {@link scala.collection.mutable.PriorityQueue}
    * @param writer An instance of the currently used {@link java.io.BufferedWriter}
    * @return The new index after writing the lines
    */
  private def writeLinesFromBuffer(
      indexCounter: Long,
      minHeap: PriorityQueue[FileLine],
      writer: BufferedWriter
  ): Long = {
    Try {
      val linesToAppend = ArrayBuffer[FileLine]()
      val itr           = LazyList.from(indexCounter.toInt + 1).iterator
      while (minHeap.nonEmpty && itr.next() == minHeap.head.lineNumber) linesToAppend += minHeap.dequeue()

      if (linesToAppend.length > 0) {
        writer.append((linesToAppend.map(_.lineStr) mkString System.lineSeparator()) + System.lineSeparator())
        writer.flush()
        linesToAppend.last.lineNumber
      } else indexCounter
    } match {
      case Success(value) =>
        // println(s"New Index value: ${value}")
        value
      case Failure(exception) =>
        println(exception)
        throw exception
    }

  }

  /**
    *
    * @param jobManager An ActorRef of {@link com.ing.hashingserviceclient.cli.actors.HashingJobManager} actor.
    * @param totalLines The total number of lines to be hashed.
    * @return The running Behavior of this actor.
    */
  private def handleWriteOps(jobManager: ActorRef[HashingJobProto], totalLines: Long): Behavior[FileWriteProto] = {
    Behaviors.receive { (context, message) =>
      message match {
        case SetFile(filename) =>
          val path = Paths.get(filename)
          handleWriteOps(
            new BufferedWriter(new FileWriterType(filename, true)),
            0L,
            totalLines,
            PriorityQueue.empty(MinOrder),
            jobManager
          )
        case AbortJob => Behaviors.stopped
      }
    }
  }

  /**
    *
    * @param writer An instance of the currently used {@link java.io.BufferedWriter}
    * @param indexCounter The line number of the last written hashed line
    * @param totalLines The total number of lines to be hashed.
    * @param minHeap The instance of the currently used {@link scala.collection.mutable.PriorityQueue}
    * @param jobManager An ActorRef of {@link com.ing.hashingserviceclient.cli.actors.HashingJobManager} actor.
    * @return The updated Behavior of this actor.
    */
  private def handleWriteOps(
      writer: BufferedWriter,
      indexCounter: Long,
      totalLines: Long,
      minHeap: PriorityQueue[FileLine],
      jobManager: ActorRef[HashingJobProto]
  ): Behavior[FileWriteProto] = {
    Behaviors.receive { (context, message) =>
      message match {
        case WriteLinesToFile(lines) =>
          // The index counter is equal to starting index of the hashed lines received
          if (lines.head.lineNumber == (indexCounter + 1)) {
            //.map(_.lineStr)
            writer.append((lines.map(_.lineStr) mkString System.lineSeparator()) + System.lineSeparator())
            writer.flush()
            if (minHeap.nonEmpty && lines.last.lineNumber == (minHeap.head.lineNumber - 1)) {
              val newIndex = writeLinesFromBuffer(lines.last.lineNumber, minHeap, writer)
              jobManager ! JobProgress(newIndex)
              if (newIndex == totalLines) context.self ! CloseFile
              handleWriteOps(writer, newIndex, totalLines, minHeap, jobManager)
            } else {
              if (lines.last.lineNumber == totalLines) context.self ! CloseFile
              jobManager ! JobProgress(lines.last.lineNumber)
              handleWriteOps(writer, lines.last.lineNumber, totalLines, minHeap, jobManager)
            }
          } else if (minHeap.nonEmpty && indexCounter == minHeap.head.lineNumber - 1) {
            val newIndex = writeLinesFromBuffer(indexCounter, minHeap.addAll(lines), writer)
            if (newIndex == totalLines) context.self ! CloseFile
            jobManager ! JobProgress(newIndex)
            handleWriteOps(writer, newIndex, totalLines, minHeap, jobManager)
          } else {
            // jobManager ! JobProgress(indexCounter)
            if (indexCounter == totalLines) context.self ! CloseFile
            handleWriteOps(writer, indexCounter, totalLines, minHeap.addAll(lines), jobManager)
          }

        case CloseFile =>
          println("")
          println("File Write Done")
          writer.close()
          jobManager ! Finished
          Behaviors.stopped

        case AbortJob =>
          writer.close()
          Behaviors.stopped
      }
    }
  }
}
