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
package org.terracotta.offheapstore.storage.portability;

import java.nio.ByteBuffer;

/**
 * An object to ByteBuffer converter.
 * ByteBuffer转换器的对象。
 *
 * @param <T> type handled by this converter
 *
 * @author Chris Dennis
 */
public interface Portability<T> {

  /**
   * Encodes an object of type {@code T} as a {@code ByteBuffer}.
   * 将{@code T}类型的对象编码为{@code ByteBuffer}。
   *
   * @param object object to be encoded
   * @return the encoded object
   */
  ByteBuffer encode(T object);

  /**
   * Decodes a {@code ByteBuffer} to an object of type {@code T}.
   * 将{@code ByteBuffer}解码为{@code T}类型的对象。
   *
   * @param buffer bytes to decode
   * @return the decoded object
   */
  T decode(ByteBuffer buffer);

  /**
   * Returns true if the encoded object once decoded would be
   * {@code Object.equals(Object)} to the supplied object.
   * 如果解码后的编码对象与提供的对象为{@code object.equals（object）}，则返回true。
   *
   * @param object object to compare to
   * @param buffer buffer containing encoded object
   * @return {@code true} if the two parameters are "equal"
   */
  boolean equals(Object object, ByteBuffer buffer);
}
