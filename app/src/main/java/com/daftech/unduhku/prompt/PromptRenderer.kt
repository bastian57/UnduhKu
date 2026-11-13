package com.daftech.unduhku.prompt

/**
 * Render SATU prompt text-to-video dari [ClipAnalysis] — logika MURNI,
 * tanpa Android, bisa di-unit-test di JVM (lihat PromptRendererTest).
 *
 * Mengikuti rumus resmi Google (panduan Veo 3.1):
 * [Cinematography] + [Subject] + [Action] + [Context] +
 * [Style & Ambiance] + baris audio. Satu gerakan kamera + satu aksi
 * per beat. Exclusions ditulis sebagai klausa "with no …" DI DALAM
 * prompt — TIDAK ADA blok negative prompt terpisah (Veo tidak punya
 * field itu; panduan Google menyuruh deskripsikan yang dikecualikan
 * dengan kalimat positif).
 *
 * Target:
 *  - OMNI_10S (default): dua beat [00:00-00:05] / [00:05-00:10].
 *  - VEO_8S: KOMPRES (bukan potong mentah) ke [00:00-00:04] /
 *    [00:04-00:08]; aksi kedua digabung ke beat pertama sebagai payoff.
 *    JANGAN pernah emit prompt 10 detik lalu suruh user "extend" —
 *    Veo Extend sudah pensiun di Flow (5 Nov 2026).
 */
object PromptRenderer {

    /** Steering live-action — WAJIB di setiap prompt (sumber sering anime;
     *  tanpa ini model balik menggambar gaya animasi). */
    const val LIVE_ACTION_STEERING =
        "Live-action, photorealistic, shot on a cinema camera, " +
            "natural skin texture, realistic physics, 24fps film look."

    /** Exclusions sebagai klausa positif di dalam prompt. */
    const val EXCLUSIONS =
        "with no text on screen, no watermark, no cartoon or anime " +
            "style, no exaggerated features, no brand logos"

    /** Batas kata prompt (riset: ~120–150 kata, ~900 karakter). */
    const val MAX_WORDS = 150

    fun wordCount(text: String): Int =
        text.split(Regex("\\s+")).count { it.isNotBlank() }

    fun render(analysis: ClipAnalysis, target: PromptTarget): PromptResult {
        val prompt = when (target) {
            PromptTarget.OMNI_10S -> renderOmni(analysis)
            PromptTarget.VEO_8S -> renderVeo(analysis)
        }
        val finalPrompt = enforceWordCap(prompt, analysis)
        return PromptResult(
            prompt = finalPrompt,
            glossId = buildGloss(analysis, target),
            engine = if (analysis.audioHeard) PromptEngine.GEMINI else PromptEngine.GROQ,
            target = target,
            audioInferred = !analysis.audioHeard
        )
    }

    private fun cinematography(analysis: ClipAnalysis, closeUp: Boolean): String {
        val move = analysis.cameraMovement.trim().lowercase()
            .takeIf { it.isNotEmpty() } ?: "static"
        val shot = if (closeUp) "close-up" else "medium shot"
        return "$move, $shot, shallow depth of field"
    }

    private fun audioLines(analysis: ClipAnalysis): String {
        val lines = ArrayList<String>()
        val dialogue = analysis.dialogue?.trim().orEmpty()
        if (dialogue.isNotEmpty() && analysis.audioHeard) {
            // Satu kalimat pendek yang muat diucapkan dalam beat-nya.
            val short = dialogue.split(Regex("\\s+")).take(12).joinToString(" ")
            lines += "Dialogue: a voice says, \"$short\"."
        }
        val cues = analysis.audioCues.trim()
        if (cues.isNotEmpty()) {
            lines += if (analysis.audioHeard) {
                "SFX: $cues."
            } else {
                // Jalur Groq TIDAK dengar audio — label jujur DI DALAM prompt.
                "SFX: $cues (inferred from visuals, not heard)."
            }
        }
        lines += "Ambient noise: ${analysis.mood}, ${analysis.setting}."
        return lines.joinToString(" ")
    }

    private fun renderOmni(a: ClipAnalysis): String {
        val beat1 = "[00:00-00:05] ${cinematography(a, false)}, " +
            "${a.subject}, ${a.actionBeats[0]}, ${a.setting}. " +
            "$LIVE_ACTION_STEERING ${a.lighting}. ${a.mood}. " +
            audioLines(a)
        val beat2 = "[00:05-00:10] ${cinematography(a, true)}, " +
            "${a.subject}, ${a.actionBeats[1]}, ${a.setting}. " +
            "$LIVE_ACTION_STEERING $EXCLUSIONS."
        return "$beat1\n$beat2"
    }

    private fun renderVeo(a: ClipAnalysis): String {
        // Kompres: beat 1 = aksi pertama, beat 2 = payoff (aksi kedua)
        // dengan kalimat dialog kalau ada — durasi total 8 detik.
        val beat1 = "[00:00-00:04] ${cinematography(a, false)}, " +
            "${a.subject}, ${a.actionBeats[0]}, ${a.setting}. " +
            "$LIVE_ACTION_STEERING ${a.lighting}. ${a.mood}. " +
            audioLines(a)
        val beat2 = "[00:04-00:08] ${cinematography(a, true)}, " +
            "${a.subject}, ${a.actionBeats[1]}. " +
            "$LIVE_ACTION_STEERING $EXCLUSIONS."
        return "$beat1\n$beat2"
    }

