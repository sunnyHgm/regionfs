package regionfs

import java.io._
import java.nio.ByteBuffer

import org.apache.commons.io.IOUtils
import org.grapheco.commons.util.Profiler._
import org.grapheco.regionfs.GlobalSetting
import org.grapheco.regionfs.server.LocalRegionManager
import org.grapheco.regionfs.util.{CrcUtils, Transactional, TransactionalContext}
import org.junit.{Assert, Test}

/**
  * Created by bluejoe on 2020/2/11.
  */
class LocalRegionFileIOTest extends FileTestBase {
  @Test
  def testRegionIO(): Unit = {
    val rm = new LocalRegionManager(1, new File("./testdata/nodes/node1"),
      GlobalSetting.empty, nullRegionEventListener);

    val region = rm.createNew()

    Assert.assertEquals(65537, region.regionId)
    Assert.assertEquals(true, region.isPrimary)
    Assert.assertEquals(0, region.revision)
    Assert.assertEquals(0, region.bodyLength)

    val bytes1 = IOUtils.toByteArray(new FileInputStream(new File("./testdata/inputs/9999999")))
    val buf = ByteBuffer.wrap(bytes1)

    val id = timing(true, 10) {
      val clone = buf.duplicate()
      val tx = Transactional {
        case _ =>
          region.createLocalId()
      } & {
        case localId: Long =>
          region.saveLocalFile(localId, clone, CrcUtils.computeCrc32(buf.duplicate()))
      } & {
        case localId: Long =>
          region.markGlobalWriten(localId, buf.remaining())
      }

      tx.perform(1, TransactionalContext.DEFAULT).asInstanceOf[Long]
    }

    val bytes2 = timing(true, 10) {
      val buf = region.read(id).get
      val bytes = new Array[Byte](buf.remaining())
      buf.get(bytes)
      bytes
    }

    Assert.assertArrayEquals(bytes1, bytes2);
  }
}
