/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.storage

import java.io._
import java.lang.ref.{ReferenceQueue => JReferenceQueue, WeakReference}
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

import scala.collection.mutable
import scala.collection.mutable.HashMap
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.Random
import scala.util.control.NonFatal

import com.codahale.metrics.{MetricRegistry, MetricSet}

import org.apache.spark._
import org.apache.spark.executor.{DataReadMethod, ShuffleWriteMetrics}
import org.apache.spark.internal.{config, Logging}
import org.apache.spark.memory.{MemoryManager, MemoryMode}
import org.apache.spark.metrics.source.Source
import org.apache.spark.network._
import org.apache.spark.network.buffer.ManagedBuffer
import org.apache.spark.network.netty.SparkTransportConf
import org.apache.spark.network.shuffle.{ExternalShuffleClient, TempFileManager}
import org.apache.spark.network.shuffle.protocol.ExecutorShuffleInfo
import org.apache.spark.rpc.RpcEnv
import org.apache.spark.serializer.{SerializerInstance, SerializerManager}
import org.apache.spark.shuffle.ShuffleManager
import org.apache.spark.storage.memory._
import org.apache.spark.unsafe.Platform
import org.apache.spark.util._
import org.apache.spark.util.io.ChunkedByteBuffer

/* Class for returning a fetched block and associated metrics. */
private[spark] class BlockResult(
    val data: Iterator[Any],
    val readMethod: DataReadMethod.Value,
    val bytes: Long)

/**
 * Abstracts away how blocks are stored and provides different ways to read the underlying block
 * data. Callers should call [[dispose()]] when they're done with the block.
 */
private[spark] trait BlockData {

  def toInputStream(): InputStream

  /**
   * Returns a Netty-friendly wrapper for the block's data.
   *
   * Please see `ManagedBuffer.convertToNetty()` for more details.
   */
  def toNetty(): Object

  def toChunkedByteBuffer(allocator: Int => ByteBuffer): ChunkedByteBuffer

  def toByteBuffer(): ByteBuffer

  def size: Long

  def dispose(): Unit

}

private[spark] class ByteBufferBlockData(
    val buffer: ChunkedByteBuffer,
    val shouldDispose: Boolean) extends BlockData {

  override def toInputStream(): InputStream = buffer.toInputStream(dispose = false)

  override def toNetty(): Object = buffer.toNetty

  override def toChunkedByteBuffer(allocator: Int => ByteBuffer): ChunkedByteBuffer = {
    buffer.copy(allocator)
  }

  override def toByteBuffer(): ByteBuffer = buffer.toByteBuffer

  override def size: Long = buffer.size

  override def dispose(): Unit = {
    if (shouldDispose) {
      buffer.dispose()
    }
  }

}

/**
 * Manager running on every node (driver and executors) which provides interfaces for putting and
 * retrieving blocks both locally and remotely into various stores (memory, disk, and off-heap).
 *
 * Note that [[initialize()]] must be called before the BlockManager is usable.
 * 其实为什么不在LiveListenerBus也加这样的说明呢：post必须在listener建立之后才能调用，而不让问题搞的更复杂：see[SPARK-22850]
 * 看了一下 BlockManager 的方法调用，意思是 RDD 直接就和 BlockManager 交互了？
 */
