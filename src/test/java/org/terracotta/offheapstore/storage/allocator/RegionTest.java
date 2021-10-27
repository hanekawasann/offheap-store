package org.terracotta.offheapstore.storage.allocator;

import org.junit.Assert;
import org.junit.Test;

/**
 * @author yukms 2021/10/27 23:55
 */
public class RegionTest {
  @Test
  public void test_01() {
    // yukms TODO: 21位
    // yukms TODO: 1 1111 1111 1111 1111 1111
    Assert.assertEquals("111111111111111111111", Integer.toBinaryString(availableHere(0, 1024 * 1024)));
    //System.out.println(Integer.toBinaryString(availableHere(0, 1024 * 1024 * 1024)));
  }

  @Test
  public void test_02() {

  }

  int availableHere(int start, int end) {
    int bits = 0;

    for (int i = 0; i < Integer.SIZE - 1; i++) {
      int size = 1 << i;
      System.out.print(Integer.toBinaryString(size));
      System.out.print("\t");
      int mask = size - 1;
      System.out.print(Integer.toBinaryString(mask));
      System.out.print("\t");

      int a = (start + mask) & ~mask;
      System.out.print(Integer.toBinaryString(a));
      System.out.print("\t");

      if ((end - a + 1) >= size) {
        bits |= size;
      }
      System.out.print(Integer.toBinaryString(bits));
      System.out.println();
    }
    return bits;
  }
}
