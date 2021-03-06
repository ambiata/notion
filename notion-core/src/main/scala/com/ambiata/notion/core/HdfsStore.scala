package com.ambiata.notion.core

import java.io.{InputStream, OutputStream, PipedInputStream, PipedOutputStream}

import com.ambiata.mundane.control._
import com.ambiata.mundane.data._
import com.ambiata.mundane.io._
import com.ambiata.mundane.path._
import com.ambiata.poacher.hdfs._
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path => HPath}
import scodec.bits.ByteVector

import scala.io.Codec
import scalaz.{Store => _, _}, Scalaz._

case class HdfsStore(conf: Configuration, root: HdfsPath) extends Store[RIO] with ReadOnlyStore[RIO] {
  def readOnly: ReadOnlyStore[RIO] =
    this

  def list(prefix: Key): RIO[List[Key]] =
    hdfs {
      val p = keyToHdfsPath(prefix)
      p.onExists(p.listFilesRecursively.map(files =>
          files.flatMap(_.toHdfsPath.rebaseTo(root)).map(p => Key(p.path.names.toVector))), Hdfs.ok(Nil))
    }

  def filter(prefix: Key, predicate: Key => Boolean): RIO[List[Key]] =
    list(prefix).map(_.filter(predicate))

  def find(prefix: Key, predicate: Key => Boolean): RIO[Option[Key]] =
    list(prefix).map(_.find(predicate))

  /* Note: This was broken before, it would return true if Key was a directory */
  def exists(key: Key): RIO[Boolean] =
    hdfs { keyToHdfsPath(key).determine.map(_.cata(_.isLeft, false)) }

  def delete(key: Key): RIO[Unit] =
    hdfs { keyToHdfsPath(key).determineFile.flatMap(_.delete) }

  def deleteAll(prefix: Key): RIO[Unit] =
    hdfs { keyToHdfsPath(prefix).delete }

  /* Note: This use to work on dirs also, but it shouldn't have. */
  def move(in: Key, out: Key): RIO[Unit] =
    hdfs {
      keyToHdfsPath(in).determinef(
        f => f.move(keyToHdfsPath(out)).void
      , d => Hdfs.fail(s"Can not move key, not an object. ${d}").void)
    }

  /* Note: This use to work on dirs also, but it shouldn't have. */
  def copy(in: Key, out: Key): RIO[Unit] =
    hdfs {
      keyToHdfsPath(in).determinef(
        f => f.copy(keyToHdfsPath(out)).void
      , d => Hdfs.fail(s"Can not copy key, not an object. ${d}").void)
    }

  def moveTo(store: Store[RIO], src: Key, dest: Key): RIO[Unit] =
    copyTo(store, src, dest) >> delete(src)

  def copyTo(store: Store[RIO], src: Key, dest: Key): RIO[Unit] =
    unsafe.withInputStream(src) { in =>
      store.unsafe.withOutputStream(dest) { out =>
        Streams.pipe(in, out) }}

  def checksum(key: Key, algorithm: ChecksumAlgorithm): RIO[Checksum] =
    hdfs {
      val p = keyToHdfsPath(key)
      p.checksum(algorithm).flatMap(_.cata(
        Hdfs.ok
      , Hdfs.fail(s"Key does not exist. HdfsPath(${p.path})")))
    }

  val bytes: StoreBytes[RIO] = new StoreBytes[RIO] {
    def read(key: Key): RIO[ByteVector] =
      withInputStreamValue[Array[Byte]](key)(Streams.readBytes(_, 4 * 1024 * 1024)).map(ByteVector.view)

    def write(key: Key, data: ByteVector): RIO[Unit] =
      unsafe.withOutputStream(key)(Streams.writeBytes(_, data.toArray))
  }

  val strings: StoreStrings[RIO] = new StoreStrings[RIO] {
    def read(key: Key, codec: Codec): RIO[String] =
      bytes.read(key).map(b => new String(b.toArray, codec.name))

    def write(key: Key, data: String, codec: Codec): RIO[Unit] =
      bytes.write(key, ByteVector.view(data.getBytes(codec.name)))
  }

  val utf8: StoreUtf8[RIO] = new StoreUtf8[RIO] {
    def read(key: Key): RIO[String] =
      strings.read(key, Codec.UTF8)

    def write(key: Key, data: String): RIO[Unit] =
      strings.write(key, data, Codec.UTF8)
  }

  val lines: StoreLines[RIO] = new StoreLines[RIO] {
    def read(key: Key, codec: Codec): RIO[List[String]] =
      strings.read(key, codec).map(_.lines.toList)

    def write(key: Key, data: List[String], codec: Codec): RIO[Unit] =
      strings.write(key, Lists.prepareForFile(data), codec)
  }

  val linesUtf8: StoreLinesUtf8[RIO] = new StoreLinesUtf8[RIO] {
    def read(key: Key): RIO[List[String]] =
      lines.read(key, Codec.UTF8)

    def write(key: Key, data: List[String]): RIO[Unit] =
      lines.write(key, data, Codec.UTF8)
  }

  def withInputStreamValue[A](key: Key)(f: InputStream => RIO[A]): RIO[A] =
    hdfs { keyToHdfsPath(key).readWith(i => Hdfs.fromRIO(f(i))) }

  val unsafe: StoreUnsafe[RIO] = new StoreUnsafe[RIO] {
    def withInputStream(key: Key)(f: InputStream => RIO[Unit]): RIO[Unit] =
      withInputStreamValue[Unit](key)(f)

    def withOutputStream(key: Key)(f: OutputStream => RIO[Unit]): RIO[Unit] = for {
      e <- exists(key)
      _ <- RIO.when(e, RIO.fail(s"Can not overwrite key ${key}"))
      _ <- hdfs(keyToHdfsPath(key).writeWith(o => Hdfs.fromRIO(f(o))))
    } yield ()
  }

  def hdfs[A](thunk: => Hdfs[A]): RIO[A] =
    thunk.run(conf)

  def keyToHdfsPath(key: Key): HdfsPath =
    HdfsPath(Path.fromList(root.path, key.components.toList))
}
