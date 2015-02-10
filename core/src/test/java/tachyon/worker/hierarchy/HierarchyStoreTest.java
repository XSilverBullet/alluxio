package tachyon.worker.hierarchy;

import java.io.IOException;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import tachyon.Constants;
import tachyon.TestUtils;
import tachyon.client.InStream;
import tachyon.client.ReadType;
import tachyon.client.TachyonFS;
import tachyon.client.TachyonFile;
import tachyon.client.WriteType;
import tachyon.conf.TachyonConf;
import tachyon.master.LocalTachyonCluster;
import tachyon.util.CommonUtils;

/**
 * Unit tests for tachyon.worker.StorageTier.
 */
public class HierarchyStoreTest {
  private static final int MEM_CAPACITY_BYTES = 1000;
  private static final int DISK_CAPACITY_BYTES = 10000;
  private static final int USER_QUOTA_UNIT_BYTES = 100;

  private LocalTachyonCluster mLocalTachyonCluster = null;
  private TachyonFS mTFS = null;
  private TachyonConf mWorkerConf;

  @After
  public final void after() throws Exception {
    mLocalTachyonCluster.stop();
  }

  @Before
  public final void before() throws IOException {
    mLocalTachyonCluster =
        new LocalTachyonCluster(MEM_CAPACITY_BYTES, USER_QUOTA_UNIT_BYTES, Constants.GB);
    mWorkerConf = mLocalTachyonCluster.getWorkerTachyonConf();

    mWorkerConf.set(Constants.WORKER_MAX_HIERARCHY_STORAGE_LEVEL, Integer.toString(2));
    mWorkerConf.set("tachyon.worker.hierarchystore.level0.alias", "MEM");
    mWorkerConf.set("tachyon.worker.hierarchystore.level0.dirs.path",  "/ramdisk");
    mWorkerConf.set("tachyon.worker.hierarchystore.level0.dirs.quota", DISK_CAPACITY_BYTES + "");

    mWorkerConf.set("tachyon.worker.hierarchystore.level1.alias", "HDD");
    mWorkerConf.set("tachyon.worker.hierarchystore.level1.dirs.path", "/disk1" + "," + "/disk2");
    mWorkerConf.set("tachyon.worker.hierarchystore.level1.dirs.quota", DISK_CAPACITY_BYTES + "");

    mLocalTachyonCluster.start();
    mTFS = mLocalTachyonCluster.getClient();
  }

  @Test
  public void blockEvict() throws IOException, InterruptedException {
    int fileId1 =
        TestUtils.createByteFile(mTFS, "/root/test1", WriteType.TRY_CACHE, MEM_CAPACITY_BYTES / 6);
    int fileId2 =
        TestUtils.createByteFile(mTFS, "/root/test2", WriteType.TRY_CACHE, MEM_CAPACITY_BYTES / 6);
    int fileId3 =
        TestUtils.createByteFile(mTFS, "/root/test3", WriteType.TRY_CACHE, MEM_CAPACITY_BYTES / 6);

    TachyonFile file1 = mTFS.getFile(fileId1);
    TachyonFile file2 = mTFS.getFile(fileId2);
    TachyonFile file3 = mTFS.getFile(fileId3);

    Assert.assertEquals(file1.isInMemory(), true);
    Assert.assertEquals(file2.isInMemory(), true);
    Assert.assertEquals(file3.isInMemory(), true);

    int fileId4 =
        TestUtils.createByteFile(mTFS, "/root/test4", WriteType.TRY_CACHE, MEM_CAPACITY_BYTES / 2);
    int fileId5 =
        TestUtils.createByteFile(mTFS, "/root/test5", WriteType.TRY_CACHE, MEM_CAPACITY_BYTES / 2);

    CommonUtils.sleepMs(null, TestUtils.getToMasterHeartBeatIntervalMs(mWorkerConf));
    TachyonFile file4 = mTFS.getFile(fileId4);
    TachyonFile file5 = mTFS.getFile(fileId5);

    Assert.assertEquals(file1.isInMemory(), false);
    Assert.assertEquals(file2.isInMemory(), false);
    Assert.assertEquals(file3.isInMemory(), false);
    Assert.assertEquals(file4.isInMemory(), true);
    Assert.assertEquals(file5.isInMemory(), true);
  }

  @Test
  public void promoteBlock() throws IOException, InterruptedException {
    int fileId1 =
        TestUtils.createByteFile(mTFS, "/root/test1", WriteType.TRY_CACHE, MEM_CAPACITY_BYTES / 6);
    int fileId2 =
        TestUtils.createByteFile(mTFS, "/root/test2", WriteType.TRY_CACHE, MEM_CAPACITY_BYTES / 2);
    int fileId3 =
        TestUtils.createByteFile(mTFS, "/root/test3", WriteType.TRY_CACHE, MEM_CAPACITY_BYTES / 2);

    CommonUtils.sleepMs(null, TestUtils.getToMasterHeartBeatIntervalMs(mWorkerConf));
    TachyonFile file1 = mTFS.getFile(fileId1);
    TachyonFile file2 = mTFS.getFile(fileId2);
    TachyonFile file3 = mTFS.getFile(fileId3);

    Assert.assertEquals(false, file1.isInMemory());
    Assert.assertEquals(true, file2.isInMemory());
    Assert.assertEquals(true, file3.isInMemory());
    Assert.assertEquals(MEM_CAPACITY_BYTES / 6 + MEM_CAPACITY_BYTES,
        mLocalTachyonCluster.getMasterInfo().getUsedBytes());

    InStream is = file1.getInStream(ReadType.CACHE_PROMOTE);
    byte[] buf = new byte[MEM_CAPACITY_BYTES / 6];
    int len = is.read(buf);
    is.close();

    CommonUtils.sleepMs(null, TestUtils.getToMasterHeartBeatIntervalMs(mWorkerConf));
    Assert.assertEquals(MEM_CAPACITY_BYTES / 6, len);
    Assert.assertEquals(true, file1.isInMemory());
    Assert.assertEquals(false, file2.isInMemory());
    Assert.assertEquals(true, file3.isInMemory());
    Assert.assertEquals(MEM_CAPACITY_BYTES / 6 + MEM_CAPACITY_BYTES,
        mLocalTachyonCluster.getMasterInfo().getUsedBytes());
  }
}
