package com.bitdroid.swap

import java.io.IOException

/**
 * Works out the new partition table for the OTHER slot, the way update_engine does it:
 * start from the running slot's table, drop every partition with the other slot's suffix,
 * then add the ROM's dynamic partitions (system, vendor, product…) in free space only.
 * Free space = space no running-slot partition uses, per the running slot's table (what's mapped right now).
 * The running slot's partitions are never moved, resized or removed.
 */
object Planner {

    class Plan(val metadata: Lp.Metadata, val blob: ByteArray, val newParts: List<Lp.Partition>, val freeBefore: Long, val needed: Long)

    /**
     * @param current  metadata of the running slot
     * @param target   the other slot's suffix, "_a" or "_b"
     * @param sizes    dynamic partition name (no suffix) → size in bytes, from the ROM
     * @param groups   ROM group name (no suffix) → (max size, partition names)
     */
    fun plan(
        geo: Lp.Geometry,
        current: Lp.Metadata,
        target: String,
        sizes: Map<String, Long>,
        groups: List<Payload.Group>,
    ): Plan {
        if (current.blockDevices.size != 1) throw IOException("super is split over ${current.blockDevices.size} disks. bitSwap only handles one.")
        val dev = current.blockDevices[0]
        val lbs = geo.logicalBlockSize.toLong()
        val firstByte = dev.firstLogicalSector * Lp.SECTOR
        val endByte = dev.size / lbs * lbs
        if (firstByte <= 0 || firstByte >= endByte) throw IOException("odd super layout")

        // space used by anything that isn't the other slot's
        val used = Lp.usedRanges(current.partitions.filter { !it.name.endsWith(target) }).sortedBy { it.first }

        // free regions inside [firstByte, endByte)
        val free = mutableListOf<LongRange>()
        var cur = firstByte
        for (r in used) {
            if (r.first > cur) free += cur until minOf(r.first, endByte)
            cur = maxOf(cur, r.last + 1)
            if (cur >= endByte) break
        }
        if (cur < endByte) free += cur until endByte

        val align = maxOf(dev.alignment.toLong(), lbs).let { if (it % lbs == 0L) it else lbs }
        val alignOff = dev.alignmentOffset.toLong()
        fun alignUp(x: Long): Long {
            val y = x - alignOff
            val a = (y + align - 1) / align * align + alignOff
            return if (a < x) x else a
        }
        val freeAligned = free.mapNotNull { r ->
            val s = alignUp(r.first)
            val e = (r.last + 1) / lbs * lbs
            if (e - s >= lbs) s until e else null
        }.toMutableList()
        val freeTotal = freeAligned.sumOf { it.last + 1 - it.first }

        // groups for the target slot
        val newGroups = current.groups.toMutableList()
        val groupIndex = mutableMapOf<String, Int>()
        for (g in groups) {
            val gname = g.name + target
            val idx = newGroups.indexOfFirst { it.name == gname }
            val entry = Lp.Group(gname, 0, g.size)
            if (idx >= 0) { newGroups[idx] = entry.copy(flags = newGroups[idx].flags); groupIndex[g.name] = idx }
            else { newGroups += entry; groupIndex[g.name] = newGroups.size - 1 }
            val sum = g.partitions.sumOf { roundUp(sizes[it] ?: 0L, lbs) }
            if (g.size > 0 && sum > g.size) {
                throw IOException("The ROM's partitions (${gb(sum)}) don't fit its own group limit (${gb(g.size)}). Bad ROM zip?")
            }
        }

        // allocate, biggest first so it splits less
        val order = sizes.keys.sortedByDescending { sizes[it] }
        val needed = order.sumOf { roundUp(sizes.getValue(it), lbs) }
        if (needed > freeTotal) {
            throw IOException("Not enough room in super for this ROM on the other slot: it needs ${gb(needed)}, there's ${gb(freeTotal)} free " +
                "next to the ROM you're running. Nothing was changed.")
        }
        val alloc = mutableMapOf<String, List<Lp.Extent>>()
        for (n in order) {
            var left = roundUp(sizes.getValue(n), lbs)
            val ext = mutableListOf<Lp.Extent>()
            val it = freeAligned.listIterator()
            while (left > 0 && it.hasNext()) {
                val r = it.next()
                val avail = r.last + 1 - r.first
                if (avail <= 0) continue
                val take = minOf(avail, left)
                ext += Lp.Extent(take / Lp.SECTOR, Lp.TARGET_LINEAR, r.first / Lp.SECTOR, 0)
                left -= take
                val ns = alignUp(r.first + take)
                if (ns <= r.last) it.set(ns until r.last + 1) else it.remove()
            }
            if (left > 0) throw IOException("Not enough contiguous room in super for $n. Nothing was changed.")
            alloc[n] = ext
        }

        // new table: running slot's table, minus the other slot's partitions, plus the ROM's
        val keep = current.partitions.filter { !it.name.endsWith(target) }
        val added = order.map { n ->
            val g = groups.firstOrNull { n in it.partitions } ?: throw IOException("$n isn't in any group")
            Lp.Partition(n + target, Lp.ATTR_READONLY, alloc.getValue(n), groupIndex.getValue(g.name))
        }
        val meta = Lp.Metadata(current.major, current.minor, current.headerSize, current.headerTail, keep + added, newGroups, current.blockDevices)
        val blob = Lp.serialize(meta)
        if (blob.size > geo.metadataMaxSize) throw IOException("The new partition table is too big for super's metadata area.")
        return Plan(meta, blob, added, freeTotal, needed)
    }

    fun roundUp(x: Long, m: Long) = (x + m - 1) / m * m
    fun gb(b: Long) = "%.2f GB".format(b / 1e9)
}