private[spark] class BlockManager(
    executorId: String,
    rpcEnv: RpcEnv,
    val master: BlockManagerMaster,
    val serializerManager: SerializerManager,
    val conf: SparkConf,
    memoryManager: MemoryManager,
    mapOutputTracker: MapOutputTracker,
    shuffleManager: ShuffleManager,
    val blockTransferService: BlockTransferService,
    securityManager: SecurityManager,
    numUsableCores: Int)
  extends BlockDataManager with BlockEvictionHandler with Logging {

  // 是否启用外部shuffle服务 ？？？
  private[spark] val externalShuffleServiceEnabled =
    conf.getBoolean("spark.shuffle.service.enabled", false)

  // 磁盘块管理
  val diskBlockManager = {
    // Only perform cleanup if an external service is not serving our shuffle files.
    // 只有在外部服务没有在为shuffle文件服务或当前节点为driver时，才会执行在stop调用是执行删除操作
    val deleteFilesOnStop =
      !externalShuffleServiceEnabled || executorId == SparkContext.DRIVER_IDENTIFIER
    new DiskBlockManager(conf, deleteFilesOnStop)
  }
  // 块信息管理
  // Visible for testing
  private[storage] val blockInfoManager = new BlockInfoManager
  // ？？？
  private val futureExecutionContext = ExecutionContext.fromExecutorService(
    ThreadUtils.newDaemonCachedThreadPool("block-manager-future", 128))

  // Actual storage of where blocks are kept
  // 内存存储
  private[spark] val memoryStore =
    new MemoryStore(conf, blockInfoManager, serializerManager, memoryManager, this)
  // 磁盘存储
  private[spark] val diskStore = new DiskStore(conf, diskBlockManager, securityManager)
  memoryManager.setMemoryStore(memoryStore)

  // Note: depending on the memory manager, `maxMemory` may actually vary over time.
  // However, since we use this only for reporting and logging, what we actually want here is
  // the absolute maximum value that `maxMemory` can ever possibly reach. We may need
  // to revisit whether reporting this value as the "max" is intuitive to the user.
  private val maxOnHeapMemory = memoryManager.maxOnHeapStorageMemory
  private val maxOffHeapMemory = memoryManager.maxOffHeapStorageMemory

  // Port used by the external shuffle service. In Yarn mode, this may be already be
  // set through the Hadoop configuration as the server is launched in the Yarn NM.
  private val externalShuffleServicePort = {
    val tmpPort = Utils.getSparkOrYarnConfig(conf, "spark.shuffle.service.port", "7337").toInt
    if (tmpPort == 0) {
      // for testing, we set "spark.shuffle.service.port" to 0 in the yarn config, so yarn finds
      // an open port.  But we still need to tell our spark apps the right port to use.  So
      // only if the yarn config has the port set to 0, we prefer the value in the spark config
      conf.get("spark.shuffle.service.port").toInt
    } else {
      tmpPort
    }
  }
  // BlockManager的唯一标识
  var blockManagerId: BlockManagerId = _

  // Address of the server that serves this executor's shuffle files. This is either an external
  // service, or just our own Executor's BlockManager.
  // shuffleServerId是服务该executor的shuffle 文件的服务端地址。这有可能是一个外部的服务，也有可能这就是
  // 在该executor上的BlockManager
  private[spark] var shuffleServerId: BlockManagerId = _

  // TODO read
  // Client to read other executors' shuffle files. This is either an external service, or just the
  // standard BlockTransferService to directly connect to other Executors.
  private[spark] val shuffleClient = if (externalShuffleServiceEnabled) {
    val transConf = SparkTransportConf.fromSparkConf(conf, "shuffle", numUsableCores)
    new ExternalShuffleClient(transConf, securityManager,
      securityManager.isAuthenticationEnabled(), conf.get(config.SHUFFLE_REGISTRATION_TIMEOUT))
  } else {
    blockTransferService
  }

  // Max number of failures before this block manager refreshes the block locations from the driver
  private val maxFailuresBeforeLocationRefresh =
    conf.getInt("spark.block.failures.beforeLocationRefresh", 5)

  private val slaveEndpoint = rpcEnv.setupEndpoint(
    "BlockManagerEndpoint" + BlockManager.ID_GENERATOR.next,
    new BlockManagerSlaveEndpoint(rpcEnv, this, mapOutputTracker))

  // Pending re-registration action being executed asynchronously or null if none is pending.
  // Accesses should synchronize on asyncReregisterLock.
  private var asyncReregisterTask: Future[Unit] = null
  private val asyncReregisterLock = new Object

  // Field related to peer block managers that are necessary for block replication
  @volatile private var cachedPeers: Seq[BlockManagerId] = _
  private val peerFetchLock = new Object
  private var lastPeerFetchTime = 0L

  // 块复制策略
  private var blockReplicationPolicy: BlockReplicationPolicy = _

  // 一个用来跟踪所有超出指定内存阈值的远程块（为什么是跟踪远程的？？？）上的文件。这些文件会通过弱引用的方式自动删除。
  // A TempFileManager used to track all the files of remote blocks which above the
  // specified memory threshold. Files will be deleted automatically based on weak reference.
  // Exposed for test
  private[storage] val remoteBlockTempFileManager =
    new BlockManager.RemoteBlockTempFileManager(this)
  private val maxRemoteBlockToMem = conf.get(config.MAX_REMOTE_BLOCK_SIZE_FETCH_TO_MEM)

  /**
   * 之所以没有在构造函数里给定appId，是因为BlockManager实例化的时候，appId可能还不知道。
   * Initializes the BlockManager with the given appId. This is not performed in the constructor as
   * the appId may not be known at BlockManager instantiation time (in particular for the driver,
   * where it is only learned after registration with the TaskScheduler -> (既然这样，我们在taskscheduler
   * 实例化之后，在实例化BlockManager不就可以了吗？？？)).
   *
   * This method initializes the BlockTransferService and ShuffleClient, registers with the
   * BlockManagerMaster, starts the BlockManagerWorker endpoint, and registers with a local shuffle
   * service if configured.
   */
  def initialize(appId: String): Unit = {
    // 初始化blockTransferService
    blockTransferService.init(this)
    // 初始化shuffleClient
    shuffleClient.init(appId)

    // 初始化块赋值策略，默认是随机块复制策略
    blockReplicationPolicy = {
      val priorityClass = conf.get(
        "spark.storage.replication.policy", classOf[RandomBlockReplicationPolicy].getName)
      val clazz = Utils.classForName(priorityClass)
      val ret = clazz.newInstance.asInstanceOf[BlockReplicationPolicy]
      logInfo(s"Using $priorityClass for block replication policy")
      ret
    }

    // 初始化id，会先从缓存中尝试获取，如果没有，再创建新的BlockManagerId，并加入缓存
    // ??? 如果缓存中存在，那么，该id会有可能还没向master注册吗？
    val id =
      BlockManagerId(executorId, blockTransferService.hostName, blockTransferService.port, None)
    // 向BlockManagerMaster注册该BlockManager
    val idFromMaster = master.registerBlockManager(
      id,
      maxOnHeapMemory,
      maxOffHeapMemory,
      slaveEndpoint)

    // 如果是idFromMaster，不用更新缓存吗？？？（好吧，在register的时候，会更新缓存，但是：
    // 那更新的也只是driver端的blockmanager的缓存啊，还是没有更新executor端的缓存啊???）
    blockManagerId = if (idFromMaster != null) idFromMaster else id

    shuffleServerId = if (externalShuffleServiceEnabled) {
      logInfo(s"external shuffle service port = $externalShuffleServicePort")
      BlockManagerId(executorId, blockTransferService.hostName, externalShuffleServicePort)
    } else {
      blockManagerId
    }

    // TODO read
    // Register Executors' configuration with the local shuffle service, if one should exist.
    if (externalShuffleServiceEnabled && !blockManagerId.isDriver) {
      registerWithExternalShuffleServer()
    }

    logInfo(s"Initialized BlockManager: $blockManagerId")
  }

  // TODO read
  def shuffleMetricsSource: Source = {
    import BlockManager._

    if (externalShuffleServiceEnabled) {
      new ShuffleMetricsSource("ExternalShuffle", shuffleClient.shuffleMetrics())
    } else {
      new ShuffleMetricsSource("NettyBlockTransfer", shuffleClient.shuffleMetrics())
    }
  }

  private def registerWithExternalShuffleServer() {
    logInfo("Registering executor with local external shuffle service.")
    val shuffleConfig = new ExecutorShuffleInfo(
      diskBlockManager.localDirs.map(_.toString),
      diskBlockManager.subDirsPerLocalDir,
      shuffleManager.getClass.getName)

    val MAX_ATTEMPTS = conf.get(config.SHUFFLE_REGISTRATION_MAX_ATTEMPTS)
    val SLEEP_TIME_SECS = 5

    for (i <- 1 to MAX_ATTEMPTS) {
      try {
        // Synchronous and will throw an exception if we cannot connect.
        shuffleClient.asInstanceOf[ExternalShuffleClient].registerWithShuffleServer(
          shuffleServerId.host, shuffleServerId.port, shuffleServerId.executorId, shuffleConfig)
        return
      } catch {
        case e: Exception if i < MAX_ATTEMPTS =>
          logError(s"Failed to connect to external shuffle server, will retry ${MAX_ATTEMPTS - i}"
            + s" more times after waiting $SLEEP_TIME_SECS seconds...", e)
          Thread.sleep(SLEEP_TIME_SECS * 1000)
        case NonFatal(e) =>
          throw new SparkException("Unable to register with external shuffle server due to : " +
            e.getMessage, e)
      }
    }
  }

  /**
   * Report all blocks to the BlockManager again. This may be necessary if we are dropped
   * by the BlockManager and come back or if we become capable of recovering blocks on disk after
   * an executor crash.
   *
   * 该方法在master返回false(暗示slave需要重新注册BlockManager)后，故意得悄悄失败(不抛出异常)。这是因为这个error
   * 会在下一次心跳检测或者有信息的block向master注册时，会重新注册BlockManager(以及所有的blocks).
   * This function deliberately fails silently if the master returns false (indicating that
   * the slave needs to re-register). The error condition will be detected again by the next
   * heart beat attempt or new block registration and another try to re-register all blocks
   * will be made then.
   */
  private def reportAllBlocks(): Unit = {
    logInfo(s"Reporting ${blockInfoManager.size} blocks to the master.")
    for ((blockId, info) <- blockInfoManager.entries) {
      // 获取当前Block当前的BlockStatus
      val status = getCurrentBlockStatus(blockId, info)
      if (info.tellMaster && !tryToReportBlockStatus(blockId, status)) {
        // fails silently,不抛出异常
        logError(s"Failed to report $blockId to master; giving up.")
        return
      }
    }
  }

  /**
   * Re-register with the master and report all blocks to it. This will be called by the heart beat
   * thread if our heartbeat to the block manager indicates that we were not registered.
   *
   * Note that this method must be called without any BlockInfo locks held.
   */
  def reregister(): Unit = {
    // TODO: We might need to rate limit re-registering.
    logInfo(s"BlockManager $blockManagerId re-registering with master")
    master.registerBlockManager(blockManagerId, maxOnHeapMemory, maxOffHeapMemory, slaveEndpoint)
    // （重新）向master节点报告（登记）所有blocks的情况
    reportAllBlocks()
  }

  /**
   * Re-register with the master sometime soon.
   */
  private def asyncReregister(): Unit = {
    asyncReregisterLock.synchronized {
      if (asyncReregisterTask == null) {
        asyncReregisterTask = Future[Unit] {
          // This is a blocking action and should run in futureExecutionContext which is a cached
          // thread pool
          reregister()
          asyncReregisterLock.synchronized {
            asyncReregisterTask = null
          }
        }(futureExecutionContext)
      }
    }
  }

  /**
   * For testing. Wait for any pending asynchronous re-registration; otherwise, do nothing.
   */
  def waitForAsyncReregister(): Unit = {
    val task = asyncReregisterTask
    if (task != null) {
      try {
        ThreadUtils.awaitReady(task, Duration.Inf)
      } catch {
        case NonFatal(t) =>
          throw new Exception("Error occurred while waiting for async. reregistration", t)
      }
    }
  }

  /**
   * 获取本地block的接口。如果block没有找到或无法成功读取，则抛出异常。
   * Interface to get local block data. Throws an exception if the block cannot be found or
   * cannot be read successfully.
   */
  override def getBlockData(blockId: BlockId): ManagedBuffer = {
    // 该BlockId是否为ShuffleBlockId
    if (blockId.isShuffle) {
      // 如果是ShuffleBlock，则通过ShuffleBlockResolver来获取该block
      // 该getBlockData()返回的是FileSegmentManagedBuffer，该buf包含数据文件的一个引用(File对象),
      // 但并不包含实际的block数据。
      shuffleManager.shuffleBlockResolver.getBlockData(blockId.asInstanceOf[ShuffleBlockId])
    } else {
      getLocalBytes(blockId) match {
        case Some(blockData) =>
          new BlockManagerManagedBuffer(blockInfoManager, blockId, blockData, true)
        case None =>
          // If this block manager receives a request for a block that it doesn't have then it's
          // likely that the master has outdated block statuses for this block. Therefore, we send
          // an RPC so that this block is marked as being unavailable from this block manager.
          reportBlockStatus(blockId, BlockStatus.empty)
          throw new BlockNotFoundException(blockId.toString)
      }
    }
  }

  /**
   * Put the block locally, using the given storage level.
   *
   * '''Important!''' Callers must not mutate or release the data buffer underlying `bytes`. Doing
   * so may corrupt or change the data stored by the `BlockManager`.
   */
  override def putBlockData(
      blockId: BlockId,
      data: ManagedBuffer,
      level: StorageLevel,
      classTag: ClassTag[_]): Boolean = {
    putBytes(blockId, new ChunkedByteBuffer(data.nioByteBuffer()), level)(classTag)
  }

  /**
   * Get the BlockStatus for the block identified by the given ID, if it exists.
   * NOTE: This is mainly for testing.
   */
  def getStatus(blockId: BlockId): Option[BlockStatus] = {
    blockInfoManager.get(blockId).map { info =>
      val memSize = if (memoryStore.contains(blockId)) memoryStore.getSize(blockId) else 0L
      val diskSize = if (diskStore.contains(blockId)) diskStore.getSize(blockId) else 0L
      BlockStatus(info.level, memSize = memSize, diskSize = diskSize)
    }
  }

  /**
   * Get the ids of existing blocks that match the given filter. Note that this will
   * query the blocks stored in the disk block manager (that the block manager
   * may not know of).
   */
  def getMatchingBlockIds(filter: BlockId => Boolean): Seq[BlockId] = {
    // The `toArray` is necessary here in order to force the list to be materialized so that we
    // don't try to serialize a lazy iterator when responding to client requests.
    (blockInfoManager.entries.map(_._1) ++ diskBlockManager.getAllBlocks())
      .filter(filter)
      .toArray
      .toSeq
  }

  /**
   * Tell the master about the current storage status of a block. This will send a block update
   * message reflecting the current status, *not* the desired storage level in its block info.
   * For example, a block with MEMORY_AND_DISK set might have fallen out to be only on disk.
   *
   * droppedMemorySize exists to account for when the block is dropped from memory to disk (so
   * it is still valid). This ensures that update in master will compensate for the increase in
   * memory on slave.
   */
  private def reportBlockStatus(
      blockId: BlockId,
      status: BlockStatus,
      droppedMemorySize: Long = 0L): Unit = {
    val needReregister = !tryToReportBlockStatus(blockId, status, droppedMemorySize)
    if (needReregister) {
      logInfo(s"Got told to re-register updating block $blockId")
      // Re-registering will report our new block for free.
      asyncReregister()
    }
    logDebug(s"Told master about block $blockId")
  }

  /**
   * 发送UpdateBlockInfo给master。如果block成功在master登记，则返回true；反之返回false，
   * 并且要求slave重新登记它的BlockManager。
   * Actually send a UpdateBlockInfo message. Returns the master's response,
   * which will be true if the block was successfully recorded and false if
   * the slave needs to re-register.
   */
  private def tryToReportBlockStatus(
      blockId: BlockId,
      status: BlockStatus,
      droppedMemorySize: Long = 0L): Boolean = {
    val storageLevel = status.storageLevel
    // 这个droppedMemorySize到底什么意思???没看明白
    val inMemSize = Math.max(status.memSize, droppedMemorySize)
    val onDiskSize = status.diskSize
    // 我们发送给master的messgae是包含blockManagerId的，这样master就知道该block是在哪台机器上了
    master.updateBlockInfo(blockManagerId, blockId, storageLevel, inMemSize, onDiskSize)
  }

  /**
   * Return the updated storage status of the block with the given ID. More specifically, if
   * the block is dropped from memory and possibly added to disk, return the new storage level
   * and the updated in-memory and on-disk sizes.
   */
  private def getCurrentBlockStatus(blockId: BlockId, info: BlockInfo): BlockStatus = {
    info.synchronized {
      info.level match {
        case null =>
          BlockStatus.empty
        case level =>
          // 更新当前block的StorageLevel
          val inMem = level.useMemory && memoryStore.contains(blockId)
          val onDisk = level.useDisk && diskStore.contains(blockId)
          val deserialized = if (inMem) level.deserialized else false
          // 如果既不在内存中，也不在磁盘中，则replication为1???那它在哪儿呢???
          val replication = if (inMem  || onDisk) level.replication else 1
          val storageLevel = StorageLevel(
            useDisk = onDisk,
            useMemory = inMem,
            useOffHeap = level.useOffHeap,
            deserialized = deserialized,
            replication = replication)
          // 更新当前block的占用内存大小或磁盘大小
          val memSize = if (inMem) memoryStore.getSize(blockId) else 0L
          val diskSize = if (onDisk) diskStore.getSize(blockId) else 0L
          BlockStatus(storageLevel, memSize, diskSize)
      }
    }
  }

  /**
   * Get locations of an array of blocks.
   */
  private def getLocationBlockIds(blockIds: Array[BlockId]): Array[Seq[BlockManagerId]] = {
    val startTimeMs = System.currentTimeMillis
    val locations = master.getLocations(blockIds).toArray
    logDebug("Got multiple block location in %s".format(Utils.getUsedTimeMs(startTimeMs)))
    locations
  }

  /**
   * Cleanup code run in response to a failed local read.
   * Must be called while holding a read lock on the block.
   */
  private def handleLocalReadFailure(blockId: BlockId): Nothing = {
    releaseLock(blockId)
    // Remove the missing block so that its unavailability is reported to the driver
    removeBlock(blockId)
    throw new SparkException(s"Block $blockId was not found even though it's read-locked")
  }

  /**
   * 从本地BlockManager中获取java对象迭代器形式的Block
   * Get block from local block manager as an iterator of Java objects.
   */
  def getLocalValues(blockId: BlockId): Option[BlockResult] = {
    logDebug(s"Getting local block $blockId")
    // 申请获取该Block的读锁
    blockInfoManager.lockForReading(blockId) match {
      // 读锁申请失败，该Block不存在
      case None =>
        logDebug(s"Block $blockId was not found")
        None
      // 读锁申请成功，返回该Block的BlockInfo
      case Some(info) =>
        // 通过BlockInfo获取该Block的StorageLevel
        val level = info.level
        logDebug(s"Level for block $blockId is $level")
        // 获取当前task的attemptId
        val taskAttemptId = Option(TaskContext.get()).map(_.taskAttemptId())
        // 从内存中获取该Block
        if (level.useMemory && memoryStore.contains(blockId)) {
          val iter: Iterator[Any] = if (level.deserialized) {
            // 如果该Block是以value形式存储的，则直接获取对应的value，并返回迭代器
            memoryStore.getValues(blockId).get
          } else {
            // 如果该Block是以序列化的形式存储，则反序列其值后，返回迭代器
            serializerManager.dataDeserializeStream(
              blockId, memoryStore.getBytes(blockId).get.toInputStream())(info.classTag)
          }
          // We need to capture the current taskId in case the iterator completion is triggered
          // from a different thread which does not have TaskContext set; see SPARK-18406 for
          // discussion.
          val ci = CompletionIterator[Any, Iterator[Any]](iter, {
            // 当iter遍历完成之后，会执行releaseLock方法()
            releaseLock(blockId, taskAttemptId)
          })
          // 返回BlockResult
          Some(new BlockResult(ci, DataReadMethod.Memory, info.size))
        } else if (level.useDisk && diskStore.contains(blockId)) { // 从本地磁盘中获取该Block
          val diskData = diskStore.getBytes(blockId)
          val iterToReturn: Iterator[Any] = {
            if (level.deserialized) {
              val diskValues = serializerManager.dataDeserializeStream(
                blockId,
                diskData.toInputStream())(info.classTag)
              // 如果有足够的内存空间，则把刚从磁盘读取的Block缓存到内存中，
              // 以加速之后对该Block的读取
              maybeCacheDiskValuesInMemory(info, blockId, level, diskValues)
            } else {
              val stream = maybeCacheDiskBytesInMemory(info, blockId, level, diskData)
                .map { _.toInputStream(dispose = false) }
                .getOrElse { diskData.toInputStream() }
              serializerManager.dataDeserializeStream(blockId, stream)(info.classTag)
            }
          }
          val ci = CompletionIterator[Any, Iterator[Any]](iterToReturn, {
            // 从磁盘中读取完数据后不仅要释放锁，
            // 还要dispose数据，而从内存中读取就不用dipose数据，why???
            // 答：因为现在已经有该block的两份数据了(内存和磁盘)，
            // 显然，我们不需要磁盘里的了(内存里的读取更快啊)
            releaseLockAndDispose(blockId, diskData, taskAttemptId)
          })
          Some(new BlockResult(ci, DataReadMethod.Disk, info.size))
        } else {
          handleLocalReadFailure(blockId)
        }
    }
  }

  /**
   * Get block from the local block manager as serialized bytes.
   */
  def getLocalBytes(blockId: BlockId): Option[BlockData] = {
    logDebug(s"Getting local block $blockId as bytes")
    // As an optimization for map output fetches, if the block is for a shuffle, return it
    // without acquiring a lock; the disk store never deletes (recent) items so this should work
    if (blockId.isShuffle) {
      val shuffleBlockResolver = shuffleManager.shuffleBlockResolver
      // TODO: This should gracefully handle case where local block is not available. Currently
      // downstream code will throw an exception.
      val buf = new ChunkedByteBuffer(
        shuffleBlockResolver.getBlockData(blockId.asInstanceOf[ShuffleBlockId]).nioByteBuffer())
      Some(new ByteBufferBlockData(buf, true))
    } else {
      blockInfoManager.lockForReading(blockId).map { info => doGetLocalBytes(blockId, info) }
    }
  }

  /**
   * Get block from the local block manager as serialized bytes.
   *
   * Must be called while holding a read lock on the block.
   * Releases the read lock upon exception; keeps the read lock upon successful return.
   */
  private def doGetLocalBytes(blockId: BlockId, info: BlockInfo): BlockData = {
    val level = info.level
    logDebug(s"Level for block $blockId is $level")
    // TODO 修改注释
    // 这个注释说的order是level.deserialize == false的情况吧
    // In order, try to read the serialized bytes from memory, then from disk, then fall back to
    // serializing in-memory objects, and, finally, throw an exception if the block does not exist.
    // 如果level指明block是非序列化存储的，那么优先查找磁盘，再查找内存。之所以优先
    // 查找磁盘，是因为如果内存中有该Block，那么，我们还要对其序列化（这部分开销大于
    // 访问磁盘带来的开销）。而如果磁盘里有该block，则肯定是序列化过的。
    if (level.deserialized) {
      // 通过读取来自磁盘的预先序列化的副本来尽量避免昂贵的序列化操作
      // Try to avoid expensive serialization by reading a pre-serialized copy from disk:
      // 虽然level说了是deserialized，但是如果存到了磁盘上，那肯定是序列化过的
      if (level.useDisk && diskStore.contains(blockId)) {
        // 之所以在这边没有把磁盘里读取的block缓存到内存中，是因为：首先该if分支处理的是反序列化存储
        // 的block，所以，极有可能在内存中已经有该value对象(且没有以bytes存储的对象)。而调用者却想要
        // bytes对象(说明内存中没有bytes对象嘛)。而你却要缓存一个value对象（强调：人家要的是bytes）对
        // 象。所以，这样的缓存很可能不会带来任何效益。
        // Note: we purposely do not try to put the block back into memory here. Since this branch
        // handles deserialized blocks, this block may only be cached in memory as objects, not
        // serialized bytes. Because the caller only requested bytes, it doesn't make sense to
        // cache the block's deserialized objects since that caching may not have a payoff.
        diskStore.getBytes(blockId)
      } else if (level.useMemory && memoryStore.contains(blockId)) {
        // TODO read
        // 如果在内存中，则序列化之
        // The block was not found on disk, so serialize an in-memory copy:
        new ByteBufferBlockData(serializerManager.dataSerializeWithExplicitClassTag(
          blockId, memoryStore.getValues(blockId).get, info.classTag), true)
      } else {
        // 抛出异常（会释放锁）
        handleLocalReadFailure(blockId)
      }
    } else {  // storage level is serialized
      // 如果level指明block是序列化存储的，那么优先查找内存，再查找磁盘
      if (level.useMemory && memoryStore.contains(blockId)) {
        new ByteBufferBlockData(memoryStore.getBytes(blockId).get, false)
      } else if (level.useDisk && diskStore.contains(blockId)) {
        val diskData = diskStore.getBytes(blockId)
        // 尝试缓存到磁盘
        maybeCacheDiskBytesInMemory(info, blockId, level, diskData)
          .map(new ByteBufferBlockData(_, false))
          .getOrElse(diskData)
      } else {
        handleLocalReadFailure(blockId)
      }
    }
  }

  /**
   * Get block from remote block managers.
   *
   * This does not acquire a lock on this block in this JVM.
   */
  private def getRemoteValues[T: ClassTag](blockId: BlockId): Option[BlockResult] = {
    val ct = implicitly[ClassTag[T]]
    getRemoteBytes(blockId).map { data =>
      val values =
        serializerManager.dataDeserializeStream(blockId, data.toInputStream(dispose = true))(ct)
      new BlockResult(values, DataReadMethod.Network, data.size)
    }
  }

  /**
   * 按照同一主机、同一机架、其它机架的先后顺序，对location(BlockManageId)进行排序。
   * Return a list of locations for the given block, prioritizing the local machine since
   * multiple block managers can share the same host, followed by hosts on the same rack.
   */
  private def sortLocations(locations: Seq[BlockManagerId]): Seq[BlockManagerId] = {
    val locs = Random.shuffle(locations)
    val (preferredLocs, otherLocs) = locs.partition { loc => blockManagerId.host == loc.host }
    blockManagerId.topologyInfo match {
      case None => preferredLocs ++ otherLocs
      case Some(_) =>
        val (sameRackLocs, differentRackLocs) = otherLocs.partition {
          loc => blockManagerId.topologyInfo == loc.topologyInfo
        }
        preferredLocs ++ sameRackLocs ++ differentRackLocs
    }
  }

  /**
   * 以序列化字节的形式从远程block managers处获取block（之所以manager加"s"是因为：
   * 一个block可能存储在多个block managers。这是可能的。所以，如果我们在某个block
   * manager上获取该block失败，我们就要尝试从下一个block manager获取该block。）
   * Get block from remote block managers as serialized bytes.
   */
  def getRemoteBytes(blockId: BlockId): Option[ChunkedByteBuffer] = {
    logDebug(s"Getting remote block $blockId")
    require(blockId != null, "BlockId is null")
    var runningFailureCount = 0
    var totalFailureCount = 0

    // Because all the remote blocks are registered in driver, it is not necessary to ask
    // all the slave executors to get block status.
    // 因为所有的远程块都会向driver注册，所以，我们不必要去询问所有的slave executors来获取
    // 该块的信息(只需要询问driver就可以啦)
    // 获取Block的Location和Status(by rpc)
    val locationsAndStatus = master.getLocationsAndStatus(blockId)
    // 获取disk size和 memory size中较大的那个为block size
    val blockSize = locationsAndStatus.map { b =>
      b.status.diskSize.max(b.status.memSize)
    }.getOrElse(0L)
    // 获取存有该block的所有location(其实就是BlockManagerId)
    val blockLocations = locationsAndStatus.map(_.locations).getOrElse(Seq.empty)

    // 如果block的size超过了阈值maxRemoteBlockToMem，则我们需要把我们的FileManager传参给
    // BlockTransferService，BTS会利用FileManager来spill该block(到磁盘)。如果没有，就传参
    // null值给BlockTransferService，这意味这该block会被存储在内存中。
    // If the block size is above the threshold, we should pass our FileManger to
    // BlockTransferService, which will leverage it to spill the block; if not, then passed-in
    // null value means the block will be persisted in memory.
    val tempFileManager = if (blockSize > maxRemoteBlockToMem) {
      remoteBlockTempFileManager
    } else {
      null
    }

    // 对location按本地性高低进行排序
    val locations = sortLocations(blockLocations)
    val maxFetchFailures = locations.size
    var locationIterator = locations.iterator
    while (locationIterator.hasNext) {
      val loc = locationIterator.next()
      logDebug(s"Getting remote block $blockId from $loc")
      val data = try {
        // TODO read blockTransferService
        blockTransferService.fetchBlockSync(
          loc.host, loc.port, loc.executorId, blockId.toString, tempFileManager).nioByteBuffer()
      } catch {
        case NonFatal(e) =>
          runningFailureCount += 1
          totalFailureCount += 1

          if (totalFailureCount >= maxFetchFailures) {
            // Give up trying anymore locations. Either we've tried all of the original locations,
            // or we've refreshed the list of locations from the master, and have still
            // hit failures after trying locations from the refreshed list.
            logWarning(s"Failed to fetch block after $totalFailureCount fetch failures. " +
              s"Most recent failure cause:", e)
            return None
          }

          logWarning(s"Failed to fetch remote block $blockId " +
            s"from $loc (failed attempt $runningFailureCount)", e)

          // stale entries是指那些过时的location吗???
          // If there is a large number of executors then locations list can contain a
          // large number of stale entries causing a large number of retries that may
          // take a significant amount of time. To get rid of these stale entries
          // we refresh the block locations after a certain number of fetch failures
          if (runningFailureCount >= maxFailuresBeforeLocationRefresh) {
            // refresh后的location是否会包含之前failure的那些个locations???
            // 这个不确定的。如果有executor挂掉了，那么该executor上的block manager可能也就随之
            // 挂掉了，这时候refresh肯定获取不到这个location。但其它情况可能还是会有。（所以，
            // 要去除掉这些之前failure过的location吗？？？）
            locationIterator = sortLocations(master.getLocations(blockId)).iterator
            logDebug(s"Refreshed locations from the driver " +
              s"after ${runningFailureCount} fetch failures.")
            runningFailureCount = 0
          }

          // This location failed, so we retry fetch from a different one by returning null here
          null
      }
      // 只要我们从某个location上获取了该block，该循环就终止了
      if (data != null) {
        return Some(new ChunkedByteBuffer(data))
      }
      logDebug(s"The value of block $blockId is null")
    }
    logDebug(s"Block $blockId not found")
    None
  }

  /**
   * 从block manager获取一个block(本地或远程)
   * Get a block from the block manager (either local or remote).
   *
   * 如果该block存储在本地，则需要先在该block上获取一个读锁。而如果是从远程的block manager上获取的，
   * 则不需要加读锁。一旦block result中的data迭代完毕，则读锁就会自动释放。
   * This acquires a read lock on the block if the block was stored locally and does not acquire
   * any locks if the block was fetched from a remote block manager. The read lock will
   * automatically be freed once the result's `data` iterator is fully consumed.
   */
  def get[T: ClassTag](blockId: BlockId): Option[BlockResult] = {
    // 先尝试从本地获取该block
    val local = getLocalValues(blockId)
    if (local.isDefined) {
      logInfo(s"Found block $blockId locally")
      return local
    }
    // 如果本地没有，再从远程获取该block
    val remote = getRemoteValues[T](blockId)
    if (remote.isDefined) {
      logInfo(s"Found block $blockId remotely")
      return remote
    }
    None
  }

  /**
   * Downgrades an exclusive write lock to a shared read lock.
   */
  def downgradeLock(blockId: BlockId): Unit = {
    blockInfoManager.downgradeLock(blockId)
  }

  /**
   * Release a lock on the given block with explicit TID.
   * The param `taskAttemptId` should be passed in case we can't get the correct TID from
   * TaskContext, for example, the input iterator of a cached RDD iterates to the end in a child
   * thread.
   */
  def releaseLock(blockId: BlockId, taskAttemptId: Option[Long] = None): Unit = {
    blockInfoManager.unlock(blockId, taskAttemptId)
  }

  /**
   * Registers a task with the BlockManager in order to initialize per-task bookkeeping structures.
   */
  def registerTask(taskAttemptId: Long): Unit = {
    blockInfoManager.registerTask(taskAttemptId)
  }

  /**
   * Release all locks for the given task.
   *
   * @return the blocks whose locks were released.
   */
  def releaseAllLocksForTask(taskAttemptId: Long): Seq[BlockId] = {
    blockInfoManager.releaseAllLocksForTask(taskAttemptId)
  }

  /**
   * 如果该Block已经存储在BlockManager，则直接拉取；否则，调用该方法提供的makeIterator方法来计算该Block，
   * 并将其持久化，然后返回它的值。
   * Retrieve the given block if it exists, otherwise call the provided `makeIterator` method
   * to compute the block, persist it, and return its values.
   *
   * @return either a BlockResult if the block was successfully cached, or an iterator if the block
   *         could not be cached.
   */
  def getOrElseUpdate[T](
      blockId: BlockId,
      level: StorageLevel,
      classTag: ClassTag[T],
      makeIterator: () => Iterator[T]): Either[BlockResult, Iterator[T]] = {
    // Attempt to read the block from local or remote storage. If it's present, then we don't need
    // to go through the local-get-or-put path.
    get[T](blockId)(classTag) match {
      case Some(block) =>
        return Left(block)
      case _ =>
        // Need to compute the block.
    }
    // Initially we hold no locks on this block.
    doPutIterator(blockId, makeIterator, level, classTag, keepReadLock = true) match {
      case None =>
        // doPut() didn't hand work back to us, so the block already existed or was successfully
        // stored. Therefore, we now hold a read lock on the block.
        val blockResult = getLocalValues(blockId).getOrElse {
          // Since we held a read lock between the doPut() and get() calls, the block should not
          // have been evicted, so get() not returning the block indicates some internal error.
          releaseLock(blockId)
          throw new SparkException(s"get() failed for block $blockId even though we held a lock")
        }
        // We already hold a read lock on the block from the doPut() call and getLocalValues()
        // acquires the lock again, so we need to call releaseLock() here so that the net number
        // of lock acquisitions is 1 (since the caller will only call release() once).
        releaseLock(blockId)
        Left(blockResult)
      case Some(iter) =>
        // The put failed, likely because the data was too large to fit in memory and could not be
        // dropped to disk. Therefore, we need to pass the input iterator back to the caller so
        // that they can decide what to do with the values (e.g. process them without caching).
       Right(iter)
    }
  }

  /**
   * @return true if the block was stored or false if an error occurred.
   */
  def putIterator[T: ClassTag](
      blockId: BlockId,
      values: Iterator[T],
      level: StorageLevel,
      tellMaster: Boolean = true): Boolean = {
    require(values != null, "Values is null")
    doPutIterator(blockId, () => values, level, implicitly[ClassTag[T]], tellMaster) match {
      case None =>
        true
      case Some(iter) =>
        // Caller doesn't care about the iterator values, so we can close the iterator here
        // to free resources earlier
        // 释放占用的unroll memory
        iter.close()
        false
    }
  }

  /**
   * A short circuited method to get a block writer that can write data directly to disk.
   * The Block will be appended to the File specified by filename. Callers should handle error
   * cases.
   */
  def getDiskWriter(
      blockId: BlockId,
      file: File,
      serializerInstance: SerializerInstance,
      bufferSize: Int,
      writeMetrics: ShuffleWriteMetrics): DiskBlockObjectWriter = {
    val syncWrites = conf.getBoolean("spark.shuffle.sync", false)
    new DiskBlockObjectWriter(file, serializerManager, serializerInstance, bufferSize,
      syncWrites, writeMetrics, blockId)
  }

  /**
   * Put a new block of serialized bytes to the block manager.
   *
   * 调用者不得更改或释放底层字节的数据缓冲区，如果这么做可能损坏或更改 BlockManager 存储的数据
   * 这里的 data buffer 指的是 ChunkedByteBuffer 嘛？
   * '''Important!''' Callers must not mutate or release the data buffer underlying `bytes`. Doing
   * so may corrupt or change the data stored by the `BlockManager`.
   *
   * @return true if the block was stored or false if an error occurred.
   */
  def putBytes[T: ClassTag](
      blockId: BlockId,
      bytes: ChunkedByteBuffer,
      level: StorageLevel,
      tellMaster: Boolean = true): Boolean = {
    require(bytes != null, "Bytes is null")
    doPutBytes(blockId, bytes, level, implicitly[ClassTag[T]], tellMaster)
  }

  /**
   * Put the given bytes according to the given level in one of the block stores, replicating
   * the values if necessary.
   *
   * If the block already exists, this method will not overwrite it.
   *
   * '''Important!''' Callers must not mutate or release the data buffer underlying `bytes`. Doing
   * so may corrupt or change the data stored by the `BlockManager`.
   *
   * @param keepReadLock if true, this method will hold the read lock when it returns (even if the
   *                     block already exists). If false, this method will hold no locks when it
   *                     returns.
   * @return true if the block was already present or if the put succeeded, false otherwise.
   */
  private def doPutBytes[T](
      blockId: BlockId,
      bytes: ChunkedByteBuffer,
      level: StorageLevel,
      classTag: ClassTag[T],
      tellMaster: Boolean = true,
      keepReadLock: Boolean = false): Boolean = {
    doPut(blockId, level, classTag, tellMaster = tellMaster, keepReadLock = keepReadLock) { info =>
      val startTimeMs = System.currentTimeMillis
      // 由于我们正在存储bytes类型的数据，所以在存储数据到本地之前，先开始副本分发。
      // 这个过程是比较快的，因为数据已经序列化并且已经可以发送了。
      // Since we're storing bytes, initiate the replication before storing them locally.
      // This is faster as data is already serialized and ready to send.
      // TODO 学习一下这个Future的语法
      val replicationFuture = if (level.replication > 1) {
        Future {
          // This is a blocking action and should run in futureExecutionContext which is a cached
          // thread pool. The ByteBufferBlockData wrapper is not disposed of to avoid releasing
          // buffers that are owned by the caller.
          // 会一直阻塞，直到复制完成
          replicate(blockId, new ByteBufferBlockData(bytes, false), level, classTag)
        }(futureExecutionContext)
      } else {
        null
      }

      val size = bytes.size

      if (level.useMemory) {
        // Put it in memory first, even if it also has useDisk set to true;
        // We will drop it to disk later if the memory store can't hold it.
        val putSucceeded = if (level.deserialized) {
          val values =
            serializerManager.dataDeserializeStream(blockId, bytes.toInputStream())(classTag)
          memoryStore.putIteratorAsValues(blockId, values, classTag) match {
              // scala 中神奇的语法了，是 Either 的两个实现
            case Right(_) => true
            case Left(iter) =>
              // If putting deserialized values in memory failed, we will put the bytes directly to
              // disk, so we don't need this iterator and can close it to free resources earlier.
              iter.close()
              false
          }
        } else {
          val memoryMode = level.memoryMode
          memoryStore.putBytes(blockId, size, memoryMode, () => {
            if (memoryMode == MemoryMode.OFF_HEAP &&
              // 如果chunks中有一个ByteBuffer是非Direct的就用
              // Platform.allocateDirectBuffer来申请DirectBuffer??? 啥意思???
                bytes.chunks.exists(buffer => !buffer.isDirect)) {
              bytes.copy(Platform.allocateDirectBuffer)
            } else {
              // 那else的意思就是说整个bytes都是DirectBuffer???
              bytes
            }
          })
        }
        // 如果在内存中存储失败，则尝试在磁盘中存储
        if (!putSucceeded && level.useDisk) {
          logWarning(s"Persisting block $blockId to disk instead.")
          diskStore.putBytes(blockId, bytes)
        }
      } else if (level.useDisk) {
        diskStore.putBytes(blockId, bytes)
      }

      // 获取Block当前的BlockStatus
      // 注意区分BlockStatus和BlockInfo的区别
      val putBlockStatus = getCurrentBlockStatus(blockId, info)
      // 如果刚刚获取的BlockStatus的storageLevel有效，则说明我们存储Block的成功了
      val blockWasSuccessfullyStored = putBlockStatus.storageLevel.isValid
      if (blockWasSuccessfullyStored) {
        // Now that the block is in either the memory or disk store,
        // tell the master about it.
        info.size = size
        if (tellMaster && info.tellMaster) {
          // 向master登记该block的相关信息
          reportBlockStatus(blockId, putBlockStatus)
        }
        addUpdatedBlockStatusToTaskMetrics(blockId, putBlockStatus)
      }
      logDebug("Put block %s locally took %s".format(blockId, Utils.getUsedTimeMs(startTimeMs)))
      // 如果该Block需要复制多个副本，则等待复制的任务完成
      if (level.replication > 1) {
        // Wait for asynchronous replication to finish
        try {
          ThreadUtils.awaitReady(replicationFuture, Duration.Inf)
        } catch {
          case NonFatal(t) =>
            throw new Exception("Error occurred while waiting for replication to finish", t)
        }
      }
      if (blockWasSuccessfullyStored) {
        None
      } else {
        Some(bytes)
      }
    }.isEmpty
  }

  /**
   * Helper method used to abstract common code from [[doPutBytes()]] and [[doPutIterator()]].
   *
   * @param putBody a function which attempts the actual put() and returns None on success
   *                or Some on failure.
   */
  private def doPut[T](
      blockId: BlockId,
      level: StorageLevel,
      classTag: ClassTag[_],
      tellMaster: Boolean,
      keepReadLock: Boolean)(putBody: BlockInfo => Option[T]): Option[T] = {

    require(blockId != null, "BlockId is null")
    require(level != null && level.isValid, "StorageLevel is null or invalid")

    val putBlockInfo = {
      // 创建新的BlockInfo对象
      val newInfo = new BlockInfo(level, classTag, tellMaster)
      // 如果成功获取到写锁，则返回刚刚创建的blockInfo（新的block的元数据）
      if (blockInfoManager.lockNewBlockForWriting(blockId, newInfo)) {
        newInfo
      } else {
        // 反之，说明，block已经存在，不能重复创建
        logWarning(s"Block $blockId already exists on this machine; not re-adding it")
        // 如果不要求继续持有读锁，那么，就释放掉（因为对于一个已经存在的block，
        // lockNewBlockForWriting方法会返回一个读锁）
        if (!keepReadLock) {
          // lockNewBlockForWriting returned a read lock on the existing block, so we must free it:
          releaseLock(blockId)
        }
        return None
      }
    }

    val startTimeMs = System.currentTimeMillis
    var exceptionWasThrown: Boolean = true
    val result: Option[T] = try {
      val res = putBody(putBlockInfo)
      exceptionWasThrown = false
      if (res.isEmpty) {
        // the block was successfully stored
        if (keepReadLock) {
          blockInfoManager.downgradeLock(blockId)
        } else {
          blockInfoManager.unlock(blockId)
        }
      } else {
        // 反之，block存储失败了，我们需要将其从"内部"移除
        removeBlockInternal(blockId, tellMaster = false)
        logWarning(s"Putting block $blockId failed")
      }
      res
    } catch {
      // Since removeBlockInternal may throw exception,
      // we should print exception first to show root cause.
      case NonFatal(e) =>
        logWarning(s"Putting block $blockId failed due to exception $e.")
        throw e
    } finally {
      // This cleanup is performed in a finally block rather than a `catch` to avoid having to
      // catch and properly re-throw InterruptedException.
      if (exceptionWasThrown) {
        // If an exception was thrown then it's possible that the code in `putBody` has already
        // notified the master about the availability of this block, so we need to send an update
        // to remove this block location.
        removeBlockInternal(blockId, tellMaster = tellMaster)
        // The `putBody` code may have also added a new block status to TaskMetrics, so we need
        // to cancel that out by overwriting it with an empty block status. We only do this if
        // the finally block was entered via an exception because doing this unconditionally would
        // cause us to send empty block statuses for every block that failed to be cached due to
        // a memory shortage (which is an expected failure, unlike an uncaught exception).
        addUpdatedBlockStatusToTaskMetrics(blockId, BlockStatus.empty)
      }
    }
    if (level.replication > 1) {
      logDebug("Putting block %s with replication took %s"
        .format(blockId, Utils.getUsedTimeMs(startTimeMs)))
    } else {
      logDebug("Putting block %s without replication took %s"
        .format(blockId, Utils.getUsedTimeMs(startTimeMs)))
    }
    result
  }

  /**
   * Put the given block according to the given level in one of the block stores, replicating
   * the values if necessary.
   *
   * If the block already exists, this method will not overwrite it.
   *
   * @param keepReadLock if true, this method will hold the read lock when it returns (even if the
   *                     block already exists). If false, this method will hold no locks when it
   *                     returns.
   * @return None if the block was already present or if the put succeeded, or Some(iterator)
   *         if the put failed.
   */
  private def doPutIterator[T](
      blockId: BlockId,
      iterator: () => Iterator[T],
      level: StorageLevel,
      classTag: ClassTag[T],
      tellMaster: Boolean = true,
      keepReadLock: Boolean = false): Option[PartiallyUnrolledIterator[T]] = {
    doPut(blockId, level, classTag, tellMaster = tellMaster, keepReadLock = keepReadLock) { info =>
      val startTimeMs = System.currentTimeMillis
      var iteratorFromFailedMemoryStorePut: Option[PartiallyUnrolledIterator[T]] = None
      // Size of the block in bytes
      var size = 0L
      if (level.useMemory) {
        // 优先存储至内存，即使可以也使用磁盘存储。如果内存存储不下，
        // 再存储（是全部存储到磁盘中，还是存不下的那部分存到磁盘中？？？）到磁盘中。
        // Put it in memory first, even if it also has useDisk set to true;
        // We will drop it to disk later if the memory store can't hold it.

        // 如果反序列化了，则直接存储value???
        if (level.deserialized) {
          memoryStore.putIteratorAsValues(blockId, iterator(), classTag) match {
            case Right(s) =>
              size = s
            case Left(iter) =>
              // Not enough space to unroll this block; drop to disk if applicable
              if (level.useDisk) {
                logWarning(s"Persisting block $blockId to disk instead.")
                diskStore.put(blockId) { channel =>
                  val out = Channels.newOutputStream(channel)
                  serializerManager.dataSerializeStream(blockId, out, iter)(classTag)
                }
                size = diskStore.getSize(blockId)
              } else {
                iteratorFromFailedMemoryStorePut = Some(iter)
              }
          }
        } else { // !level.deserialized
          memoryStore.putIteratorAsBytes(blockId, iterator(), classTag, level.memoryMode) match {
            case Right(s) =>
              size = s
            case Left(partiallySerializedValues) =>
              // Not enough space to unroll this block; drop to disk if applicable
              if (level.useDisk) {
                logWarning(s"Persisting block $blockId to disk instead.")
                diskStore.put(blockId) { channel =>
                  val out = Channels.newOutputStream(channel)
                  partiallySerializedValues.finishWritingToStream(out)
                }
                size = diskStore.getSize(blockId)
              } else {
                iteratorFromFailedMemoryStorePut = Some(partiallySerializedValues.valuesIterator)
              }
          }
        }

      } else if (level.useDisk) {
        diskStore.put(blockId) { channel =>
          val out = Channels.newOutputStream(channel)
          serializerManager.dataSerializeStream(blockId, out, iterator())(classTag)
        }
        size = diskStore.getSize(blockId)
      }

      val putBlockStatus = getCurrentBlockStatus(blockId, info)
      val blockWasSuccessfullyStored = putBlockStatus.storageLevel.isValid
      if (blockWasSuccessfullyStored) {
        // Now that the block is in either the memory or disk store, tell the master about it.
        info.size = size
        if (tellMaster && info.tellMaster) {
          reportBlockStatus(blockId, putBlockStatus)
        }
        addUpdatedBlockStatusToTaskMetrics(blockId, putBlockStatus)
        logDebug("Put block %s locally took %s".format(blockId, Utils.getUsedTimeMs(startTimeMs)))
        if (level.replication > 1) {
          val remoteStartTime = System.currentTimeMillis
          val bytesToReplicate = doGetLocalBytes(blockId, info)
          // [SPARK-16550] Erase the typed classTag when using default serialization, since
          // NettyBlockRpcServer crashes when deserializing repl-defined classes.
          // TODO(ekl) remove this once the classloader issue on the remote end is fixed.
          val remoteClassTag = if (!serializerManager.canUseKryo(classTag)) {
            scala.reflect.classTag[Any]
          } else {
            classTag
          }
          try {
            replicate(blockId, bytesToReplicate, level, remoteClassTag)
          } finally {
            bytesToReplicate.dispose()
          }
          logDebug("Put block %s remotely took %s"
            .format(blockId, Utils.getUsedTimeMs(remoteStartTime)))
        }
      }
      assert(blockWasSuccessfullyStored == iteratorFromFailedMemoryStorePut.isEmpty)
      iteratorFromFailedMemoryStorePut
    }
  }

  /**
   * Attempts to cache spilled bytes read from disk into the MemoryStore in order to speed up
   * subsequent reads. This method requires the caller to hold a read lock on the block.
   *
   * @return a copy of the bytes from the memory store if the put succeeded, otherwise None.
   *         If this returns bytes from the memory store then the original disk store bytes will
   *         automatically be disposed and the caller should not continue to use them. Otherwise,
   *         if this returns None then the original disk store bytes will be unaffected.
   */
  private def maybeCacheDiskBytesInMemory(
      blockInfo: BlockInfo,
      blockId: BlockId,
      level: StorageLevel,
      diskData: BlockData): Option[ChunkedByteBuffer] = {
    require(!level.deserialized)
    if (level.useMemory) {
      // Synchronize on blockInfo to guard against a race condition where two readers both try to
      // put values read from disk into the MemoryStore.
      blockInfo.synchronized {
        if (memoryStore.contains(blockId)) {
          diskData.dispose()
          Some(memoryStore.getBytes(blockId).get)
        } else {
          val allocator = level.memoryMode match {
            case MemoryMode.ON_HEAP => ByteBuffer.allocate _
            case MemoryMode.OFF_HEAP => Platform.allocateDirectBuffer _
          }
          val putSucceeded = memoryStore.putBytes(blockId, diskData.size, level.memoryMode, () => {
            // https://issues.apache.org/jira/browse/SPARK-6076
            // If the file size is bigger than the free memory, OOM will happen. So if we
            // cannot put it into MemoryStore, copyForMemory should not be created. That's why
            // this action is put into a `() => ChunkedByteBuffer` and created lazily.
            diskData.toChunkedByteBuffer(allocator)
          })
          if (putSucceeded) {
            diskData.dispose()
            Some(memoryStore.getBytes(blockId).get)
          } else {
            None
          }
        }
      }
    } else {
      None
    }
  }

  /**
   * 尝试把从磁盘读取的溢出值缓存到memoryStore中去，以加速之后的读操作。
   * Attempts to cache spilled values read from disk into the MemoryStore in order to speed up
   * subsequent reads. This method requires the caller to hold a read lock on the block.
   *
   * @return a copy of the iterator. The original iterator passed this method should no longer
   *         be used after this method returns.
   */
  private def maybeCacheDiskValuesInMemory[T](
      blockInfo: BlockInfo,
      blockId: BlockId,
      level: StorageLevel,
      diskIterator: Iterator[T]): Iterator[T] = {
    require(level.deserialized)
    val classTag = blockInfo.classTag.asInstanceOf[ClassTag[T]]
    if (level.useMemory) {
      // Synchronize on blockInfo to guard against a race condition where two readers both try to
      // put values read from disk into the MemoryStore.
      blockInfo.synchronized {
        if (memoryStore.contains(blockId)) {
          // Note: if we had a means to discard the disk iterator, we would do that here.
          memoryStore.getValues(blockId).get
        } else {
          memoryStore.putIteratorAsValues(blockId, diskIterator, classTag) match {
            case Left(iter) =>
              // TODO 失败不应该释放unroll memory吗?
              // 答：如果后面有对iter遍历，那么，等iter的urolled元素遍历完成时，也会释放的
              // The memory store put() failed, so it returned the iterator back to us:
              iter
            case Right(_) =>
              // The put() succeeded, so we can read the values back:
              memoryStore.getValues(blockId).get
          }
        }
      }.asInstanceOf[Iterator[T]]
    } else {
      diskIterator
    }
  }

  /**
   * Get peer block managers in the system.
   */
  private def getPeers(forceFetch: Boolean): Seq[BlockManagerId] = {
    peerFetchLock.synchronized {
      val cachedPeersTtl = conf.getInt("spark.storage.cachedPeersTtl", 60 * 1000) // milliseconds
      val timeout = System.currentTimeMillis - lastPeerFetchTime > cachedPeersTtl
      // 如果缓存的cachedPeers为空（说明我们还从来没有获取过peer），或者forceFetch=true(强制获取peers，
      // 比如我们要复制一个block的时候)，或者timeout了（距离上一个获取peers已经过滤很久，可能有些executors都
      // 挂掉了呢，我们需要重新了解一下当前peers的情况）
      if (cachedPeers == null || forceFetch || timeout) {
        cachedPeers = master.getPeers(blockManagerId).sortBy(_.hashCode)
        lastPeerFetchTime = System.currentTimeMillis
        logDebug("Fetched peers from master: " + cachedPeers.mkString("[", ",", "]"))
      }
      cachedPeers
    }
  }

  /**
   * Called for pro-active replenishment of blocks lost due to executor failures
   *
   * @param blockId blockId being replicate
   * @param existingReplicas existing block managers that have a replica
   * @param maxReplicas maximum replicas needed
   */
  def replicateBlock(
      blockId: BlockId,
      existingReplicas: Set[BlockManagerId],
      maxReplicas: Int): Unit = {
    logInfo(s"Using $blockManagerId to pro-actively replicate $blockId")
    // 先通过lockForReading获取该（blockId对应的）Block的读锁
    blockInfoManager.lockForReading(blockId).foreach { info =>
      // 从本地获取该Block data
      val data = doGetLocalBytes(blockId, info)
      val storageLevel = StorageLevel(
        useDisk = info.level.useDisk,
        useMemory = info.level.useMemory,
        useOffHeap = info.level.useOffHeap,
        deserialized = info.level.deserialized,
        replication = maxReplicas)
      // we know we are called as a result of an executor removal, so we refresh peer cache
      // this way, we won't try to replicate to a missing executor with a stale reference
      getPeers(forceFetch = true)
      try {
        replicate(blockId, data, storageLevel, info.classTag, existingReplicas)
      } finally {
        logDebug(s"Releasing lock for $blockId")
        // TODO read releaseLockAndDispose
        releaseLockAndDispose(blockId, data)
      }
    }
  }

  /**
   * 复制block到其它的节点。注意：这是一个阻塞的调用，直到block复制完成才会返回。
   * 阻塞是不是为了保证数据的一致性呢???
   * Replicate block to another node. Note that this is a blocking call that returns after
   * the block has been replicated.
   */
  private def replicate(
      blockId: BlockId,
      data: BlockData,
      level: StorageLevel,
      classTag: ClassTag[_],
      existingReplicas: Set[BlockManagerId] = Set.empty): Unit = {

    // 复制副本时最大的失败次数
    val maxReplicationFailures = conf.getInt("spark.storage.maxReplicationFailures", 1)
    val tLevel = StorageLevel(
      useDisk = level.useDisk,
      useMemory = level.useMemory,
      useOffHeap = level.useOffHeap,
      deserialized = level.deserialized,
      replication = 1) // 在这里replication又被设置为1了...

    // 需要达成的副本复制的个数(也就是副本的总个数减去本地的那一份)
    val numPeersToReplicateTo = level.replication - 1
    val startTime = System.nanoTime

    // 已经复制了该block的peers
    val peersReplicatedTo = mutable.HashSet.empty ++ existingReplicas
    val peersFailedToReplicateTo = mutable.HashSet.empty[BlockManagerId]
    var numFailures = 0

    // 获取同为peer的BlockManagerIds，且过滤掉已经有该block副本的BlockManagerIds
    val initialPeers = getPeers(false).filterNot(existingReplicas.contains)

    // 根据块复制策略(默认随机块复制策略)选择哪些BlockManager作为复制的目标BlockManager。因为peers
    // 是除了自己即driver外的所有BlockManager(显然，绝大多数时候大于副本的个数)，所以我们需要通过
    // 块复制策略，挑几个BlockManager进行复制
    var peersForReplication = blockReplicationPolicy.prioritize(
      blockManagerId,
      initialPeers,
      peersReplicatedTo,
      blockId,
      numPeersToReplicateTo)

    while(numFailures <= maxReplicationFailures &&
      !peersForReplication.isEmpty &&
      peersReplicatedTo.size < numPeersToReplicateTo) {
      val peer = peersForReplication.head
      try {
        val onePeerStartTime = System.nanoTime
        logTrace(s"Trying to replicate $blockId of ${data.size} bytes to $peer")
        // 通过blockTransferService把block复制到目标peer
        // TODO read blockTransferService#uploadBlcokSync
        blockTransferService.uploadBlockSync(
          peer.host,
          peer.port,
          peer.executorId,
          blockId,
          new BlockManagerManagedBuffer(blockInfoManager, blockId, data, false),
          tLevel,
          classTag)
        logTrace(s"Replicated $blockId of ${data.size} bytes to $peer" +
          s" in ${(System.nanoTime - onePeerStartTime).toDouble / 1e6} ms")
        peersForReplication = peersForReplication.tail
        peersReplicatedTo += peer
      } catch {
        case NonFatal(e) =>
          logWarning(s"Failed to replicate $blockId to $peer, failure #$numFailures", e)
          peersFailedToReplicateTo += peer
          // 每次失败都要立即重新获取peers吗??? 不等等peersForReplication里其它的peer情况吗???
          // 比如，哎呀连续试了3次之后，还是复制失败，那么，我们再重新获取peers
          // we have a failed replication, so we get the list of peers again
          // we don't want peers we have already replicated to and the ones that
          // have failed previously
          val filteredPeers = getPeers(true).filter { p =>
            !peersFailedToReplicateTo.contains(p) && !peersReplicatedTo.contains(p)
          }

          numFailures += 1
          // 如果失败一次，会重新打乱peers
          peersForReplication = blockReplicationPolicy.prioritize(
            blockManagerId,
            filteredPeers,
            peersReplicatedTo,
            blockId,
            numPeersToReplicateTo - peersReplicatedTo.size)
      }
    }
    logDebug(s"Replicating $blockId of ${data.size} bytes to " +
      s"${peersReplicatedTo.size} peer(s) took ${(System.nanoTime - startTime) / 1e6} ms")
    if (peersReplicatedTo.size < numPeersToReplicateTo) {
      logWarning(s"Block $blockId replicated to only " +
        s"${peersReplicatedTo.size} peer(s) instead of $numPeersToReplicateTo peers")
    }

    logDebug(s"block $blockId replicated to ${peersReplicatedTo.mkString(", ")}")
  }

  /**
   * Read a block consisting of a single object.
   */
  def getSingle[T: ClassTag](blockId: BlockId): Option[T] = {
    get[T](blockId).map(_.data.next().asInstanceOf[T])
  }

  /**
   * Write a block consisting of a single object.
   *
   * @return true if the block was stored or false if the block was already stored or an
   *         error occurred.
   */
  def putSingle[T: ClassTag](
      blockId: BlockId,
      value: T,
      level: StorageLevel,
      tellMaster: Boolean = true): Boolean = {
    // 把value转换成一个Iterator，以适用putIterator()
    // 注意：Iterator(List(1,2,3))返回的iterator只包含一个元素: List(1,2,3)
    // 而： List(1,2,3).iterator返回的iterator包含三个元素：1,2,3
    // TODO 那么，如果传进来的是一个List(或Seq)类型的value，却把它当做一个元素看待，
    // 则对于之后的unroll操作其实是不起作用的。这里是否可以优化一下???
    putIterator(blockId, Iterator(value), level, tellMaster)
  }

  /**
   * Drop a block from memory, possibly putting it on disk if applicable. Called when the memory
   * store reaches its limit and needs to free up space.
   *
   * If `data` is not put on disk, it won't be created.
   *
   * The caller of this method must hold a write lock on the block before calling this method.
   * This method does not release the write lock.
   *
   * @return the block's new effective StorageLevel.
   */
  private[storage] override def dropFromMemory[T: ClassTag](
      blockId: BlockId,
      data: () => Either[Array[T], ChunkedByteBuffer]): StorageLevel = {
    logInfo(s"Dropping block $blockId from memory")
    // 首先确认该block是否被写锁锁定，如果锁定，则返回其blockInfo
    val info = blockInfoManager.assertBlockIsLockedForWriting(blockId)
    var blockIsUpdated = false
    val level = info.level

    // 如果该block可以使用磁盘存储，且diskStore不包含该block
    // Drop to disk, if storage level requires
    if (level.useDisk && !diskStore.contains(blockId)) {
      logInfo(s"Writing block $blockId to disk")
      // 这个data()的语法又看不懂了???
      // 将该block以字节流的形式（如果data是value，会先序列化为字节流）写入磁盘
      // TODO read 磁盘写入操作
      data() match {
        case Left(elements) =>
          diskStore.put(blockId) { channel =>
            // TODO 很多这种channel啊，stream啊，bytebuffer啊都不会应用
            val out = Channels.newOutputStream(channel)
            // 因为要写入磁盘，所以要把value序列化成流
            serializerManager.dataSerializeStream(
              blockId,
              out,
              elements.toIterator)(info.classTag.asInstanceOf[ClassTag[T]])
          }
        case Right(bytes) =>
          diskStore.putBytes(blockId, bytes)
      }
      blockIsUpdated = true
    }

    // 执行真正的从内存中删除该block的操作！
    // Actually drop from memory store
    // 那么问题来了，如果该block在之前没有写入磁盘，然后就直接从内存中删除了吗???
    // (答：see detail：MemorySore#dropBlock#L540，如果storageLevel不支持使用磁盘的话，那么该Block就完全被删除了)
    val droppedMemorySize =
      if (memoryStore.contains(blockId)) memoryStore.getSize(blockId) else 0L
    val blockIsRemoved = memoryStore.remove(blockId)
    if (blockIsRemoved) {
      blockIsUpdated = true
    } else {
      logWarning(s"Block $blockId could not be dropped from memory as it does not exist")
    }

    val status = getCurrentBlockStatus(blockId, info)
    // whether state changes for this block should be reported to the master. This
    // is true for most blocks, but is false for broadcast blocks.
    if (info.tellMaster) {
      reportBlockStatus(blockId, status, droppedMemorySize)
    }
    if (blockIsUpdated) {
      addUpdatedBlockStatusToTaskMetrics(blockId, status)
    }
    status.storageLevel
  }

  /**
   * Remove all blocks belonging to the given RDD.
   *
   * @return The number of blocks removed.
   */
  def removeRdd(rddId: Int): Int = {
    // TODO: Avoid a linear scan by creating another mapping of RDD.id to blocks.
    logInfo(s"Removing RDD $rddId")
    val blocksToRemove = blockInfoManager.entries.flatMap(_._1.asRDDId).filter(_.rddId == rddId)
    blocksToRemove.foreach { blockId => removeBlock(blockId, tellMaster = false) }
    blocksToRemove.size
  }

  /**
   * Remove all blocks belonging to the given broadcast.
   */
  def removeBroadcast(broadcastId: Long, tellMaster: Boolean): Int = {
    logDebug(s"Removing broadcast $broadcastId")
    val blocksToRemove = blockInfoManager.entries.map(_._1).collect {
      case bid @ BroadcastBlockId(`broadcastId`, _) => bid
    }
    blocksToRemove.foreach { blockId => removeBlock(blockId, tellMaster) }
    blocksToRemove.size
  }

  /**
   * Remove a block from both memory and disk.
   */
  def removeBlock(blockId: BlockId, tellMaster: Boolean = true): Unit = {
    logDebug(s"Removing block $blockId")
    blockInfoManager.lockForWriting(blockId) match {
      case None =>
        // The block has already been removed; do nothing.
        logWarning(s"Asked to remove block $blockId, which does not exist")
      case Some(info) =>
        removeBlockInternal(blockId, tellMaster = tellMaster && info.tellMaster)
        addUpdatedBlockStatusToTaskMetrics(blockId, BlockStatus.empty)
    }
  }

  /**
   * Internal version of [[removeBlock()]] which assumes that the caller already holds a write
   * lock on the block.
   */
  private def removeBlockInternal(blockId: BlockId, tellMaster: Boolean): Unit = {
    // Removals are idempotent in disk store and memory store. At worst, we get a warning.
    val removedFromMemory = memoryStore.remove(blockId)
    val removedFromDisk = diskStore.remove(blockId)
    if (!removedFromMemory && !removedFromDisk) {
      logWarning(s"Block $blockId could not be removed as it was not found on disk or in memory")
    }
    // 从blockInfoManager移除该Block，并且需要释放在该Block上的写锁
    blockInfoManager.removeBlock(blockId)
    if (tellMaster) {
      reportBlockStatus(blockId, BlockStatus.empty)
    }
  }

  private def addUpdatedBlockStatusToTaskMetrics(blockId: BlockId, status: BlockStatus): Unit = {
    if (conf.get(config.TASK_METRICS_TRACK_UPDATED_BLOCK_STATUSES)) {
      Option(TaskContext.get()).foreach { c =>
        c.taskMetrics().incUpdatedBlockStatuses(blockId -> status)
      }
    }
  }

  def releaseLockAndDispose(
      blockId: BlockId,
      data: BlockData,
      taskAttemptId: Option[Long] = None): Unit = {
    releaseLock(blockId, taskAttemptId)
    data.dispose()
  }

  def stop(): Unit = {
    blockTransferService.close()
    if (shuffleClient ne blockTransferService) {
      // Closing should be idempotent, but maybe not for the NioBlockTransferService.
      shuffleClient.close()
    }
    remoteBlockTempFileManager.stop()
    diskBlockManager.stop()
    rpcEnv.stop(slaveEndpoint)
    blockInfoManager.clear()
    memoryStore.clear()
    futureExecutionContext.shutdownNow()
    logInfo("BlockManager stopped")
  }
}


