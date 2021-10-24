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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.offheapstore.buffersource.BufferSource;
import org.terracotta.offheapstore.storage.allocator.PowerOfTwoAllocator;
import org.terracotta.offheapstore.util.DebuggingUtils;
import org.terracotta.offheapstore.util.MemoryUnit;
import org.terracotta.offheapstore.util.PhysicalMemory;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableSet;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.terracotta.offheapstore.storage.allocator.PowerOfTwoAllocator.Packing.CEILING;
import static org.terracotta.offheapstore.storage.allocator.PowerOfTwoAllocator.Packing.FLOOR;

/**
 * An upfront allocating direct byte buffer source.
 * 一种预先分配的直接字节缓冲源。
 * <p>
 * This buffer source implementation allocates all of its required storage
 * up-front in fixed size chunks.  Runtime allocations are then satisfied using
 * slices from these initial chunks.
 * 此缓冲区源实现将其所有所需的存储预先分配到固定大小的块中。
 * 然后使用来自这些初始块的切片来满足运行时分配。
 *
 * @author Chris Dennis
 */
public class UpfrontAllocatingPageSource implements PageSource {

    public static final String ALLOCATION_LOG_LOCATION = UpfrontAllocatingPageSource.class.getName() + ".allocationDump";

    private static final Logger LOGGER = LoggerFactory.getLogger(UpfrontAllocatingPageSource.class);
    private static final double PROGRESS_LOGGING_STEP_SIZE = 0.1;
    private static final long PROGRESS_LOGGING_THRESHOLD = MemoryUnit.GIGABYTES.toBytes(4L);

    private static final Comparator<Page> REGION_COMPARATOR = (a, b) -> {
      if (a.address() == b.address()) {
        return a.size() - b.size();
      } else {
        return a.address() - b.address();
      }
    };

    private final SortedMap<Long, Runnable> risingThresholds = new TreeMap<>();
    private final SortedMap<Long, Runnable> fallingThresholds = new TreeMap<>();

    // yukms TODO: 片分配器
    private final List<PowerOfTwoAllocator> sliceAllocators = new ArrayList<>();
    // yukms TODO: 受害者分配器
    private final List<PowerOfTwoAllocator> victimAllocators = new ArrayList<>();

    private final List<ByteBuffer> buffers = new ArrayList<>();

    /*
     * TODO : currently the TreeSet along with the comparator above works for the
     * subSet queries due to the alignment properties of the region allocation
     * being used here.  I more flexible implementation might involve switching
     * to using an AATreeSet subclass - that would also require me to finish
     * writing the subSet implementation for that class.
     * 目前，由于此处使用的区域分配的对齐属性，树集和上面的比较器可用于子集查询。
     * 我认为更灵活的实现可能需要切换到使用AATreeSet子类——这也需要我完成该类的子集实现的编写。
     */
    private final List<NavigableSet<Page>> victims = new ArrayList<>();

    private volatile int availableSet = ~0;

    /**
     * Create an up-front allocating buffer source of {@code toAllocate} total bytes, in
     * {@code chunkSize} byte chunks.
     *
     * @param source     source from which initial buffers will be allocated
     * @param toAllocate total space to allocate in bytes
     * @param chunkSize  chunkSize size to allocate in bytes
     */
    public UpfrontAllocatingPageSource(BufferSource source, long toAllocate, int chunkSize) {
        this(source, toAllocate, chunkSize, -1, true);
    }

    /**
     * Create an up-front allocating buffer source of {@code toAllocate} total bytes, in
     * maximally sized chunks, within the given bounds.
     *
     * @param source     source from which initial buffers will be allocated
     * @param toAllocate total space to allocate in bytes 要分配的总空间（字节）
     * @param maxChunk   the largest chunk size in bytes 以字节为单位的最大块大小
     * @param minChunk   the smallest chunk size in bytes 以字节为单位的最小块大小
     */
    public UpfrontAllocatingPageSource(BufferSource source, long toAllocate, int maxChunk, int minChunk) {
        this(source, toAllocate, maxChunk, minChunk, false);
    }

