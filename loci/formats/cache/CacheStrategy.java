//
// CacheStrategy.java
//

/*
OME Bio-Formats package for reading and converting biological file formats.
Copyright (C) 2005-@year@ UW-Madison LOCI and Glencoe Software, Inc.

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package loci.formats.cache;

import java.util.*;
import loci.formats.FormatTools;

/**
 * Superclass of cache strategies.
 *
 * <dl><dt><b>Source code:</b></dt>
 * <dd><a href="https://skyking.microscopy.wisc.edu/trac/java/browser/trunk/loci/formats/cache/CacheStrategy.java">Trac</a>,
 * <a href="https://skyking.microscopy.wisc.edu/svn/java/trunk/loci/formats/cache/CacheStrategy.java">SVN</a></dd></dl>
 */
public abstract class CacheStrategy
  implements CacheReporter, Comparator, ICacheStrategy
{

  // -- Constants --

  /** Default cache range. */
  public static final int DEFAULT_RANGE = 0;

  // -- Fields --

  /** Length of each dimensional axis. */
  protected int[] lengths;

  /** The order in which planes should be loaded along each axis. */
  protected int[] order;

  /** Number of planes to cache along each axis. */
  protected int[] range;

  /** Priority for caching each axis. Controls axis caching order. */
  protected int[] priorities;

  /**
   * The list of dimensional positions to consider caching, in order of
   * preference based on strategy, axis priority and planar ordering.
   */
  private int[][] positions;

  /**
   * Whether load order array needs to be recomputed
   * before building the next load list.
   */
  private boolean dirty;

  /** List of cache event listeners. */
  protected Vector listeners;

  // -- Constructors --

  /** Constructs a cache strategy. */
  public CacheStrategy(int[] lengths) {
    this.lengths = lengths;
    order = new int[lengths.length];
    Arrays.fill(order, CENTERED_ORDER);
    range = new int[lengths.length];
    Arrays.fill(range, DEFAULT_RANGE);
    priorities = new int[lengths.length];
    Arrays.fill(priorities, NORMAL_PRIORITY);
    positions = getPossiblePositions();
    dirty = true;
    listeners = new Vector();
  }

  // -- Abstract CacheStrategy API methods --

  /**
   * Gets positions to consider for possible inclusion in the cache,
   * assuming a current position at the origin (0).
   */
  protected abstract int[][] getPossiblePositions();

  // -- CacheStrategy API methods --

  /**
   * Computes the distance from the given axis value to the
   * axis center, taking into account the axis ordering scheme.
   */
  public int distance(int axis, int value) {
    switch (order[axis]) {
      case CENTERED_ORDER:
        if (value == 0) return 0;
        int vb = lengths[axis] - value;
        return value <= vb ? value : vb;
      case FORWARD_ORDER:
        return value;
      case BACKWARD_ORDER:
        if (value == 0) return 0;
        return lengths[axis] - value;
      default:
        throw new IllegalStateException("unknown order: " + order[axis]);
    }
  }

  // -- Internal CacheStrategy API methods --

  /** Shortcut for converting N-D position to rasterized position. */
  protected int raster(int[] pos) {
    return FormatTools.positionToRaster(lengths, pos);
  }

  /** Shortcut for converting rasterized position to N-D position. */
  protected int[] pos(int raster) {
    return FormatTools.rasterToPosition(lengths, raster);
  }

  /**
   * Shortcut for converting rasterized position to N-D position,
   * using the given array instead of allocating a new one.
   */
  protected int[] pos(int raster, int[] pos) {
    return FormatTools.rasterToPosition(lengths, raster, pos);
  }

  /** Shortcut for computing total number of positions. */
  protected int length() { return FormatTools.getRasterLength(lengths); }

  // -- CacheReporter API methods --

  /* @see CacheReporter#addCacheListener(CacheListener) */
  public void addCacheListener(CacheListener l) {
    synchronized (listeners) { listeners.add(l); }
  }

  /* @see CacheReporter#removeCacheListener(CacheListener) */
  public void removeCacheListener(CacheListener l) {
    synchronized (listeners) { listeners.remove(l); }
  }

  /* @see CacheReporter#getCacheListeners() */
  public CacheListener[] getCacheListeners() {
    CacheListener[] l;
    synchronized (listeners) {
      l = new CacheListener[listeners.size()];
      listeners.copyInto(l);
    }
    return l;
  }

  // -- Comparator API methods --

  /**
   * Default comparator orders dimensional positions based on distance from the
   * current position, taking into account axis priorities and planar ordering.
   */
  public int compare(Object o1, Object o2) {
    int[] p1 = (int[]) o1;
    int[] p2 = (int[]) o2;

    // compare sum of axis distances for each priority
    for (int p=MAX_PRIORITY; p>=MIN_PRIORITY; p--) {
      int dist1 = 0, dist2 = 0;
      for (int i=0; i<p1.length; i++) {
        if (priorities[i] == p) {
          dist1 += distance(i, p1[i]);
          dist2 += distance(i, p2[i]);
        }
      }
      int diff = dist1 - dist2;
      if (diff != 0) return diff;
    }

    // compare number of diverging axes for each priority
    for (int p=MAX_PRIORITY; p>=MIN_PRIORITY; p--) {
      int div1 = 0, div2 = 0;
      for (int i=0; i<p1.length; i++) {
        if (priorities[i] == p) {
          if (p1[i] != 0) div1++;
          if (p2[i] != 0) div2++;
        }
      }
      int diff = div1 - div2;
      if (diff != 0) return diff;
    }

    return 0;
  }

  // -- ICacheStrategy API methods --

  /* @see ICacheStrategy#getLoadList(int[]) */
  public int[][] getLoadList(int[] pos) throws CacheException {
    int[][] loadList = null;
    synchronized (positions) {
      if (dirty) {
        // reorder the positions list
        Arrays.sort(positions, this);
        dirty = false;
      }

      // count up number of load list entries
      int c = 0;
      for (int i=0; i<positions.length; i++) {
        int[] ipos = positions[i];

        // verify position is close enough to current position
        boolean ok = true;
        for (int j=0; j<ipos.length; j++) {
          if (distance(j, ipos[j]) > range[j]) {
            ok = false;
            break;
          }
        }
        if (ok) c++; // in range
      }

      loadList = new int[c][lengths.length];
      c = 0;

      // build load list
      for (int i=0; i<positions.length; i++) {
        int[] ipos = positions[i];

        // verify position is close enough to current position
        boolean ok = true;
        for (int j=0; j<ipos.length && c<loadList.length; j++) {
          if (distance(j, ipos[j]) > range[j]) {
            ok = false;
            break;
          }
          int value = (pos[j] + ipos[j]) % lengths[j]; // normalize axis value
          loadList[c][j] = value;
        }
        if (ok) c++; // in range; lock in load list entry
      }
    }
    return loadList;
  }

  /* @see ICacheStrategy#getPriorities() */
  public int[] getPriorities() { return priorities; }

  /* @see ICacheStrategy#setPriority() */
  public void setPriority(int priority, int axis) {
    if (priority < MIN_PRIORITY || priority > MAX_PRIORITY) {
      throw new IllegalArgumentException(
        "Invalid priority for axis #" + axis + ": " + priority);
    }
    synchronized (positions) {
      priorities[axis] = priority;
      dirty = true;
    }
    notifyListeners(new CacheEvent(this, CacheEvent.PRIORITIES_CHANGED));
  }

  /* @see ICacheStrategy#getOrder() */
  public int[] getOrder() { return order; }

  /* @see ICacheStrategy#getOrder() */
  public void setOrder(int order, int axis) {
    if (order != CENTERED_ORDER &&
      order != FORWARD_ORDER && order != BACKWARD_ORDER)
    {
      throw new IllegalArgumentException(
        "Invalid order for axis #" + axis + ": " + order);
    }
    synchronized (positions) {
      this.order[axis] = order;
      dirty = true;
    }
    notifyListeners(new CacheEvent(this, CacheEvent.ORDER_CHANGED));
  }

  /* @see ICacheStrategy#getRange() */
  public int[] getRange() { return range; }

  /* @see ICacheStrategy#setRange(int, int) */
  public void setRange(int num, int axis) {
    if (num < 0) {
      throw new IllegalArgumentException(
        "Invalid range for axis #" + axis + ": " + num);
    }
    range[axis] = num;
    notifyListeners(new CacheEvent(this, CacheEvent.RANGE_CHANGED));
  }

  /* @see ICacheStrategy#getLengths() */
  public int[] getLengths() { return lengths; }

  // -- Helper methods --

  /** Informs listeners of a cache update. */
  protected void notifyListeners(CacheEvent e) {
    synchronized (listeners) {
      for (int i=0; i<listeners.size(); i++) {
        CacheListener l = (CacheListener) listeners.elementAt(i);
        l.cacheUpdated(e);
      }
    }
  }

}
