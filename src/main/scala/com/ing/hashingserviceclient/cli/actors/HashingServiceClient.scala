package com.ing.hashingserviceclient.cli.actors

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import akka.actor.typed.{ActorRef, Behavior}
import com.ing.hashingserviceclient.cli.domain.FileContentProto.FileLine
import com.ing.hashingserviceclient.cli.domain.FileWriteProto.{FileWriteProto, WriteLinesToFile}
import com.ing.hashingserviceclient.cli.domain.HashingJobProto.{HashingJobProto, JobProcessError}
import com.ing.hashingserviceclient.cli.domain.HashingServiceCommandProto._
import com.ing.hashingserviceclient.cli.domain.HashingServiceRequestResponseModel.{HashRequest, HashResponse}
import com.ing.hashingserviceclient.cli.domain._
import com.ing.hashingserviceclient.cli.utils.MinOrder
import io.circe.generic.auto._
import io.circe.parser._
import org.mapdb.{DBMaker, HTreeMap, Serializer}
import sttp.client.circe._
import sttp.client.{basicRequest, HttpURLConnectionBackend, HttpURLConnectionBackendWithCustomStub, UriContext}

import scala.collection.mutable
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

/**
  * <h1>HashingServiceClient Actor</h1>
  * The HashingServiceClient actor is responsible for handling hashing requests sent by the {@link com.ing.hashingserviceclient.cli.actors.HashingServiceManager} parent actor.
  * It buffers received requests until the number of lines reaches the configured/adjusted block size.
  * The lifecycle of this actor is outlined as follows:
  * <ul>
  *   <li>Sends an HTTP POST request to the remote hashing service if the size of buffer reaches the configured/adjusted block size.</li>
  *   <li>If the received response from the remote service is 503 then reduces the block size by 50% and retries this step.</li>
  *   <li>If the received response from the remote service contains hashed lines then:
  *   <ul>
  *     <li>Sends them to the {@link com.ing.hashingserviceclient.cli.actors.FileWriter} actor.</li>
  *     <li>Clears the used buffer while retaining the unused buffer.</li>
  *     <li>Increases the block size by 10% as an optimistic behaviour.</li>
  *     <li>Goes to first step.</li>
  *  </ul>
  *  </li>
  *  <li>If a {@link com.ing.hashingserviceclient.cli.domain.HashingServiceCommandProto.EndSession} command is received:
  *  <ul>
  *    <li> If the current buffer is empty then stop. </li>
  *    <li> Else send {@link com.ing.hashingserviceclient.cli.domain.HashingServiceCommandProto.EndSession} command to self to consume again later.</li>
  *    <li> Resume from step 3 with the block size adjusted to the size of the buffer.</li>
  *  </ul>
  *  </li>
  * </ul>
  *
  * @author Saad Hashmi
  * @version 1.0
  * @since 2020-09-13
  */
object HashingServiceClient {

  // The type of the actor's internal messages.
  sealed private trait PrivateCommand extends HashingServiceCommandProto
  final private case class ProcessingException(error: JobProcessError) extends PrivateCommand
  final private case class ClientBuffer(
      blockLength: Int,
      currentBlock: mutable.PriorityQueue[FileLine],
      lastHashedLineNum: Long,
      cache: HTreeMap[String, String]
  ) extends PrivateCommand

  /**
    *
    * @param clientNum The instance number of this actor.
    * @param blockLength The number of lines to buffer before sending the request to the remote hashing service.
    * @param hashingServiceAddress The address of the remote hashing service.
    * @param jobManager An ActorRef of {@link com.ing.hashingserviceclient.cli.actors.HashingJobManager} actor.
    * @param fileWriter An ActorRef of {@link com.ing.hashingserviceclient.cli.actors.FileWriter} actor.
    * @param useStub A boolean variable to indicate whether to use a stub for the remote hashing service. Set to 'True' for testing.
    * @return The starting Behaviour of this actor.
    */
  def apply(
      clientNum: Int,
      blockLength: Int,
      hashingServiceAddress: HashingServiceAddress,
      jobManager: ActorRef[HashingJobProto],
      fileWriter: ActorRef[FileWriteProto],
      useStub: Boolean
  ): Behavior[HashingServiceCommandProto] = {
    Behaviors.withStash(100) { buffer =>
      Behaviors.setup[HashingServiceCommandProto] { context =>
        {
          implicit val ordering = MinOrder
          val db                = DBMaker.memoryDB().make().hashMap("map", Serializer.STRING, Serializer.STRING).createOrOpen

          new HashingServiceClient(
            buffer,
            context,
            hashingServiceAddress,
            jobManager,
            fileWriter,
            useStub
          ).buildFileBlock(
            ClientBuffer(blockLength, mutable.PriorityQueue.empty[FileLine], 0, db)
          )
        } narrow
      }
    }

  }
}

