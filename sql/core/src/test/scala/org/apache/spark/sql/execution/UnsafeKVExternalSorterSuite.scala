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

package org.apache.spark.sql.execution

import scala.util.Random

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.UnsafeProjection
import org.apache.spark.sql.test.TestSQLContext
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.memory.{ExecutorMemoryManager, MemoryAllocator, TaskMemoryManager}
import org.apache.spark.unsafe.types.UTF8String
import org.apache.spark._

class UnsafeKVExternalSorterSuite extends SparkFunSuite {

  test("sorting arbitrary string data") {

    // Calling this make sure we have block manager and everything else setup.
    TestSQLContext

    val taskMemMgr = new TaskMemoryManager(new ExecutorMemoryManager(MemoryAllocator.HEAP))
    val shuffleMemMgr = new TestShuffleMemoryManager

    TaskContext.setTaskContext(new TaskContextImpl(
      stageId = 0,
      partitionId = 0,
      taskAttemptId = 0,
      attemptNumber = 0,
      taskMemoryManager = taskMemMgr,
      metricsSystem = null))

    val keySchema = new StructType().add("a", StringType)
    val valueSchema = new StructType().add("b", IntegerType)
    val sorter = new UnsafeKVExternalSorter(
      keySchema, valueSchema, SparkEnv.get.blockManager, shuffleMemMgr,
      16 * 1024)

    val keyConverter = UnsafeProjection.create(keySchema)
    val valueConverter = UnsafeProjection.create(valueSchema)

    val rand = new Random(42)
    val data = Seq.fill(512) {
      Seq.fill(rand.nextInt(100))(rand.nextPrintableChar()).mkString
    }

    var i = 0
    data.foreach { str =>
      val k = InternalRow(UTF8String.fromString(str))
      val v = InternalRow(str.length)
      sorter.insertKV(keyConverter.apply(k), valueConverter.apply(v))

      if ((i % 10) == 0) {
        shuffleMemMgr.markAsOutOfMemory()
        sorter.closeCurrentPage()
      }
      i += 1
    }

    val out = new scala.collection.mutable.ArrayBuffer[String]
    val iter = sorter.sortedIterator()
    while (iter.next()) {
      assert(iter.getKey.getString(0).length === iter.getValue.getInt(0))
      out += iter.getKey.getString(0)
    }

    assert(out === data.sorted)
  }
}
