package com.daftech.unduhku

import com.daftech.unduhku.prompt.ClipAnalysis
import com.daftech.unduhku.prompt.PromptRenderer
import com.daftech.unduhku.prompt.PromptTarget
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptRendererTest {

    private fun bardockAnalysis(audioHeard: Boolean) = ClipAnalysis(
        subject = "a muscular young man with spiky black hair, " +
            "wearing an orange martial-arts gi",
        actionBeats = listOf(
            "stares in shock at a fleet of armored soldiers floating " +
                "in a starry sky",
            "clenches his fist and charges toward the fleet"
        ),
        setting = "a rocky desert plain at sunset",
        cameraMovement = "slow dolly-in",
        cameraConfidence = "medium",
        lighting = "warm golden-hour light with long shadows",
        mood = "tense and dramatic",
        audioCues = "wind whooshing, distant explosions",
        dialogue = "This ends now",
        audioHeard = audioHeard
    )

    @Test
    fun omniHasTwoTenSecondBeats() {
        val result = PromptRenderer.render(
            bardockAnalysis(true), PromptTarget.OMNI_10S
        )
        assertTrue(result.prompt.contains("[00:00-00:05]"))
        assertTrue(result.prompt.contains("[00:05-00:10]"))
        assertFalse(result.prompt.contains("[00:00-00:04]"))
        assertTrue(result.prompt.contains("Live-action, photorealistic"))
    }

    @Test
    fun veoCompressesToEightSeconds() {
        val result = PromptRenderer.render(
            bardockAnalysis(true), PromptTarget.VEO_8S
        )
        assertTrue(result.prompt.contains("[00:00-00:04]"))
        assertTrue(result.prompt.contains("[00:04-00:08]"))
        assertFalse(result.prompt.contains("[00:05-00:10]"))
        assertTrue(result.glossId.contains("8 detik"))
    }

    @Test
    fun exclusionsAreInlineNeverSeparateBlock() {
        val result = PromptRenderer.render(
            bardockAnalysis(true), PromptTarget.OMNI_10S
        )
        assertTrue(result.prompt.contains("with no text on screen"))
        assertTrue(result.prompt.contains("no cartoon or anime style"))
        assertFalse(result.prompt.contains("Negative prompt"))
    }

    @Test
    fun dialogueOnlyWhenAudioHeard() {
        val heard = PromptRenderer.render(
            bardockAnalysis(true), PromptTarget.OMNI_10S
        )
        assertTrue(heard.prompt.contains("Dialogue:"))
        assertTrue(heard.prompt.contains("This ends now"))
        assertFalse(heard.audioInferred)

        val inferred = PromptRenderer.render(
            bardockAnalysis(false), PromptTarget.OMNI_10S
        )
        assertFalse(inferred.prompt.contains("Dialogue:"))
        assertTrue(inferred.audioInferred)
        assertTrue(
            inferred.prompt.contains("(inferred from visuals, not heard)")
        )
        assertTrue(inferred.glossId.contains("diperkirakan dari gambar"))
    }

    @Test
    fun promptStaysWithinWordCap() {
        // Analisis dengan field super panjang — renderer WAJIB memangkas.
        val long = bardockAnalysis(true).copy(
            setting = ("a vast rocky desert plain at sunset with " +
                "towering mesas and drifting dust ".repeat(6)).trim(),
            audioCues = "wind whooshing, distant explosions, " +
                "metallic clanking, shouting soldiers, rumbling thunder ".repeat(4),
            actionBeats = listOf(
                "stares in shock at a fleet of armored soldiers " +
                    "floating in a starry sky above the desert ".repeat(3),
                "clenches his fist and charges toward the fleet ".repeat(3)
            )
        )
        val result = PromptRenderer.render(long, PromptTarget.OMNI_10S)
        assertTrue(
            "prompt ${PromptRenderer.wordCount(result.prompt)} kata, " +
                "maks ${PromptRenderer.MAX_WORDS}",
            PromptRenderer.wordCount(result.prompt) <= PromptRenderer.MAX_WORDS
        )
        assertTrue(result.prompt.contains("[00:00-00:05]"))
        assertTrue(result.prompt.contains("[00:05-00:10]"))
    }

    @Test
    fun wordCapNeverRemovesDialogueOrSfx() {
        // Regresi 13 Nov 2026: enforceWordCap lama pakai regex kalimat
        // pertama berhuruf kapital dan menghapus baris Dialogue padahal
        // lighting/mood di data nyata huruf kecil. Prompt JAUH di atas
        // cap — Dialogue + SFX + exclusions WAJIB selamat.
        val long = bardockAnalysis(true).copy(
            setting = ("a vast rocky desert plain at sunset with " +
                "towering mesas and drifting dust ".repeat(6)).trim(),
            audioCues = "wind whooshing, distant explosions, " +
                "metallic clanking, shouting soldiers, rumbling thunder ".repeat(4),
            actionBeats = listOf(
                "stares in shock at a fleet of armored soldiers " +
                    "floating in a starry sky above the desert ".repeat(3),
                "clenches his fist and charges toward the fleet ".repeat(3)
            )
        )
        val result = PromptRenderer.render(long, PromptTarget.OMNI_10S)
        assertTrue(result.prompt.contains("Dialogue:"))
        assertTrue(result.prompt.contains("This ends now"))
        assertTrue(result.prompt.contains("SFX:"))
        assertTrue(result.prompt.contains("with no text on screen"))
        assertTrue(
            PromptRenderer.wordCount(result.prompt) <= PromptRenderer.MAX_WORDS
        )
    }

    @Test
    fun glossNeverContainsPromptText() {
        val result = PromptRenderer.render(
            bardockAnalysis(true), PromptTarget.OMNI_10S
        )
        assertFalse(result.glossId.contains("[00:00-00:05]"))
        assertFalse(result.glossId.contains("Live-action, photorealistic"))
    }

    @Test
    fun fromJsonIsLenient() {
        // Respons model tidak lengkap — tetap jadi analisis valid.
        val analysis = ClipAnalysis.fromJson(
            JSONObject("{\"subject\": \"a warrior\"}"), true
        )
        assertEquals("a warrior", analysis.subject)
        assertEquals(2, analysis.actionBeats.size)
        assertEquals("low", analysis.cameraConfidence)
        // "action" string tunggal -> dipakai untuk kedua beat.
        val single = ClipAnalysis.fromJson(
            JSONObject(
                "{\"subject\": \"a warrior\", " +
                    "\"action\": \"runs across the field\"}"
            ),
            false
        )
        assertEquals("runs across the field", single.actionBeats[0])
        assertEquals("runs across the field", single.actionBeats[1])
        assertFalse(single.audioHeard)
    }

    @Test
    fun sampleTimesFollowFiveFiftyNinetyFiveRule() {
        val times = com.daftech.unduhku.prompt.ClipFrameExtractor
            .sampleTimesMs(10_000L)
        assertEquals(listOf(500L, 5_000L, 9_500L), times)
        val short = com.daftech.unduhku.prompt.ClipFrameExtractor
            .sampleTimesMs(6_000L)
        assertEquals(listOf(300L, 3_000L, 5_700L), short)
        assertTrue(
            com.daftech.unduhku.prompt.ClipFrameExtractor
                .sampleTimesMs(0L).isEmpty()
        )
    }
}
