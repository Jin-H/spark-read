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

package org.apache.spark.scheduler

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

import com.codahale.metrics.{Gauge, Timer}

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.internal.Logging
import org.apache.spark.internal.config._
import org.apache.spark.util.Utils

/**
 * An asynchronous queue for events. All events posted to this queue will be delivered to the child
 * listeners in a separate thread.
 *
 * Delivery will only begin when the `start()` method is called. The `stop()` method should be
 * called when no more events need to be delivered.
 */
private class AsyncEventQueue(val name: String, conf: SparkConf, metrics: LiveListenerBusMetrics)
  extends SparkListenerBus
  with Logging {

  import AsyncEventQueue._

  // Cap the capacity of the queue so we get an explicit error (rather than an OOM exception) if
  // it's perpetually being added to more quickly than it's being drained.
  // LinkedBlockingQueue线程安全
  private val eventQueue = new LinkedBlockingQueue[SparkListenerEvent](
    conf.get(LISTENER_BUS_EVENT_QUEUE_CAPACITY))

    // Keep the event count separately, so that waitUntilEmpty() can be implemented properly;
  // this allows that method to return only when the events in the queue have been fully
  // processed (instead of just dequeued).
  private val eventCount = new AtomicLong()

  // 被抛弃（可能由于eventQueue已满造成）的事件的计数，每次log后，会被重置为0
  /** A counter for dropped events. It will be reset every time we log it. */
  private val droppedEventsCounter = new AtomicLong(0L)

  /** When `droppedEventsCounter` was logged last time in milliseconds. */
  @volatile private var lastReportTimestamp = 0L

  private val logDroppedEvent = new AtomicBoolean(false)

  private var sc: SparkContext = null

  private val started = new AtomicBoolean(false)
  private val stopped = new AtomicBoolean(false)

  // 不同于droppedEventsCounter， droppedEvents不会被重置（应该是在应用执行期间一直增长）
  private val droppedEvents = metrics.metricRegistry.counter(s"queue.$name.numDroppedEvents")
  private val processingTime = metrics.metricRegistry.timer(s"queue.$name.listenerProcessingTime")

  // Remove the queue size gauge first, in case it was created by a previous incarnation of
  // this queue that was removed from the listener bus.
  metrics.metricRegistry.remove(s"queue.$name.size")
  metrics.metricRegistry.register(s"queue.$name.size", new Gauge[Int] {
    override def getValue: Int = eventQueue.size()
  })
  // 分发消息事件线程
  private val dispatchThread = new Thread(s"spark-listener-group-$name") {
    setDaemon(true)
    override def run(): Unit = Utils.tryOrStopSparkContext(sc) {
      dispatch()
    }
  }

                                                          // 看不懂这个语法啊....
  private def dispatch(): Unit = LiveListenerBus.withinListenerThread.withValue(true) {
    try {
      // 当eventQueue为空时，会阻塞
      var next: SparkListenerEvent = eventQueue.take()
      // POISON_PILLS是Spark的一种执行终止策略：当stop()被调用时，就会把POISON_PILL丢入队列中，以让线程退出循环
      // 不同于Dispatcher中的一点是：Dispatcher分发消息时是多线程，所以需要把取出来的POISON_PILL再放回去
      while (next != POISON_PILL) {
        val ctx = processingTime.time()
        try {
          super.postToAll(next)
        } finally {
          ctx.stop()
        }
        eventCount.decrementAndGet()
        next = eventQueue.take()
      }
      eventCount.decrementAndGet()
    } catch {
      case ie: InterruptedException =>
        logInfo(s"Stopping listener queue $name.", ie)
    }
  }

  override protected def getTimer(listener: SparkListenerInterface): Option[Timer] = {
    metrics.getTimerForListenerClass(listener.getClass.asSubclass(classOf[SparkListenerInterface]))
  }

  /**
   * Start an asynchronous thread to dispatch events to the underlying listeners.
   *
   * @param sc Used to stop the SparkContext in case the async dispatcher fails.
   */
  private[scheduler] def start(sc: SparkContext): Unit = {
    if (started.compareAndSet(false, true)) {
      this.sc = sc
      dispatchThread.start()
    } else {
      throw new IllegalStateException(s"$name already started!")
    }
  }

  /**
   * Stop the listener bus. It will wait until the queued events have been processed, but new
   * events will be dropped.
   */
  private[scheduler] def stop(): Unit = {
    if (!started.get()) {
      throw new IllegalStateException(s"Attempted to stop $name that has not yet started!")
    }
    if (stopped.compareAndSet(false, true)) {
      eventCount.incrementAndGet()
      eventQueue.put(POISON_PILL)
    }
    dispatchThread.join()
  }

  def post(event: SparkListenerEvent): Unit = {
    if (stopped.get()) {
      return
    }

    eventCount.incrementAndGet()
    // 如果队列已满，返回false
    if (eventQueue.offer(event)) {
      return
    }

    eventCount.decrementAndGet()
    droppedEvents.inc()
    droppedEventsCounter.incrementAndGet()
    // 这个logDroppedEvent只会赋值一次（所以如果之后eventQueue经历不满又满过程的时候，这个log是不会有了）
    if (logDroppedEvent.compareAndSet(false, true)) {
      // Only log the following message once to avoid duplicated annoying logs.
      logError(s"Dropping event from queue $name. " +
        "This likely means one of the listeners is too slow and cannot keep up with " +
        "the rate at which tasks are being started by the scheduler.")
    }
    logTrace(s"Dropping event $event")

    val droppedCount = droppedEventsCounter.get
    if (droppedCount > 0) {
      // Don't log too frequently（超过600000ms才log，那一分钟这个值又是怎么来的？经验咯）
      if (System.currentTimeMillis() - lastReportTimestamp >= 60 * 1000) {
        // There may be multiple threads trying to decrease droppedEventsCounter.
        // Use "compareAndSet" to make sure only one thread can win.
        // And if another thread is increasing droppedEventsCounter, "compareAndSet" will fail and
        // then that thread will update it.
        // 可能会有多个线程想要重置droppedEventsCounter，但是compareAndSet保证了只有一个线程可以重置该变量
        // 且，如果有另一个线程正在给droppedEventsCounter加1，那么，其它线程的compareAndSet都会失败，但是
        // 正在给droppedEventsCounter加1的那个线程，最终会重置droppedEventsCounter(这个设计好巧妙)
        if (droppedEventsCounter.compareAndSet(droppedCount, 0)) {
          val prevLastReportTimestamp = lastReportTimestamp
          lastReportTimestamp = System.currentTimeMillis()
          val previous = new java.util.Date(prevLastReportTimestamp)
          logWarning(s"Dropped $droppedEvents events from $name since $previous.")
        }
      }
    }
  }

  /**
   * For testing only. Wait until there are no more events in the queue.
   *
   * @return true if the queue is empty.
   */
  def waitUntilEmpty(deadline: Long): Boolean = {
    while (eventCount.get() != 0) {
      if (System.currentTimeMillis > deadline) {
        return false
      }
      Thread.sleep(10)
    }
    true
  }

}

private object AsyncEventQueue {

  val POISON_PILL = new SparkListenerEvent() { }

}