    /**
     * Paksa prompt ≤ MAX_WORDS. Pemangkasan HANYA menyentuh kalimat
     * dekoratif (lighting, mood, Ambient noise, setting) — TIDAK PERNAH
     * baris audio (Dialogue/SFX) atau exclusions, karena itu yang
     * menentukan hasil di Flow. Pemangkasan literal per nilai field,
     * bukan regex kalimat-pertama: regex lama sempat menghapus baris
     * Dialogue karena kalimat lighting/mood di data nyata sering
     * huruf kecil, sehingga regex loncat ke kalimat berhuruf kapital
     * pertama — yaitu Dialogue (bug ketemu unit test 13 Nov 2026).
     * Deterministik — hasil yang sama untuk input yang sama.
     */
    fun enforceWordCap(prompt: String, analysis: ClipAnalysis): String {
        var text = prompt
        if (wordCount(text) <= MAX_WORDS) return text
        // (1) Buang kalimat lighting (dekoratif, aman dibuang).
        val lighting = analysis.lighting.trim().trimEnd('.')
        if (lighting.isNotEmpty()) {
            text = text.replaceFirst(". $lighting.", ".")
        }
        if (wordCount(text) <= MAX_WORDS) return text
        // (2) Buang kalimat mood.
        val mood = analysis.mood.trim().trimEnd('.')
        if (mood.isNotEmpty()) {
            text = text.replaceFirst(". $mood.", ".")
        }
        if (wordCount(text) <= MAX_WORDS) return text
        // (3) Buang baris Ambient noise.
        text = text.replaceFirst(Regex("Ambient noise: [^.]+\\. ?"), "")
        if (wordCount(text) <= MAX_WORDS) return text
        // (4) Pendekkan setting (muncul di kedua beat) ke 8 kata pertama.
        val setting = analysis.setting.trim().trimEnd('.')
        if (setting.isNotEmpty()) {
            val shortSetting = setting.split(Regex("\\s+"))
                .take(8).joinToString(" ")
            if (shortSetting != setting) {
                text = text.replace(setting, shortSetting)
            }
        }
        if (wordCount(text) <= MAX_WORDS) return text
        // (4b) Pendekkan tiap aksi ke 10 kata pertama.
        for (beat in analysis.actionBeats) {
            val cleanBeat = beat.trim().trimEnd('.')
            if (cleanBeat.isEmpty()) continue
            val shortBeat = cleanBeat.split(Regex("\\s+"))
                .take(10).joinToString(" ")
            if (shortBeat != cleanBeat) {
                text = text.replace(cleanBeat, shortBeat)
            }
            if (wordCount(text) <= MAX_WORDS) return text
        }
        // (4c) Pendekkan daftar SFX ke 8 kata pertama.
        val cues = analysis.audioCues.trim().trimEnd('.')
        if (cues.isNotEmpty()) {
            val shortCues = cues.split(Regex("\\s+"))
                .take(8).joinToString(" ")
            if (shortCues != cues) {
                text = text.replace(cues, shortCues)
            }
        }
        if (wordCount(text) <= MAX_WORDS) return text
        // (5) Fallback terakhir: potong kata dari akhir (jangan potong
        // baris timestamp di awal beat — pemotongan dari akhir aman).
        // Langkah 1–4c mestinya sudah cukup untuk input realistis;
        // langkah ini hanya untuk input patologis.
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size <= MAX_WORDS) return text
        return words.take(MAX_WORDS).joinToString(" ").trimEnd('.') + "."
    }

    /** Penjelasan singkat Bahasa Indonesia — BUKAN prompt, jangan disalin. */
    private fun buildGloss(a: ClipAnalysis, target: PromptTarget): String {
        val targetText = when (target) {
            PromptTarget.OMNI_10S ->
                "Prompt untuk Google Flow — Gemini Omni Flash 1.2, " +
                    "10 detik, 720p, dua bagian (0–5 dtk dan 5–10 dtk)."
            PromptTarget.VEO_8S ->
                "Versi 8 detik untuk Veo 3.1 Lite — 2 detik dipangkas " +
                    "dari versi 10 detik. Jangan pakai Extend (sudah " +
                    "pensiun di Flow)."
        }
        val audioText = if (a.audioHeard) {
            "Suara di prompt ini didengar langsung dari video."
        } else {
            "Suara di prompt ini HANYA diperkirakan dari gambar " +
                "(engine Groq tidak bisa mendengar audio) — cek sebelum " +
                "ditempel ke Flow."
        }
        val cameraText = "Gerakan kamera: ${a.cameraMovement} " +
            "(keyakinan: ${a.cameraConfidence})."
        return "$targetText $audioText $cameraText " +
            "Salin prompt Inggris di atas, tempel di Flow " +
            "(Text-to-Video), lalu generate."
    }
}