/**
  *
  * @param stash The currently used {@link akka.actor.typed.scaladsl.StashBuffer}.
  * @param context The {@link akka.actor.typed.scaladsl.ActorContext} of this actor.
  * @param hashingServiceAddress The HTTP address of the remote hashing service.
  * @param jobManager An ActorRef of {@link com.ing.hashingserviceclient.cli.actors.HashingJobManager} actor.
  * @param fileWriter An ActorRef of {@link com.ing.hashingserviceclient.cli.actors.FileWriter} actor.
  */
class HashingServiceClient(
    stash: StashBuffer[HashingServiceCommandProto],
    context: ActorContext[HashingServiceCommandProto],
    hashingServiceAddress: HashingServiceAddress,
    jobManager: ActorRef[HashingJobProto],
    fileWriter: ActorRef[FileWriteProto],
    useStub: Boolean
)(implicit val ordering: Ordering[FileLine]) {
  import HashingServiceClient._

  /**
    *
    * @param clientState The currently maintained client buffer.
    * @return The starting Behaviour of this actor.
    */
  def buildFileBlock(clientState: ClientBuffer): Behavior[HashingServiceCommandProto] = {
    Behaviors.receiveMessage {
      case GetHashFromCache(line) =>
        if (clientState.cache.containsKey(line)) {
          // // println("Entered CacheRequest")
          fileWriter ! WriteLinesToFile(Array(line.copy(lineStr = clientState.cache.get(line).asInstanceOf[String])))
          Behaviors.same
        } else {
          val updatedState = clientState.copy(currentBlock = clientState.currentBlock.addOne(line))
          sendHashingRequestImpl(updatedState)
        }
      case SendHashingRequest(lines) =>
        // // println("Entered SendHashingRequest")
        val updatedState = clientState.copy(currentBlock = clientState.currentBlock.addAll(lines))
        sendHashingRequestImpl(updatedState)

      case ProcessingException(error) =>
        jobManager ! error
        Behaviors.stopped

      case EndSession =>
        // println("Entered EndSessionRequest")
        if (clientState.currentBlock.size > 0) {

          // Buffer is not emptied yet so send hash request for remaining lines and also send EndSession request to consume again later
          context.self ! EndSession
          sendHashingRequestImpl(clientState.copy(blockLength = clientState.currentBlock.size))

        } else {
          // println(s"All done..Last hashed line sent: ${clientState.lastHashedLineNum}")
          clientState.cache.close()
          Behaviors.stopped
        }
      case AbortJob =>
        clientState.cache.close()
        Behaviors.stopped

      case other =>
        // println("Entered Other")
        Behaviors.same
    }
  }

  /**
    *
    * @param clientBuffer The currently maintained client buffer.
    * @return The updated Behaviour of this actor.
    */
  private def sendHashingRequestImpl(clientBuffer: ClientBuffer): Behavior[HashingServiceCommandProto] = {

    if (clientBuffer.currentBlock.size >= clientBuffer.blockLength) {
      val handledRequest = handleHashRequest(clientBuffer)
      //stash.stash(handledRequest)
      handledRequest match {
        case c @ ClientBuffer(_, _, _, _) =>
          buildFileBlock(c)
        case other =>
          // println("Entered other")
          buildFileBlock(
            ClientBuffer(
              clientBuffer.currentBlock.size,
              mutable.PriorityQueue.empty[FileLine],
              clientBuffer.lastHashedLineNum,
              clientBuffer.cache
            )
          )

      }
    } else buildFileBlock(clientBuffer)

  }

  /**
    *
    * @param clientState The currently maintained client buffer.
    * @return The next command depending on the response from the remote hashing service.
    */
  private def handleHashRequest(clientState: ClientBuffer): HashingServiceCommandProto = {

    val (usedState, leftState) = takeBlockFromState(clientState)
    fetchHash(hashingServiceAddress, usedState) match {
      case Success(result) =>
        handleFetchResult(
          clientState.cache,
          result,
          usedState.currentBlock,
          leftState.currentBlock,
          usedState.blockLength
        )
      case Failure(exception) =>
        // println(s"503 for blockLength: ${usedState.blockLength}")
        handleFetchException(exception.asInstanceOf[JobProcessError], clientState)
    }
  }

  /**
    *
    * @param hashingServiceAddress The HTTP address of the remote hashing service.
    * @param clientState The currently maintained client buffer.
    * @return Array of hashed lines.
    */
  private def fetchHash(
      hashingServiceAddress: HashingServiceAddress,
      clientState: ClientBuffer
  ): Try[Array[FileLine]] = {
    // println(
    //   "Sending FetchHash for start: " + clientState.currentBlock.head.lineNumber + " and end: " + clientState.currentBlock.last.lineNumber
    // )

    implicit val sttpBackend = {
      if (useStub) new HttpURLConnectionBackendWithCustomStub(HttpURLConnectionBackend()).stub
      else new HttpURLConnectionBackendWithCustomStub(HttpURLConnectionBackend())

    }
    val requestPayload = HashRequest("job-1", clientState.currentBlock.map(_.lineStr).toArray)
    // println("Request Payload --> \n\t" + requestPayload.asJson.noSpaces)
    val response = basicRequest.post(uri"${hashingServiceAddress.address}").body(requestPayload).send()
    if (response.code.code != 503) {
      val output: Try[Array[FileLine]] = response.body match {
        case Left(error) => Failure(JobProcessError(error))
        case Right(result) =>
          val out = for {
            parsedJson <- parse(result)
            hashedLine <- parsedJson.as[HashResponse]
            hashedLinesWithIndex = hashedLine.lines
              .zip(clientState.currentBlock.map(_.lineNumber)).map(
                zipped => FileLine(zipped._2, zipped._1)
              )
          } yield hashedLinesWithIndex
          out match {
            case Left(error)  => Failure(JobProcessError(error.getMessage))
            case Right(value) => Success(value)
          }
      }
      output
    } else {
      // Case when "503" error received
      Failure(JobProcessError("503"))

    }
  }

  /**
    *
    * @param exception HTTP Protocol exception.
    * @param clientBuffer The currently maintained client buffer.
    * @return  The next command depending on the response from the remote hashing service.
    */
  private def handleFetchException(
      exception: JobProcessError,
      clientBuffer: ClientBuffer
  ): HashingServiceCommandProto = {
    // If error is "503" we know the error is from remote hashing server so we can try again
    if (exception.message == "503") {
      ClientBuffer(
        math.ceil(clientBuffer.blockLength * 0.5).toInt,
        clientBuffer.currentBlock,
        clientBuffer.lastHashedLineNum,
        clientBuffer.cache
      )
    }
    // Otherwise there is some processing error on our side which is to be reported to Job Manager and Behaviour is to stop immediately.
    else {
      ProcessingException(JobProcessError(exception.getMessage))
    }
  }

  /**
    *
    * @param cache The currently maintained off-heap cache of hashed lines.
    * @param hashResult Array of hashed lines.
    * @param usedBlock The used client buffer.
    * @param leftBlock The unused client buffer.
    * @param currentBlockLength Current block size.
    * @return  The next command depending on the response from the remote hashing service.
    */
  private def handleFetchResult(
      cache: HTreeMap[String, String],
      hashResult: Array[FileLine],
      usedBlock: mutable.PriorityQueue[FileLine],
      leftBlock: mutable.PriorityQueue[FileLine],
      currentBlockLength: Int
  ): HashingServiceCommandProto = {

    // Add line numbers to the hashed lines using the usedBlock
    val indexedLines = usedBlock
      .zip(hashResult.map(_.lineStr)).map(
        pair =>
          pair match {
            case (fileLine, hashedStr) =>
              cache.put(fileLine.lineStr, hashedStr)
              FileLine(fileLine.lineNumber, hashedStr)
          }
      )
    // If result length is > 0 only then send to downstream actor
    if (hashResult.length > 0) {
      fileWriter ! WriteLinesToFile(indexedLines.toArray)
    }
    // Process the buffer/lines left after taking the first "currentBlockLength" number of lines
    // Also try to increase "currentBlockLength" by 10 percent as an optimistic behaviour
    ClientBuffer(math.ceil(currentBlockLength * 1.1).toInt, leftBlock, hashResult.last.lineNumber, cache)

  }

  /**
    * @param clientBuffer The currently maintained client buffer.
    * @return Returns the split client buffer.
    * */
  private def takeBlockFromState(clientBuffer: ClientBuffer): (ClientBuffer, ClientBuffer) = {
    Try {
      val takenState = clientBuffer.copy(currentBlock = clientBuffer.currentBlock.take(clientBuffer.blockLength))
      val leftState  = clientBuffer.copy(currentBlock = clientBuffer.currentBlock.drop(clientBuffer.blockLength))

      (takenState, leftState)
    } match {
      case Success(value) => value
      case Failure(error) =>
        // println(error)
        throw error
    }
  }

}
