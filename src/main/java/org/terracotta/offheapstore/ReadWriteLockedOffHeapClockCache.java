/*
 * Copyright 2015 Terracotta, Inc., a Software AG company.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terracotta.offheapstore;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.terracotta.offheapstore.paging.PageSource;
import org.terracotta.offheapstore.storage.StorageEngine;

/**
 * A concurrent-read, exclusive-write off-heap clock cache.
 * 一个并发读、排他写堆时钟缓存。
 * <p>
 * This cache uses one of the unused bits in the off-heap entry's status value to
 * store the clock data.  This clock data is racily to updated during read
 * operations. Since clock eviction data resides in the hash-map's table, it is
 * correctly copied across during table resize operations.
 * 此缓存使用堆外条目状态值中的一个未使用位来存储时钟数据。
 * 该时钟数据在读取操作期间快速更新。
 * 由于时钟逐出数据驻留在哈希映射的表中，因此在调整表大小操作期间会正确地跨表复制时钟逐出数据。
 * <p>
 * The cache uses a regular {@code ReentrantReadWriteLock} to provide read/write
 * exclusion/sharing properties.
 * 缓存使用常规的{@code ReentrantReadWriteLock}来提供读写共享属性。
 *
 * @param <K> the type of keys maintained by this map
 * @param <V> the type of mapped values
 *
 * @author Chris Dennis
 */
public class ReadWriteLockedOffHeapClockCache<K, V> extends AbstractOffHeapClockCache<K, V> {

  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

  public ReadWriteLockedOffHeapClockCache(PageSource source, StorageEngine<? super K, ? super V> storageEngine) {
    super(source, storageEngine);
  }

  public ReadWriteLockedOffHeapClockCache(PageSource source, boolean tableAllocationsSteal, StorageEngine<? super K, ? super V> storageEngine) {
    super(source, tableAllocationsSteal, storageEngine);
  }

  public ReadWriteLockedOffHeapClockCache(PageSource source, StorageEngine<? super K, ? super V> storageEngine, int tableSize) {
    super(source, storageEngine, tableSize);
  }

  public ReadWriteLockedOffHeapClockCache(PageSource source, boolean tableAllocationsSteal, StorageEngine<? super K, ? super V> storageEngine, int tableSize) {
    super(source, tableAllocationsSteal, storageEngine, tableSize);
  }

  @Override
  public Lock readLock() {
    return lock.readLock();
  }

  @Override
  public Lock writeLock() {
    return lock.writeLock();
  }

  @Override
  public ReentrantReadWriteLock getLock() {
    return lock;
  }
}
