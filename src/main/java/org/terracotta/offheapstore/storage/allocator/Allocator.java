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
package org.terracotta.offheapstore.storage.allocator;

public interface Allocator extends Iterable<Long> {

  // yukms TODO: 分配
  long allocate(long size);
  // yukms TODO: 自由的
  void free(long address);
  void clear();
  // yukms TODO: 扩大
  void expand(long size);
  // yukms TODO: 占用
  long occupied();
  // yukms TODO: 验证分配器
  void validateAllocator();
  // yukms TODO: 获取最后使用的地址
  long getLastUsedAddress();
  // yukms TODO: 获取最后使用的指针
  long getLastUsedPointer();
  // yukms TODO: 获得最小尺寸
  int getMinimalSize();
  // yukms TODO: 获取最大地址
  long getMaximumAddress();

}
