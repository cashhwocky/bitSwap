package com.bitdroid.swap

import java.io.IOException

/** Checks the root helper runs on a new partition table before it will use it. Pure logic, so it's testable. */
object Guard {

    /**
     * Refuses the new table for the other slot unless:
     *  - super's disk layout is unchanged
     *  - every partition that isn't the other slot's is exactly as it is now (same place, size, group)
     *  - the other slot's partitions sit only in space no running-slot partition uses, inside super,
     *    don't overlap each other, and aren't OS/firmware names
     * Returns the table, the other slot's extents by name (no suffix), and the byte ranges that must never be written.
     */
    fun checkPlan(blob: ByteArray, slots: List<Lp.Metadata>, current: String, target: String, superBytes: Long):
        Triple<Lp.Metadata, Map<String, List<Lp.Extent>>, List<LongRange>> {
        if (target != Safety.otherSuffix(current)) throw SecurityException("wrong target slot. Refused.")
        val m = Lp.parseMetadata(blob)
        val cur = slots[Safety.slotIndex(current)]
        if (m.blockDevices != cur.blockDevices || cur.blockDevices.size != 1) throw SecurityException("new table changes super's layout. Refused.")
        if (m.major != cur.major || m.minor != cur.minor || m.headerSize != cur.headerSize) throw SecurityException("new table changes the format. Refused.")
        val dev = cur.blockDevices[0]
        if (dev.size > superBytes) throw SecurityException("super is smaller than its table says. Refused.")

        val keepNow = cur.partitions.filter { !it.name.endsWith(target) }
        val keepNew = m.partitions.filter { !it.name.endsWith(target) }
        if (keepNow.size != keepNew.size) throw SecurityException("new table adds or removes running-slot partitions. Refused.")
        for (p in keepNow) {
            val q = keepNew.firstOrNull { it.name == p.name } ?: throw SecurityException("new table drops ${p.name}. Refused.")
            if (q.attributes != p.attributes || q.extents != p.extents ||
                m.groups.getOrNull(q.groupIndex)?.name != cur.groups.getOrNull(p.groupIndex)?.name) {
                throw SecurityException("new table changes ${p.name}. Refused.")
            }
        }

        // what's mapped right now comes from the running slot's table
        val forb = Lp.usedRanges(cur.partitions.filter { !it.name.endsWith(target) }) +
            listOf(0L until dev.firstLogicalSector * Lp.SECTOR)
        val mine = mutableListOf<LongRange>()
        val targets = mutableMapOf<String, List<Lp.Extent>>()
        for (p in m.partitions.filter { it.name.endsWith(target) }) {
            val base = p.name.removeSuffix(target)
            if (base in Safety.NEVER || base in Safety.OS_PLAIN || base.isEmpty()) throw SecurityException("new table has ${p.name}. Refused.")
            if (base in targets) throw SecurityException("new table has ${p.name} twice. Refused.")
            if (p.extents.isEmpty()) throw IOException("${p.name} has no space")
            for (e in p.extents) {
                if (e.targetType != Lp.TARGET_LINEAR || e.targetSource != 0) throw SecurityException("odd extent in ${p.name}. Refused.")
                val r = (e.targetData * Lp.SECTOR) until (e.end * Lp.SECTOR)
                if (e.numSectors <= 0 || r.first < dev.firstLogicalSector * Lp.SECTOR || r.last >= dev.size) throw SecurityException("${p.name} is outside super. Refused.")
                if (forb.any { Lp.overlaps(it, r) }) throw SecurityException("${p.name} would overwrite the running ROM. Refused.")
                if (mine.any { Lp.overlaps(it, r) }) throw SecurityException("${p.name} overlaps another new partition. Refused.")
                mine += r
            }
            targets[base] = p.extents
        }
        return Triple(m, targets, forb)
    }
}
