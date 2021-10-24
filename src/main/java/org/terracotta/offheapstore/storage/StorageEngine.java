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
package org.terracotta.offheapstore.storage;

import java.nio.ByteBuffer;
import java.util.concurrent.locks.ReadWriteLock;

/**
 * An object that encodes map/cache keys and values to integers.
 * 将map/cache键和值编码为整数的对象
 * <p>
 * {@code StorageEngine} instances can choose their own method of value/key
 * encoding.  Keys that are small enough to be fully encoded in the
 * {@code Integer} return can be stored directly in the table, others could be
 * stored in some additional data structure.
 * {@code StorageEngine}实例可以选择自己的value/key编码方法。
 * 小到可以在{@code Integer}返回中完全编码的键可以直接存储在表中，其他键可以存储在其他数据结构中。
 *
 * @param <K> key type handled by this engine
 * @param <V> value type handled by this engine
 *
 * @author Chris Dennis
 */
public interface StorageEngine<K, V> {

  /**
   * Converts the supplied key and value objects into their encoded form.
   * 将提供的键和值对象转换为其编码形式。
   *
   * @param key a key object
   * @param value a value object
   * @param hash the key hash
   * @param metadata the metadata bits
   * @return the encoded mapping
   */
  Long writeMapping(K key, V value, int hash, int metadata);

  void attachedMapping(long encoding, int hash, int metadata);

  /**
   * Called to indicate that the associated encoded value is no longer needed.
   * 调用以指示不再需要关联的编码值。
   * <p>
   * This call can be used to free any associated resources tied to the
   * lifecycle of the supplied encoded value.
   * 此调用可用于释放与所提供编码值的生命周期相关联的任何资源。
   *
   * @param encoding encoded value
   * @param hash hash of the freed mapping
   * @param removal marks removal from a map
   */
  void freeMapping(long encoding, int hash, boolean removal);

  /**
   * Converts the supplied encoded value into its correct object form.
   * 将提供的编码值转换为其正确的对象形式。
   *
   * @param encoding encoded value
   * @return a decoded value object
   */
  V readValue(long encoding);

  /**
   * Called to determine the equality of the given Java object value against the
   * given encoded form.
   * 调用以确定给定Java对象值与给定编码形式的相等性。
   * <p>
   * Simple implementations will probably perform a decode on the given encoded
   * form in order to do a regular {@code Object.equals(Object)} comparison.
   * This method is provided to allow implementations to optimize this
   * comparison if possible.
   * 简单的实现可能会对给定的编码形式执行解码，以便进行规则的{@code Object.equals（Object）}比较。
   * 提供此方法是为了允许实现在可能的情况下优化此比较。
   *
   * @param value a value object
   * @param encoding encoded value
   * @return {@code true} if the value and the encoding are equal
   */
  boolean equalsValue(Object value, long encoding);

  /**
   * Converts the supplied encoded key into its correct object form.
   * 将提供的编码密钥转换为其正确的对象形式。
   *
   * @param encoding encoded key
   * @param hashCode hash-code of the decoded key
   * @return a decoded key object
   */
  K readKey(long encoding, int hashCode);

  /**
   * Called to determine the equality of the given object against the
   * given encoded form.
   * 调用以确定给定对象与给定编码形式的相等性。
   * <p>
   * Simple implementations will probably perform a decode on the given encoded
   * form in order to do a regular {@code Object.equals(Object)} comparison.
   * This method is provided to allow implementations to optimize this
   * comparison if possible.
   * 简单的实现可能会对给定的编码形式执行解码，以便进行规则的{@code Object.equals（Object）}比较。
   * 提供此方法是为了允许实现在可能的情况下优化此比较。
   *
   * @param key a key object
   * @param encoding encoded value
   * @return {@code true} if the key and the encoding are equal
   */
  boolean equalsKey(Object key, long encoding);

  /**
   * Called to indicate that all keys and values are now free.
   * 调用以指示所有键和值现在都是自由的。
   */
  void clear();

  /**
   * Returns a measure of the amount of memory allocated for this storage engine.
   * 返回为此存储引擎分配的内存量的度量值。
   *
   * @return memory allocated for this engine in bytes
   */
  long getAllocatedMemory();

  /**
   * Returns a measure of the amount of memory consumed by this storage engine.
   * 返回此存储引擎消耗的内存量的度量值。
   *
   * @return memory occupied by this engine in bytes
   */
  long getOccupiedMemory();

  /**
   * Returns a measure of the amount of vital memory allocated for this storage engine.
   * 返回为此存储引擎分配的重要内存量的度量值。
   *
   * @return vital memory allocated for this engine in bytes
   */
  long getVitalMemory();

  /**
   * Returns a measure of the total size of the keys and values stored in this storage engine.
   * 返回存储在此存储引擎中的键和值的总大小的度量值。
   *
   * @return size of the stored keys and values in bytes
   */
  long getDataSize();

  /**
   * Invalidate any local key/value caches.
   * 使任何本地键值缓存无效。
   * <p>
   * This is called to indicate the termination of a map write "phase".  Caching
   * is permitted within a write operation (i.e. to cache around allocation
   * failures during eviction processes).
   * 调用此命令是为了指示映射写入“阶段”的终止。
   * 允许在写操作中进行缓存（即在逐出过程中缓存分配失败）。
   */
  void invalidateCache();

  void bind(Owner owner);

  void destroy();

  boolean shrink();

  interface Owner extends ReadWriteLock {

    Long getEncodingForHashAndBinary(int hash, ByteBuffer offHeapBinaryKey);

    long getSize();

    long installMappingForHashAndEncoding(int pojoHash, ByteBuffer offheapBinaryKey, ByteBuffer offheapBinaryValue, int metadata);

    Iterable<Long> encodingSet();

    boolean updateEncoding(int hashCode, long lastAddress, long compressed, long mask);

    Integer getSlotForHashAndEncoding(int hash, long address, long mask);

    boolean evict(int slot, boolean b);

    boolean isThiefForTableAllocations();

  }
}
