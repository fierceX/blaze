/*
 * Copyright 2022 The Blaze Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.blaze.arrowio

import scala.collection.mutable.ArrayBuffer
import scala.collection.JavaConverters._

import org.apache.arrow.c.ArrowArray
import org.apache.arrow.c.ArrowSchema
import org.apache.arrow.c.CDataDictionaryProvider
import org.apache.arrow.c.Data
import org.apache.arrow.c.NativeUtil
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.spark.sql.blaze.BlazeCallNativeWrapper
import org.apache.spark.TaskContext
import org.apache.spark.sql.execution.blaze.arrowio.util2.ArrowUtils
import org.apache.spark.sql.execution.blaze.arrowio.ColumnarHelper.rootAsBatch
import org.apache.spark.sql.vectorized.ColumnarBatch

class ArrowFFIImportIterator(wrapper: BlazeCallNativeWrapper, taskContext: TaskContext)
    extends Iterator[ColumnarBatch] {

  private var allocator =
    ArrowUtils.rootAllocator.newChildAllocator("arrowFFIImportIterator", 0, Long.MaxValue)
  private val emptyDictionaryProvider = new CDataDictionaryProvider()
  var consumerSchema: ArrowSchema = _
  var consumerArray: ArrowArray = _
  var consumed = true
  var cachedBatches = new ArrayBuffer[ColumnarBatch]
  var root: VectorSchemaRoot = _
  taskContext.addTaskCompletionListener[Unit](_ => close())

  override def hasNext: Boolean = {
    if (allocator == null) {
      return false
    }
    if (!consumed) {
      return true
    }
    closeConsumerArrayAndSchema()
    consumerSchema = ArrowSchema.allocateNew(allocator)
    consumerArray = ArrowArray.allocateNew(allocator)
    val hasNext =
      wrapper.nextBatch(consumerSchema.memoryAddress(), consumerArray.memoryAddress())
    if (hasNext) {
      consumed = false
      true

    } else {
      close()
      false
    }
  }

  override def next(): ColumnarBatch = {
    if (root == null) {
      val schema = Data.importSchema(allocator, consumerSchema, emptyDictionaryProvider)
      root = VectorSchemaRoot.create(schema, allocator)
    } else {
      root.allocateNew()
    }

    // NOTE: walk-around without getting the incorrect n_buffers error
    // in Data.importIntoVectorSchemaRoot()
    val childrenPtr = NativeUtil.toJavaArray(
      consumerArray.snapshot.children,
      consumerArray.snapshot.n_children.toInt)
    val childVectors = root.getFieldVectors
    if (childrenPtr != null && childrenPtr.nonEmpty) {
      childrenPtr
        .zip(childVectors.asScala)
        .foreach {
          case (childPtr, vector) =>
            val child = ArrowArray.wrap(childPtr)
            Data.importIntoVector(allocator, child, vector, emptyDictionaryProvider)
        }
      root.setRowCount(root.getVector(0).getValueCount)
    }
    val batch = rootAsBatch(root)

    consumed = true
    cachedBatches += batch
    batch
  }

  private def close(): Unit =
    synchronized {
      if (allocator != null) {
        closeConsumerArrayAndSchema()
        wrapper.finish()

        if (cachedBatches != null) {
          cachedBatches.foreach(_.close())
          cachedBatches.clear()
          cachedBatches = null
        }
        if (root != null) {
          root.close()
        }
        allocator.close()
        allocator = null
      }
    }

  private def closeConsumerArrayAndSchema(): Unit = {
    if (consumerSchema != null) {
      consumerSchema.close()
      consumerSchema = null
    }
    if (consumerArray != null) {
      consumerArray.close()
      consumerArray = null
    }
  }
}
