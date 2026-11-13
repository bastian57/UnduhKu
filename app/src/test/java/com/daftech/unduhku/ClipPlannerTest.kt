package com.daftech.unduhku

import com.daftech.unduhku.clip.ClipPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipPlannerTest {

    @Test
    fun dbphMovie47MinutesIs282Clips() {
        // File DBZ Movie 5 yang diunduh 13 Nov: durasi 2.819.240 ms.
        val clips = ClipPlanner.buildVirtualClips(2_819_240L, 10_000L)
        assertEquals(282, clips.size)
        assertEquals(0L, clips.first().startMs)
        assertEquals(10_000L, clips.first().endMs)
        assertEquals(2_810_000L, clips.last().startMs)
        // Ekor 9.240 ms >= 2 detik -> clip sendiri, bukan digabung.
        assertEquals(2_819_240L, clips.last().endMs)
    }

    @Test
    fun shortTailMergesIntoPreviousClip() {
        // Ekor 500 ms < 2 detik -> gabung ke clip sebelumnya.
        val clips = ClipPlanner.buildVirtualClips(10_500L, 10_000L)
        assertEquals(1, clips.size)
        assertEquals(0L, clips[0].startMs)
        assertEquals(10_500L, clips[0].endMs)
    }

    @Test
    fun longTailStaysSeparate() {
        val clips = ClipPlanner.buildVirtualClips(25_000L, 10_000L)
        assertEquals(3, clips.size)
        assertEquals(20_000L, clips[2].startMs)
        assertEquals(25_000L, clips[2].endMs)
    }

    @Test
    fun segmentOptionsAndTailBoundary() {
        // 61 detik @60 dtk: ekor 1 dtk < 2 dtk -> gabung, tinggal 1 clip.
        val clips = ClipPlanner.buildVirtualClips(61_000L, 60_000L)
        assertEquals(1, clips.size)
        assertEquals(0L, clips[0].startMs)
        assertEquals(61_000L, clips[0].endMs)
        // 61 detik @30 dtk: ekor 1 dtk gabung -> 2 clip, terakhir 30-61 dtk.
        val merged = ClipPlanner.buildVirtualClips(61_000L, 30_000L)
        assertEquals(2, merged.size)
        assertEquals(30_000L, merged[1].startMs)
        assertEquals(61_000L, merged[1].endMs)
        // Batas: ekor TEPAT 2 dtk (tidak < 2 dtk) -> tetap clip sendiri.
        val boundary = ClipPlanner.buildVirtualClips(62_000L, 30_000L)
        assertEquals(3, boundary.size)
        assertEquals(60_000L, boundary[2].startMs)
        assertEquals(62_000L, boundary[2].endMs)
    }

    @Test
    fun invalidInputsAreEmpty() {
        assertTrue(ClipPlanner.buildVirtualClips(0L, 10_000L).isEmpty())
        assertTrue(ClipPlanner.buildVirtualClips(-5L, 10_000L).isEmpty())
        assertTrue(ClipPlanner.buildVirtualClips(10_000L, 0L).isEmpty())
    }

    @Test
    fun clipFileName() {
        val clips = ClipPlanner.buildVirtualClips(25_000L, 10_000L)
        val name = ClipPlanner.clipFileName("_DBPH_~1.mp4", clips[1])
        assertEquals("002_DBPH-1_0010-0020.mp4", name)
    }
}
