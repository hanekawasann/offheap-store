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
package org.terracotta.offheapstore.buffersource;

import java.nio.ByteBuffer;

/**
 * A source of NIO buffers of some type.
 * 某种类型的NIO缓冲区的来源。
 *
 * @author Chris Dennis
 */
public interface BufferSource {

  /**
   * Allocates a buffer of the given size.
   * <p>
   * If a suitable buffer cannot be allocated then {@code null} should be
   * returned.  Implementations may place restrictions on the valid size value
   * they will accept.
   *
   * 分配给定大小的缓冲区
   * 如果无法分配合适的缓冲区，则应返回{@code null}。
   * 实现可能会限制它们将接受的有效大小值。
   *
   * @param size required buffer size
   * @return a buffer of the required size
   */
   ByteBuffer allocateBuffer(int size);
}