  /**
   * Create an up-front allocating buffer source of {@code toAllocate} total bytes, in
   * maximally sized chunks, within the given bounds.
   * <p>
   * By default we try to allocate chunks of {@code maxChunk} size. However, unless {@code fixed} is true, in case of
   * allocation failure, we will try to allocate half-smaller chunks. We do not allocate chunks smaller than {@code minChunk}
   * though.
   *
   * @param source     source from which initial buffers will be allocated
   * @param toAllocate total space to allocate in bytes
   * @param maxChunk   the largest chunk size in bytes
   * @param minChunk   the smallest chunk size in bytes
   * @param fixed      if the chunks should all be of size {@code maxChunk} or can be smaller 如果块的大小都应为{@code maxChunk}或更小
   */
    private UpfrontAllocatingPageSource(BufferSource source, long toAllocate, int maxChunk, int minChunk, boolean fixed) {
        Long totalPhysical = PhysicalMemory.totalPhysicalMemory();
        Long freePhysical = PhysicalMemory.freePhysicalMemory();
        if (totalPhysical != null && toAllocate > totalPhysical) {
          // yukms TODO: 分配大小大于总物理内存大小
          throw new IllegalArgumentException("Attempting to allocate " + DebuggingUtils.toBase2SuffixedString(toAllocate) + "B of memory "
                  + "when the host only contains " + DebuggingUtils.toBase2SuffixedString(totalPhysical) + "B of physical memory");
        }
        if (freePhysical != null && toAllocate > freePhysical) {
          // yukms TODO: 分配大小大于空闲物理内存大小
          LOGGER.warn("Attempting to allocate {}B of offheap when there is only {}B of free physical memory - some paging will therefore occur.",
                  DebuggingUtils.toBase2SuffixedString(toAllocate), DebuggingUtils.toBase2SuffixedString(freePhysical));
        }

        if(LOGGER.isInfoEnabled()) {
          LOGGER.info("Allocating {}B in chunks", DebuggingUtils.toBase2SuffixedString(toAllocate));
        }

        for (ByteBuffer buffer : allocateBackingBuffers(source, toAllocate, maxChunk, minChunk, fixed)) {
          sliceAllocators.add(new PowerOfTwoAllocator(buffer.capacity()));
          victimAllocators.add(new PowerOfTwoAllocator(buffer.capacity()));
          victims.add(new TreeSet<>(REGION_COMPARATOR));
          buffers.add(buffer);
        }
    }

  /**
   * Return the total allocated capacity, used or not
   *
   * @return the total capacity
   */
  public long getCapacity() {
    long capacity = 0;
    for(ByteBuffer buffer : buffers) {
      capacity += buffer.capacity();
    }
    return capacity;
  }

    /**
     * Allocates a byte buffer of at least the given size.
     * <p>
     * This {@code BufferSource} is limited to allocating regions that are a power
     * of two in size.  Supplied sizes are therefore rounded up to the next
     * largest power of two.
     *
     * @return a buffer of at least the given size
     */
    @Override
    public Page allocate(int size, boolean thief, boolean victim, OffHeapStorageArea owner) {
      if (thief) {
        return allocateAsThief(size, victim, owner);
      } else {
        return allocateFromFree(size, victim, owner);
      }
    }

