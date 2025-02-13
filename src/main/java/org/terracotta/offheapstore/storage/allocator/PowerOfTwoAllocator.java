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

import org.terracotta.offheapstore.paging.UpfrontAllocatingPageSource;
import org.terracotta.offheapstore.util.AATreeSet;
import org.terracotta.offheapstore.util.DebuggingUtils;
import static org.terracotta.offheapstore.util.Validation.shouldValidate;
import static org.terracotta.offheapstore.util.Validation.validate;

/**
 * An augmented AA tree allocator with unusual alignment/allocation properties.
 * 具有特殊的对齐/分配属性的扩充AA树分配器。
 * <p>
 * This allocator allocates only power-of-two size chunks.  In addition these
 * chunks are then only allocated on alignment with their own size.  Hence a
 * chunk of 2<sup>n</sup> size can only be allocated to an address satisfying
 * a=2<sup>n</sup>x where x is an integer.
 * 此分配器仅分配两个大小块的幂。
 * 此外，这些块仅在与它们自己的大小对齐时分配。
 * 因此，大小为2<sup>n<sup>的块只能分配给满足a=2<sup>n<sup>x的地址，其中x是整数。
 *
 * @author Chris Dennis
 */
public class PowerOfTwoAllocator extends AATreeSet<Region> {

  private static final boolean DEBUG = Boolean.getBoolean(PowerOfTwoAllocator.class.getName() + ".DEBUG");
  private static final boolean VALIDATING = shouldValidate(PowerOfTwoAllocator.class);

  private final int size;
  /**
   * This is volatile because we read it without any locking through
   * 这是不稳定的，因为我们读取它时没有任何锁定
   * {@link UpfrontAllocatingPageSource#getAllocatedSizeUnSync()}
   */
  private volatile int occupied;

  /**
   * Create a power-of-two allocator with the given initial 'free' size area.
   * 创建具有给定初始“空闲”大小区域的二次幂分配器。
   *
   * @param size initial free size
   */
  public PowerOfTwoAllocator(int size) {
    super();
    add(new Region(0, size - 1));
    this.size = size;
  }

  public int allocate(int size, Packing packing) {
    if (Integer.bitCount(size) != 1) {
      // yukms TODO: 不是2的幂次方
      throw new AssertionError("Size " + size + " is not a power of two");
    }

    // yukms TODO: 寻找指定大小的区域
    final Region r = findRegion(size, packing);
    if (r == null) {
      // yukms TODO: 没找到
      return -1;
    }
    // yukms TODO: 根据start寻找分配区域
    Region current = removeAndReturn(r.start());
    // yukms TODO: 分配并生成新区域
    Region newRange = current.remove(r);
    if (newRange != null) {
      // yukms TODO: 生成了新区域
      insert(current);
      insert(newRange);
    } else if (!current.isNull()) {
      // yukms TODO: 未生成新区域 && 分配的区域还有空间
      insert(current);
    }
    // yukms TODO: 更新已分配的大小
    occupied += r.size();
    // yukms TODO: 校验剩余空间
    validateFreeSpace();

    // yukms TODO: 返回开始地址
    return r.start();
  }

  public void free(int address, int length) {
    if (length != 0) {
      free(new Region(address, address + length - 1));
      occupied -= length;
      validateFreeSpace();
    }
  }

  public void tryFree(int address, int length) {
    if (length == 0) {
      return;
    } else if (tryFree(new Region(address, address + length - 1))) {
      occupied -= length;
      validateFreeSpace();
    }
  }

  public int find(int size, Packing packing) {
    if (Integer.bitCount(size) != 1) {
      throw new AssertionError("Size " + size + " is not a power of two");
    }

    final Region r = findRegion(size, packing);
    if (r == null) {
      return -1;
    } else {
      return r.start();
    }
  }

  // yukms TODO: 和allocate是什么关系？？？
  public void claim(int address, int size) {
    // yukms TODO: 被分配的区域
    Region current = removeAndReturn(address);
    // yukms TODO: 需要分配的区域
    Region r = new Region(address, address + size - 1);
    // yukms TODO: 分配并生成新区域
    Region newRange = current.remove(r);
    if (newRange != null) {
      // yukms TODO: 生成了新区域
      insert(current);
      insert(newRange);
    } else if (!current.isNull()) {
      // yukms TODO: 未生成新区域 && 分配的区域还有空间
      insert(current);
    }
    // yukms TODO: 更新已分配的大小
    occupied += size;
    // yukms TODO: 校验剩余空间
    validateFreeSpace();
  }

  public int occupied() {
    return occupied;
  }

  @Override
  public Region removeAndReturn(Object o) {
      Region r = super.removeAndReturn(o);
      if (r != null) {
        // yukms TODO: 移除成功
        return new Region(r);
      } else {
        return null;
      }
  }

  @Override
  public Region find(Object o) {
      Region r = super.find(o);
      if (r != null) {
          return new Region(r);
      } else {
          return null;
      }
  }

