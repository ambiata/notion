package com.ambiata.notion.distcopy

import com.ambiata.mundane.control.RIO
import com.ambiata.mundane.io._
import com.ambiata.notion.core._
import com.ambiata.poacher.hdfs.Hdfs
import com.ambiata.saws.s3._
import org.apache.hadoop.fs._

import scalaz.Scalaz._

/**
 * Copy functions between Hdfs and S3
 */
object DistCopy {

  /**
   * Download a large directory by doing a distcopy from s3 to a hdfs directory
   *
   * Don't copy files which have already been copied
   */
  def downloadDirectory(from: S3Location, to: HdfsLocation, locationIO: LocationIO, parameters: DistCopyParameters): RIO[List[Location]] =
    for {
      fromAddresses <- S3Prefix(from.bucket, from.key).listAddress.execute(locationIO.s3Client)
      mappings      <- fromAddresses.traverseU(createDownloadMapping(to, locationIO))
      _             <- DistCopyJob.run(Mappings(mappings.toVector.flatten), distCopyConfiguration(locationIO, parameters))
    } yield fromAddresses.map { case S3Address(b, k) => S3Location(b, k) }

  /** upload a large directory by doing a distcopy from hdfs to s3 */
  def uploadDirectory(from: HdfsLocation, to: S3Location, locationIO: LocationIO, parameters: DistCopyParameters): RIO[List[Location]] =
    for {
      fromPaths <- Hdfs.globFilesRecursively(new Path(from.path)).run(locationIO.configuration)
      mappings  <- fromPaths.traverseU(createUploadMapping(to, locationIO))
      _         <- DistCopyJob.run(Mappings(mappings.toVector.flatten), distCopyConfiguration(locationIO, parameters))
    } yield fromPaths.map(p => HdfsLocation(p.toString))

  /** @return true if the path exists */
  def pathExist(path: Path, locationIO: LocationIO): RIO[Boolean] =
    Hdfs.exists(path).run(locationIO.configuration)

  /** @return true if the address exists */
  def addressExist(address: S3Address, locationIO: LocationIO): RIO[Boolean] =
    address.exists.execute(locationIO.s3Client)

  /** @return a configuration for dist copy based on the current LocationIO configuration */
  def distCopyConfiguration(locationIO: LocationIO, parameters: DistCopyParameters): DistCopyConfiguration =
    DistCopyConfiguration(
        hdfs = locationIO.configuration,
        client = locationIO.s3Client,
        parameters)

  /** create a Download mapping for a file if it doesn't exist */
  def createDownloadMapping(to: HdfsLocation, locationIO: LocationIO)(address: S3Address): RIO[Option[DownloadMapping]] = {
    val toPath = new Path((to.dirPath </> FilePath.unsafe(address.key)).path)
    pathExist(toPath, locationIO).flatMap { exists =>
      if (exists) RIO.putStrLn(s"a file already exists at $toPath, $address won't be downloaded again") >> RIO.ok(None)
      else        RIO.ok(Some(DownloadMapping(address, toPath)))
    }
  }

  /** create an Upload mapping for a file, but fail if it already exist to make sure we don't override anything by accident */
  def createUploadMapping(to: S3Location, locationIO: LocationIO)(path: Path): RIO[Option[UploadMapping]] = {
    val toAddress = S3Address(to.bucket, to.key + path.toUri.getPath)
    addressExist(toAddress, locationIO).flatMap { exists =>
      if (exists) RIO.fail(s"${toAddress.render} exists already and cannot be overwritten")
      else        RIO.ok(Some(UploadMapping(path, toAddress)))
    }
  }
}