    private Page allocateAsThief(final int size, boolean victim, OffHeapStorageArea owner) {
      Page free = allocateFromFree(size, victim, owner);

      if (free != null) {
        return free;
      }

      //do thieving here...
      PowerOfTwoAllocator victimAllocator = null;
      PowerOfTwoAllocator sliceAllocator = null;
      List<Page> targets = Collections.emptyList();
      Collection<AllocatedRegion> tempHolds = new ArrayList<>();
      Map<OffHeapStorageArea, Collection<Page>> releases = new IdentityHashMap<>();

      synchronized (this) {
        for (int i = 0; i < victimAllocators.size(); i++) {
          int address = victimAllocators.get(i).find(size, victim ? CEILING : FLOOR);
          if (address >= 0) {
            victimAllocator = victimAllocators.get(i);
            sliceAllocator = sliceAllocators.get(i);
            targets = findVictimPages(i, address, size);

            //need to claim everything that falls within the range of our allocation
            int claimAddress = address;
            for (Page p : targets) {
              victimAllocator.claim(p.address(), p.size());
              int claimSize = p.address() - claimAddress;
              if (claimSize > 0) {
                tempHolds.add(new AllocatedRegion(claimAddress, claimSize));
                sliceAllocator.claim(claimAddress, claimSize);
                victimAllocator.claim(claimAddress, claimSize);
              }
              claimAddress = p.address() + p.size();
            }
            int claimSize = (address + size) - claimAddress;
            if (claimSize > 0) {
              tempHolds.add(new AllocatedRegion(claimAddress, claimSize));
              sliceAllocator.claim(claimAddress, claimSize);
              victimAllocator.claim(claimAddress, claimSize);
            }
            break;
          }
        }

        for (Page p : targets) {
          OffHeapStorageArea a = p.binding();
          Collection<Page> c = releases.get(a);
          if (c == null) {
            c = new LinkedList<>();
            c.add(p);
            releases.put(a, c);
          } else {
            c.add(p);
          }
        }
      }

      /*
       * Drop the page source synchronization here to prevent deadlock against
       * map/cache threads.
       */
      Collection<Page> results = new LinkedList<>();
      for (Entry<OffHeapStorageArea, Collection<Page>> e : releases.entrySet()) {
        OffHeapStorageArea a = e.getKey();
        Collection<Page> p = e.getValue();
        results.addAll(a.release(p));
      }

      List<Page> failedReleases = new ArrayList<>();
      synchronized (this) {
        for (AllocatedRegion r : tempHolds) {
          sliceAllocator.free(r.address, r.size);
          victimAllocator.free(r.address, r.size);
        }

        if (results.size() == targets.size()) {
          for (Page p : targets) {
            victimAllocator.free(p.address(), p.size());
            free(p);
          }
          return allocateFromFree(size, victim, owner);
        } else {
          for (Page p : targets) {
            if (results.contains(p)) {
              victimAllocator.free(p.address(), p.size());
              free(p);
            } else {
              failedReleases.add(p);
            }
          }
        }
      }

      try {
        return allocateAsThief(size, victim, owner);
      } finally {
        synchronized (this) {
          for (Page p : failedReleases) {
            //this is just an ugly way of doing an identity equals based contains
            if (victims.get(p.index()).floor(p) == p) {
              victimAllocator.free(p.address(), p.size());
            }
          }
        }
      }
    }

    private List<Page> findVictimPages(int chunk, int address, int size) {
      return new ArrayList<>(victims.get(chunk).subSet(new Page(null, -1, address, null),
        new Page(null, -1, address + size, null)));
    }

