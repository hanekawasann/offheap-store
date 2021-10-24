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
package org.terracotta.offheapstore.paging;

/**
 *
 * @author cdennis
 */
public interface PageSource {

  /**
   * Attempt to allocate a page of the given size.
   * 尝试分配给定大小的页面。
   * <p>
   * Allocations identified as thieves will if necessary 'steal' space from
   * previous allocations identified as 'victims' in order to fulfill the
   * allocation request.  <code>owner</code> is the area from which the
   * returned page can subsequently be stolen or recovered.  This is most likely
   * to be the calling instance.
   * 如有必要，标识为盗贼的分配将从以前标识为“受害者”的分配中“窃取”空间，以满足分配请求<code>owner<code>是随后可以从中窃取或恢复返回页面的区域。
   * 这很可能是调用实例。
   *
   * @param size size of page to allocate
   * @param thief {@code true} if the allocation can steal space from victims
   * @param victim {@code true} if the allocated page should be eligible for stealing
   * @param owner owner from which subsequent steal should occur
   * @return an allocated page, or {@code null} in the case of failure
   */
  Page allocate(int size, boolean thief, boolean victim, OffHeapStorageArea owner);

  void free(Page page);
}