  private void free(Region r) {
    // Step 1 : Check if the previous number is present, if so add to the same Range.
    Region prev = removeAndReturn(r.start() - 1);
    if (prev != null) {
      prev.merge(r);
      Region next = removeAndReturn(r.end() + 1);
      if (next != null) {
        prev.merge(next);
      }
      insert(prev);
      return;
    }

    // Step 2 : Check if the next number is present, if so add to the same Range.
    Region next = removeAndReturn(r.end() + 1);
    if (next != null) {
      next.merge(r);
      insert(next);
      return;
    }

    // Step 3: Add a new range for just this number.
    insert(r);
  }

  private boolean tryFree(Region r) {
    // Step 1 : Check if the previous number is present, if so add to the same Range.
    Region prev = removeAndReturn(r.start() - 1);
    if (prev != null) {
      if (prev.tryMerge(r)) {
        Region next = removeAndReturn(r.end() + 1);
        if (next != null) {
          prev.merge(next);
        }
        insert(prev);
        return true;
      } else {
        insert(prev);
        return false;
      }
    }

    // Step 2 : Check if the next number is present, if so add to the same Range.
    Region next = removeAndReturn(r.end() + 1);
    if (next != null) {
      if (next.tryMerge(r)) {
        insert(next);
        return true;
      } else {
        insert(next);
        return false;
      }
    }

    // Step 3: Add a new range for just this number.
    return tryInsert(r);
  }
  /**
   * Insert into the tree.
   *
   * @param x the item to insert.
   */
  private void insert(Region x) {
    if (!tryInsert(x)) {
      throw new AssertionError(x + " is already inserted");
    }
  }

  private boolean tryInsert(Region x) {
    return add(x);
  }

  /**
   * Find a region of the given size.
   * 找到给定大小的区域。
   */
  private Region findRegion(int size, Packing packing) {
    validate(!VALIDATING || Integer.bitCount(size) == 1);

    Node<Region> currentNode = getRoot();
    Region currentRegion = currentNode.getPayload();
    if (currentRegion == null || (currentRegion.available() & size) == 0) {
      //no region big enough for us...
      // yukms TODO: 没有足够大的区域容纳我们。。。
      return null;
    } else {
      // yukms TODO: 有空间找
      while (true) {
        // yukms TODO: prefered
        Node<Region> prefered = packing.prefered(currentNode);
        Region preferedRegion = prefered.getPayload();
        if (preferedRegion != null && (preferedRegion.available() & size) != 0) {
          // yukms TODO: 有空间 && 可用
          // yukms TODO: 继续往下找
          currentNode = prefered;
          currentRegion = preferedRegion;
        } else if ((currentRegion.availableHere() & size) != 0) {
          // yukms TODO: 没有空间了 && 当前空间是否可用
          // yukms TODO:
          return packing.slice(currentRegion, size);
        } else {
          // yukms TODO: fallback
          Node<Region> fallback = packing.fallback(currentNode);
          Region fallbackRegion = fallback.getPayload();
          if (fallbackRegion != null && (fallbackRegion.available() & size) != 0) {
            // yukms TODO: 有空间 && 可用，继续往下找
            currentNode = fallback;
            currentRegion = fallbackRegion;
          } else {
            throw new AssertionError();
          }
        }
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String toString() {
    Region rootRegion = getRoot().getPayload();
    StringBuilder sb = new StringBuilder("PowerOfTwoAllocator: Occupied ");
    sb.append(DebuggingUtils.toBase2SuffixedString(occupied())).append("B");
    sb.append(" [Largest Available Area ").append(DebuggingUtils.toBase2SuffixedString(Integer.highestOneBit(rootRegion == null ? 0 : rootRegion.available()))).append("B]");
    if (DEBUG) {
      sb.append("\nFree Regions = ").append(super.toString()).append("");
    }
    return sb.toString();
  }

  private void validateFreeSpace() {
    if (VALIDATING) {
      Region rootRegion = getRoot().getPayload();
      if (occupied() != (size - (rootRegion == null ? 0 : rootRegion.treeSize()))) {
        throw new AssertionError("Occupied:" + occupied() + " Size-TreeSize:" + (size - (rootRegion == null ? 0 : rootRegion.treeSize())));
      }
    }
  }

  public enum Packing {
    // yukms TODO: 地板
    FLOOR {

      @Override
      Node<Region> prefered(Node<Region> node) {
        return node.getLeft();
      }

      @Override
      Node<Region> fallback(Node<Region> node) {
        return node.getRight();
      }

      @Override
      Region slice(Region region, int size) {
        int mask = size - 1;
        int a = (region.start() + mask) & ~mask;
        return new Region(a, a + size - 1);
      }
    },

    // yukms TODO: 天花板
    CEILING {

      @Override
      Node<Region> prefered(Node<Region> node) {
        return node.getRight();
      }

      @Override
      Node<Region> fallback(Node<Region> node) {
        return node.getLeft();
      }

      @Override
      Region slice(Region region, int size) {
        int mask = size - 1;
        int a = (region.end() + 1) & ~mask;
        return new Region(a - size, a - 1);
      }
    };

    abstract Node<Region> prefered(Node<Region> node);

    abstract Node<Region> fallback(Node<Region> node);

    abstract Region slice(Region region, int size);
  }
}
