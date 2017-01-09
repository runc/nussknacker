package pl.touk.esp.ui.db

import pl.touk.esp.ui.db.entity.AttachmentEntity.AttachmentEntity
import pl.touk.esp.ui.db.entity.CommentEntity.CommentEntity
import pl.touk.esp.ui.db.entity.ProcessDeploymentInfoEntity.ProcessDeploymentInfoEntity
import pl.touk.esp.ui.db.entity.EnvironmentsEntity.EnvironmentsEntity
import pl.touk.esp.ui.db.entity.ProcessEntity.ProcessEntity
import pl.touk.esp.ui.db.entity.ProcessVersionEntity.ProcessVersionEntity
import pl.touk.esp.ui.db.entity.TagsEntity.TagsEntity
import slick.lifted.TableQuery

object EspTables {
  val processesTable = TableQuery[ProcessEntity]
  val processVersionsTable = TableQuery[ProcessVersionEntity]
  val deployedProcessesTable = TableQuery[ProcessDeploymentInfoEntity]
  val tagsTable = TableQuery[TagsEntity]
  val environmentsTable = TableQuery[EnvironmentsEntity]
  val commentsTable = TableQuery[CommentEntity]
  val attachmentsTable = TableQuery[AttachmentEntity]
}
