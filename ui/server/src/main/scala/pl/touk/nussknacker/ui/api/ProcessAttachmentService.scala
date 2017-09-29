package pl.touk.nussknacker.ui.api

import java.io.File

import akka.stream.Materializer
import akka.stream.scaladsl.{FileIO, Source}
import akka.util.ByteString
import com.typesafe.scalalogging.LazyLogging
import pl.touk.nussknacker.ui.api.ProcessAttachmentService.AttachmentToAdd
import pl.touk.nussknacker.ui.db.entity.AttachmentEntity.AttachmentEntityData
import pl.touk.nussknacker.ui.process.repository.ProcessActivityRepository
import pl.touk.nussknacker.ui.security.api.LoggedUser
import pl.touk.nussknacker.ui.util.CatsSyntax

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

class ProcessAttachmentService(attachmentsBasePath: String, processActivityRepository: ProcessActivityRepository) extends LazyLogging {

  def saveAttachment(processId: String, processVersionId: Long, originalFileName: String, byteSource: Source[ByteString, Any])
                    (implicit ec: ExecutionContext, loggedUser: LoggedUser, mat: Materializer): Future[Unit] = {
    val relativeFilePath = s"$processId/${s"${System.currentTimeMillis()}-$originalFileName"}"
    val attachmentFile = getAttachmentFile(relativeFilePath)
    attachmentFile.getParentFile.mkdirs()
    val fileSink = FileIO.toFile(attachmentFile)
    byteSource.runWith(fileSink).flatMap { _ =>
      val attachmentToAdd = AttachmentToAdd(processId, processVersionId, originalFileName, relativeFilePath)
      processActivityRepository.addAttachment(attachmentToAdd).recoverWith { case NonFatal(ex) =>
        logger.warn(s"Failure during writing attachment to db. Removing file ${attachmentFile}", ex)
        attachmentFile.delete()
        Future.failed(ex)
      }
    }
  }

  def readAttachment(attachmentId: Long)
                    (implicit ec: ExecutionContext, loggedUser: LoggedUser): Future[Option[(File, AttachmentEntityData)]] = {
    val attachmentFutureOpt = processActivityRepository.findAttachment(attachmentId)
    CatsSyntax.futureOpt.map(attachmentFutureOpt) { attachment =>
      (getAttachmentFile(attachment.filePath), attachment)
    }
  }

  private def getAttachmentFile(attachmentRelativePath: String): File = {
    new File(attachmentsBasePath, attachmentRelativePath)
  }

}

object ProcessAttachmentService {

  case class AttachmentToAdd(processId: String,
                             processVersionId: Long,
                             fileName: String,
                             relativeFilePath: String
                            )

}