    private Page allocateFromFree(int size, boolean victim, OffHeapStorageArea owner) {
        if (Integer.bitCount(size) != 1) {
            int rounded = Integer.highestOneBit(size) << 1;
            LOGGER.debug("Request to allocate {}B will allocate {}B", size, DebuggingUtils.toBase2SuffixedString(rounded));
            size = rounded;
        }

        if (isUnavailable(size)) {
            return null;
        }

        synchronized (this) {
            for (int i = 0; i < sliceAllocators.size(); i++) {
                int address = sliceAllocators.get(i).allocate(size, victim ? CEILING : FLOOR);
                if (address >= 0) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Allocating a {}B buffer from chunk {} &{}", DebuggingUtils.toBase2SuffixedString(size), i, address);
                    }
                    ByteBuffer b = ((ByteBuffer) buffers.get(i).limit(address + size).position(address)).slice();
                    Page p = new Page(b, i, address, owner);
                    if (victim) {
                      victims.get(i).add(p);
                    } else {
                      victimAllocators.get(i).claim(address, size);
                    }
                    if (!risingThresholds.isEmpty()) {
                      long allocated = getAllocatedSize();
                      fireThresholds(allocated - size, allocated);
                    }
                    return p;
                }
            }
            markUnavailable(size);
            return null;
        }
    }

    /**
     * Frees the supplied buffer.
     * <p>
     * If the given buffer was not allocated by this source or has already been
     * freed then an {@code AssertionError} is thrown.
     */
    @Override
    public synchronized void free(Page page) {
        if (page.isFreeable()) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Freeing a {}B buffer from chunk {} &{}", DebuggingUtils.toBase2SuffixedString(page.size()), page.index(), page.address());
            }
            markAllAvailable();
            sliceAllocators.get(page.index()).free(page.address(), page.size());
            victims.get(page.index()).remove(page);
            victimAllocators.get(page.index()).tryFree(page.address(), page.size());
            if (!fallingThresholds.isEmpty()) {
              long allocated = getAllocatedSize();
              fireThresholds(allocated + page.size(), allocated);
            }
        }
    }

    public synchronized long getAllocatedSize() {
        long sum = 0;
        for (PowerOfTwoAllocator a : sliceAllocators) {
            sum += a.occupied();
        }
        return sum;
    }

    public long getAllocatedSizeUnSync() {
        long sum = 0;
        for (PowerOfTwoAllocator a : sliceAllocators) {
            sum += a.occupied();
        }
        return sum;
    }

    private boolean isUnavailable(int size) {
        return (availableSet & size) == 0;
    }

    private synchronized void markAllAvailable() {
        availableSet = ~0;
    }

    private synchronized void markUnavailable(int size) {
        availableSet &= ~size;
    }

    @Override
    public synchronized String toString() {
        StringBuilder sb = new StringBuilder("UpfrontAllocatingPageSource");
        for (int i = 0; i < buffers.size(); i++) {
            sb.append("\nChunk ").append(i + 1).append('\n');
            sb.append("Size             : ").append(DebuggingUtils.toBase2SuffixedString(buffers.get(i).capacity())).append("B\n");
            sb.append("Free Allocator   : ").append(sliceAllocators.get(i)).append('\n');
            sb.append("Victim Allocator : ").append(victimAllocators.get(i));
        }
        return sb.toString();

    }

    private synchronized void fireThresholds(long incoming, long outgoing) {
      Collection<Runnable> thresholds;
      if (outgoing > incoming) {
        thresholds = risingThresholds.subMap(incoming, outgoing).values();
      } else if (outgoing < incoming) {
        thresholds = fallingThresholds.subMap(outgoing, incoming).values();
      } else {
        thresholds = Collections.emptyList();
      }

      for (Runnable r : thresholds) {
        try {
          r.run();
        } catch (Throwable t) {
          LOGGER.error("Throwable thrown by threshold action", t);
        }
      }
    }

    /**
     * Adds an allocation threshold action.
     * <p>
     * There can be only a single action associated with each unique direction
     * and threshold combination.  If an action is already associated with the
     * supplied combination then the action is replaced by the new action and the
     * old action is returned.
     * <p>
     * Actions are fired on passing through the supplied threshold and are called
     * synchronously with the triggering allocation.  This means care must be taken
     * to avoid mutating any map that uses this page source from within the action
     * otherwise deadlocks may result.  Exceptions thrown by the action will be
     * caught and logged by the page source and will not be propagated on the
     * allocating thread.
     *
     * @param direction new actions direction
     * @param threshold new actions threshold level
     * @param action fired on breaching the threshold
     * @return the replaced action or {@code null} if no action was present.
     */
    public synchronized Runnable addAllocationThreshold(ThresholdDirection direction, long threshold, Runnable action) {
      switch (direction) {
        case RISING:
          return risingThresholds.put(threshold, action);
        case FALLING:
          return fallingThresholds.put(threshold, action);
      }
      throw new AssertionError();
    }

    /**
     * Removes an allocation threshold action.
     * <p>
     * Removes the allocation threshold action for the given level and direction.
     *
     * @param direction registered actions direction
     * @param threshold registered actions threshold level
     * @return the removed condition or {@code null} if no action was present.
     */
    public synchronized Runnable removeAllocationThreshold(ThresholdDirection direction, long threshold) {
      switch (direction) {
        case RISING:
          return risingThresholds.remove(threshold);
        case FALLING:
          return fallingThresholds.remove(threshold);
      }
      throw new AssertionError();
    }

  /**
   * Allocate multiple buffers to fulfill the requested memory {@code toAllocate}. We first divide {@code toAllocate} in
   * chunks of size {@code maxChunk} and try to allocate them in parallel on all available processors. If one chunk fails to be
   * allocated, we try to allocate two chunks of {@code maxChunk / 2}. If this allocation fails, we continue dividing until
   * we reach of size of {@code minChunk}. If at that moment, the allocation still fails, an {@code IllegalArgumentException}
   * is thrown.
   * 分配多个缓冲区以满足请求的内存{@code to Allocate}。
   * 我们首先将{@code toAllocate}划分为大小为{@code maxChunk}的块，并尝试在所有可用处理器上并行分配它们。
   * 如果一个区块分配失败，我们将尝试分配两个{@code maxChunk/2}区块。
   * 如果此分配失败，我们将继续分割，直到达到{@code minChunk}的大小。
   * 如果此时分配仍然失败，则抛出{@code IllegalArgumentException}。
   * <p>
   * When {@code fixed} is requested, we will only allocated buffers of {@code maxChunk} size. If allocation fails, an
   * {@code IllegalArgumentException} is thrown without any division.
   * 当请求{@code fixed}时，我们将只分配{@code maxChunk}大小的缓冲区。
   * 如果分配失败，将抛出一个{@code IllegalArgumentException}，而不进行任何除法。
   * <p>
   * If the allocation is interrupted, the method will ignore it and continue allocation. It will then return with the
   * interrupt flag is set.
   * 如果分配被中断，该方法将忽略它并继续分配。
   * 然后在设置中断标志时返回。
   *
   * @param source source used to allocate memory buffers
   * @param toAllocate total amount of memory to allocate
   * @param maxChunk maximum size of a buffer. This is the targeted size for all buffers if everything goes well
   * @param minChunk minimum buffer size allowed
   * @param fixed if all buffers should have a the same size (except the last one with {@code toAllocate % maxChunk != 0}, if true, {@code minChunk} isn't used
   * @return the list of allocated buffers
   * @throws IllegalArgumentException when we fail to allocate the requested memory
   */
    private static Collection<ByteBuffer> allocateBackingBuffers(final BufferSource source, long toAllocate, int maxChunk, final int minChunk, final boolean fixed) {

      final long start = (LOGGER.isDebugEnabled() ? System.nanoTime() : 0);
      // yukms TODO: 创建分配日志
      final PrintStream allocatorLog = createAllocatorLog(toAllocate, maxChunk, minChunk);

      // guess the number of buffers and add some padding just in case
      // yukms TODO: 猜测缓冲区的数量并添加一些填充，以防万一
      final Collection<ByteBuffer> buffers = new ArrayList<>((int) (toAllocate / maxChunk + 10));

      try {
        if (allocatorLog != null) {
          allocatorLog.printf("timestamp,threadid,duration,size,physfree,totalswap,freeswap,committed%n");
        }

        List<Future<Collection<ByteBuffer>>> futures = new ArrayList<>((int) (toAllocate / maxChunk + 1));

        ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        try {

          for (long dispatched = 0; dispatched < toAllocate; ) {
            // yukms TODO: 按照maxChunk分配
            final int currentChunkSize = (int)Math.min(maxChunk, toAllocate - dispatched);
            futures.add(executorService.submit(() -> bufferAllocation(source, currentChunkSize, minChunk, fixed, allocatorLog, start)));
            dispatched += currentChunkSize;
          }
        } finally {
          executorService.shutdown();
        }

        long allocated = 0;
        long progressStep = Math.max(PROGRESS_LOGGING_THRESHOLD, (long)(toAllocate * PROGRESS_LOGGING_STEP_SIZE));
        long nextProgressLogAt = progressStep;

        for (Future<Collection<ByteBuffer>> future : futures) {
          Collection<ByteBuffer> result = uninterruptibleGet(future);
          buffers.addAll(result);
          for(ByteBuffer buffer : result) {
            allocated += buffer.capacity();
            if (allocated > nextProgressLogAt) {
              LOGGER.info("Allocation {}% complete", (100 * allocated) / toAllocate);
              nextProgressLogAt += progressStep;
            }
          }
        }

      } finally {
        if (allocatorLog != null) {
          allocatorLog.close();
        }
      }

      if(LOGGER.isDebugEnabled()) {
        long duration = System.nanoTime() - start;
        LOGGER.debug("Took {} ms to create off-heap storage of {}B.", TimeUnit.NANOSECONDS.toMillis(duration), DebuggingUtils.toBase2SuffixedString(toAllocate));
      }

      return Collections.unmodifiableCollection(buffers);
    }

  private static Collection<ByteBuffer> bufferAllocation(BufferSource source, int toAllocate, int minChunk, boolean fixed, PrintStream allocatorLog, long start) {
    // yukms TODO: 已分配大小
    long allocated = 0;
    // yukms TODO: 期望分配块大小
    long currentChunkSize = toAllocate;

    Collection<ByteBuffer> buffers = new ArrayList<>();

    while (allocated < toAllocate) {
      // yukms TODO: 如果还没到期望分配大小则继续分配
      long blockStart = System.nanoTime();
      int currentAllocation = (int)Math.min(currentChunkSize, (toAllocate - allocated));
      ByteBuffer b = source.allocateBuffer(currentAllocation);
      long blockDuration = System.nanoTime() - blockStart;

      if (b == null) {
        // yukms TODO: 分配失败
        if (fixed || (currentChunkSize >>> 1) < minChunk) {
          // yukms TODO: 固定 || 分配大小小于最小块大小
          throw new IllegalArgumentException("An attempt was made to allocate more off-heap memory than the JVM can allow." +
                                             " The limit on off-heap memory size is given by the -XX:MaxDirectMemorySize command (or equivalent).");
        }

        // In case of failure, we try half the allocation size. It might pass if memory fragmentation caused the failure
        // yukms TODO: 如果失败，我们尝试分配大小的一半。如果内存碎片导致了故障，则可能会通过
        currentChunkSize >>>= 1;

        if(LOGGER.isDebugEnabled()) {
          LOGGER.debug("Allocated failed at {}B, trying  {}B chunks.", DebuggingUtils.toBase2SuffixedString(currentAllocation), DebuggingUtils.toBase2SuffixedString(currentChunkSize));
        }
      } else {
        // yukms TODO: 分配成功
        buffers.add(b);
        // yukms TODO: 已分配大小
        allocated += currentAllocation;

        if (allocatorLog != null) {
          // yukms TODO: 打印日志
          allocatorLog.printf("%d,%d,%d,%d,%d,%d,%d,%d%n", System.nanoTime() - start,
            Thread.currentThread().getId(),  blockDuration, currentAllocation, PhysicalMemory.freePhysicalMemory(), PhysicalMemory.totalSwapSpace(), PhysicalMemory.freeSwapSpace(), PhysicalMemory.ourCommittedVirtualMemory());
        }

        if(LOGGER.isDebugEnabled()) {
          LOGGER.debug("{}B chunk allocated", DebuggingUtils.toBase2SuffixedString(currentAllocation));
        }
      }
    }

    return buffers;
  }

  private static <T> T uninterruptibleGet(Future<T> future) {
    boolean interrupted = Thread.interrupted();
    try {
      while (true) {
        try {
          return future.get();
        } catch (ExecutionException e) {
          if (e.getCause() instanceof RuntimeException) {
            throw (RuntimeException)e.getCause();
          }
          throw new RuntimeException(e);
        } catch (InterruptedException e) {
          // Remember and keep going
          interrupted = true;
        }
      }
    } finally {
      if(interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static PrintStream createAllocatorLog(long max, int maxChunk, int minChunk) {
      String path = System.getProperty(ALLOCATION_LOG_LOCATION);
      if (path == null) {
        // yukms TODO: 未配置分类日志路径
        return null;
      } else {
        PrintStream allocatorLogStream;
        try {
          // yukms TODO: 创建临时文件
          File allocatorLogFile = File.createTempFile("allocation", ".csv", new File(path));
          // yukms TODO: 包装为打印流
          allocatorLogStream = new PrintStream(allocatorLogFile, "US-ASCII");
        } catch (IOException e) {
          LOGGER.warn("Exception creating allocation log", e);
          return null;
        }
        // yukms TODO: 基本日志
        allocatorLogStream.printf("Timestamp: %s%n", new Date());
        allocatorLogStream.printf("Allocating: %sB%n",DebuggingUtils.toBase2SuffixedString(max));
        allocatorLogStream.printf("Max Chunk: %sB%n",DebuggingUtils.toBase2SuffixedString(maxChunk));
        allocatorLogStream.printf("Min Chunk: %sB%n",DebuggingUtils.toBase2SuffixedString(minChunk));
        return allocatorLogStream;
      }
    }

    public enum ThresholdDirection {
      RISING, FALLING
    }

    static class AllocatedRegion {

        private final int address;
        private final int size;

        AllocatedRegion(int address, int size) {
            this.address = address;
            this.size = size;
        }
    }
}
