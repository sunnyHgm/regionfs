package regionfs

import java.io.{ByteArrayInputStream, File, FileInputStream, FileOutputStream}

import cn.bluejoe.util.Profiler
import cn.bluejoe.regionfs.FileId
import cn.bluejoe.regionfs.client.FsClient

import scala.concurrent.Future

/**
  * Created by bluejoe on 2019/8/23.
  */
class FileTestBase {
  Profiler.enableTiming = true

  //run 3 processes first:
  // StartSingleTestServer ./node1.conf
  // StartSingleTestServer ./node2.conf
  // StartSingleTestServer ./node3.conf
  val server = NodeServerForTest.server
  val client = new FsClient("localhost:2181")

  def writeFile(src: File): FileId = {
    val fid = client.writeFile(
      new FileInputStream(src), src.length)
    fid
  }

  def writeFile(text: String): FileId = {
    writeFile(text.getBytes)
  }

  private def writeFile(bytes: Array[Byte]): FileId = {
    val fid = client.writeFile(
      new ByteArrayInputStream(bytes), bytes.length)
    fid
  }

  private def writeFileAsync(bytes: Array[Byte]): Future[FileId] = {
    val fid = client.writeFileAsync(
      new ByteArrayInputStream(bytes), bytes.length)
    fid
  }

  def writeFileAsync(text: String): Future[FileId] = {
    writeFileAsync(text.getBytes)
  }

  def writeFileAsync(src: File): Future[FileId] = {
    val fid = client.writeFileAsync(
      new FileInputStream(src), src.length)
    fid
  }

  def makeFile(dst: File, length: Long): Unit = {
    val fos = new FileOutputStream(dst)
    var n: Long = 0
    while (n < length) {
      val left: Int = Math.min((length - n).toInt, 10240)
      fos.write((0 to left - 1).map(x => ('a' + x % 26).toByte).toArray)
      n += left
    }

    fos.close()
  }
}
