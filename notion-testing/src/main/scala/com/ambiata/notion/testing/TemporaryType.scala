package com.ambiata.notion.testing

sealed trait TemporaryType

object TemporaryType {
  case object Posix extends TemporaryType
  case object S3 extends TemporaryType
  case object Hdfs extends TemporaryType
}