private[spark] object BlockManager {
  private val ID_GENERATOR = new IdGenerator

  def blockIdsToHosts(
      blockIds: Array[BlockId],
      env: SparkEnv,
      blockManagerMaster: BlockManagerMaster = null): Map[BlockId, Seq[String]] = {

    // blockManagerMaster != null is used in tests
    assert(env != null || blockManagerMaster != null)
    val blockLocations: Seq[Seq[BlockManagerId]] = if (blockManagerMaster == null) {
      env.blockManager.getLocationBlockIds(blockIds)
    } else {
      blockManagerMaster.getLocations(blockIds)
    }

    val blockManagers = new HashMap[BlockId, Seq[String]]
    for (i <- 0 until blockIds.length) {
      blockManagers(blockIds(i)) = blockLocations(i).map(_.host)
    }
    blockManagers.toMap
  }

  private class ShuffleMetricsSource(
      override val sourceName: String,
      metricSet: MetricSet) extends Source {

    override val metricRegistry = new MetricRegistry
    metricRegistry.registerAll(metricSet)
  }

  class RemoteBlockTempFileManager(blockManager: BlockManager)
      extends TempFileManager with Logging {

    private class ReferenceWithCleanup(file: File, referenceQueue: JReferenceQueue[File])
        extends WeakReference[File](file, referenceQueue) {
      private val filePath = file.getAbsolutePath

      def cleanUp(): Unit = {
        logDebug(s"Clean up file $filePath")

        if (!new File(filePath).delete()) {
          logDebug(s"Fail to delete file $filePath")
        }
      }
    }

    private val referenceQueue = new JReferenceQueue[File]
    private val referenceBuffer = Collections.newSetFromMap[ReferenceWithCleanup](
      new ConcurrentHashMap)

    private val POLL_TIMEOUT = 1000
    @volatile private var stopped = false

    private val cleaningThread = new Thread() { override def run() { keepCleaning() } }
    cleaningThread.setDaemon(true)
    cleaningThread.setName("RemoteBlock-temp-file-clean-thread")
    cleaningThread.start()

    override def createTempFile(): File = {
      blockManager.diskBlockManager.createTempLocalBlock()._2
    }

    override def registerTempFileToClean(file: File): Boolean = {
      referenceBuffer.add(new ReferenceWithCleanup(file, referenceQueue))
    }

    def stop(): Unit = {
      stopped = true
      cleaningThread.interrupt()
      cleaningThread.join()
    }

    private def keepCleaning(): Unit = {
      while (!stopped) {
        try {
          Option(referenceQueue.remove(POLL_TIMEOUT))
            .map(_.asInstanceOf[ReferenceWithCleanup])
            .foreach { ref =>
              referenceBuffer.remove(ref)
              ref.cleanUp()
            }
        } catch {
          case _: InterruptedException =>
            // no-op
          case NonFatal(e) =>
            logError("Error in cleaning thread", e)
        }
      }
    }
  }
}
