package com.daftech.unduhku.clip

import com.daftech.unduhku.model.VirtualClip

/**
 * Perencana clip VIRTUAL — logika murni, tanpa Android.
 * TIDAK membuat file video apa pun: hasilnya hanya daftar
 * (index, startMs, endMs). File fisik HANYA dibuat saat user
 * menekan Export untuk clip yang dicentang (ClipExporter).
 *
 * Aturan:
 *  - Potong tiap [segmentMs] dari 0 sampai durasi.
 *  - Ekor < 2 detik digabung ke clip sebelumnya (jangan buat clip
 *    0,3 detik yang tidak berguna) — kalau cuma ada 1 clip, ekor
 *    tetap menempel di clip itu (durasi clip jadi = durasi video).
 *  - Durasi <= 0 = daftar kosong.
 */
object ClipPlanner {

    const val MIN_TAIL_MS = 2_000L

    val SEGMENT_OPTIONS_MS = listOf(10_000L, 15_000L, 30_000L, 60_000L)

    fun buildVirtualClips(durationMs: Long, segmentMs: Long): List<VirtualClip> {
        if (durationMs <= 0L || segmentMs <= 0L) return emptyList()
        val ranges = ArrayList<Pair<Long, Long>>()
        var start = 0L
        while (start < durationMs) {
            val end = minOf(start + segmentMs, durationMs)
            ranges.add(start to end)
            start = end
        }
        // Ekor terlalu pendek -> gabung ke clip sebelumnya.
        if (ranges.size > 1) {
            val last = ranges.last()
            if (last.second - last.first < MIN_TAIL_MS) {
                val prev = ranges[ranges.size - 2]
                ranges[ranges.size - 2] = prev.first to last.second
                ranges.removeAt(ranges.size - 1)
            }
        }
        return ranges.mapIndexed { index, (clipStart, clipEnd) ->
            VirtualClip(index = index, startMs = clipStart, endMs = clipEnd)
        }
    }

    fun clipFileName(baseName: String, clip: VirtualClip): String {
        val base = baseName.substringBeforeLast('.').takeIf { it.isNotEmpty() }
            ?: baseName
        val safe = base.replace(Regex("[^A-Za-z0-9 _-]"), "").trim()
            .replace(' ', '-').take(40).ifEmpty { "clip" }
        val startSec = clip.startMs / 1000
        val endSec = clip.endMs / 1000
        return "%03d_%s_%04d-%04d.mp4".format(clip.index + 1, safe, startSec, endSec)
    }
}
