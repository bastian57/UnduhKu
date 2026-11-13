package com.daftech.unduhku.prompt

import org.json.JSONArray
import org.json.JSONObject

/**
 * Hasil analisis video — bentuk terstruktur yang dipakai PromptRenderer.
 * Diproduksi Gemini (direct-video, dengar audio) atau Groq (3 keyframe,
 * TIDAK dengar audio — lihat [audioHeard]).
 *
 * Parsing LENIENT dengan sengaja: model boleh mengembalikan field yang
 * tidak lengkap / ejaan beda; fromJson mengisi default aman supaya satu
 * respons yang sedikit meleset tidak menggagalkan seluruh generate.
 */
data class ClipAnalysis(
    /** Deskripsi subjek generik, TANPA nama franchise/karakter. */
    val subject: String,
    /** Tepat 2 aksi: beat 1 (awal) dan beat 2 (akhir/payoff). */
    val actionBeats: List<String>,
    val setting: String,
    /** Satu gerakan kamera: static / pan / tilt / dolly / tracking /
     *  handheld / zoom / crane — atau "unknown". */
    val cameraMovement: String,
    /** Keyakinan model soal gerakan kamera: high / medium / low. */
    val cameraConfidence: String,
    val lighting: String,
    val mood: String,
    /** Petunjuk suara: SFX + ambience yang terdengar / terlihat. */
    val audioCues: String,
    /** Satu kalimat dialog pendek yang terdengar, null kalau tidak ada.
     *  fix6: field ini SELALU Bahasa Inggris — model menerjemahkan
     *  dialog yang didengar saat analisis, apapun bahasa sumbernya
     *  (kasus 13 Nov 2026: dialog Tagalog lolos apa adanya ke prompt
     *  dan Flow mengucapkan Tagalog). */
    val dialogue: String?,
    /** Teks dialog PERSIS yang didengar, dalam bahasa aslinya.
     *  TIDAK PERNAH dipakai di prompt Flow — hanya dicatat di gloss
     *  Indonesia. Null kalau tidak ada dialog / jalur tidak dengar
     *  audio (Groq: selalu null). */
    val dialogueOriginal: String? = null,
    /** Nama bahasa dialog yang didengar, mis. "Tagalog", "English",
     *  "Japanese". Null kalau tidak ada dialog / tidak diketahui. */
    val dialogueLanguage: String? = null,
    /** TRUE kalau audio benar-benar didengar (Gemini). FALSE kalau audio
     *  hanya diperkirakan dari gambar (Groq) — baris audio hasil render
     *  WAJIB dilabeli "inferred from visuals, not heard". */
    val audioHeard: Boolean
) {
    companion object {
        fun fromJson(json: JSONObject, audioHeard: Boolean): ClipAnalysis {
            val subject = json.optString("subject", "").trim()
                .ifEmpty { "a person" }
            val beats = json.optJSONArray("action_beats")?.let { arr ->
                (0 until arr.length()).mapNotNull {
                    arr.optString(it, "").trim().ifEmpty { null }
                }
            } ?: emptyList()
            // Model kadang mengirim "action" string tunggal, bukan array.
            val singleAction = json.optString("action", "").trim()
            val resolvedBeats = when {
                beats.size >= 2 -> beats.take(2)
                beats.size == 1 && singleAction.isNotEmpty() ->
                    listOf(beats[0], singleAction)
                beats.size == 1 -> listOf(beats[0], beats[0])
                singleAction.isNotEmpty() -> listOf(singleAction, singleAction)
                else -> listOf(
                    "the subject moves through the scene",
                    "the scene reaches its climax"
                )
            }
            return ClipAnalysis(
                subject = subject,
                actionBeats = resolvedBeats,
                setting = json.optString("setting", "").trim()
                    .ifEmpty { "an open outdoor location" },
                cameraMovement = json.optString("camera_movement", "").trim()
                    .ifEmpty { "static" },
                cameraConfidence = json.optString("camera_confidence", "low")
                    .trim().lowercase().takeIf {
                        it in setOf("high", "medium", "low")
                    } ?: "low",
                lighting = json.optString("lighting", "").trim()
                    .ifEmpty { "natural daylight" },
                mood = json.optString("mood", "").trim()
                    .ifEmpty { "tense and dramatic" },
                audioCues = json.optString("audio_cues", "").trim()
                    .ifEmpty { "wind and distant rumble" },
                dialogue = json.optString("dialogue", "").trim()
                    .takeIf { it.isNotEmpty() },
                // fix6: field baru — lenient: JSON lama tanpa field ini
                // tetap ter-parse (default null).
                dialogueOriginal = json.optString("dialogue_original", "")
                    .trim().takeIf { it.isNotEmpty() },
                dialogueLanguage = json.optString("dialogue_language", "")
                    .trim().takeIf { it.isNotEmpty() },
                audioHeard = audioHeard
            )
        }

        /** JSON kosong yang valid — dipakai saat respons model tidak bisa
         *  diparse sama sekali (fallback terakhir, bukan error mentah). */
        fun empty(audioHeard: Boolean): ClipAnalysis =
            fromJson(JSONObject(), audioHeard)
    }
}

/** Engine analisis yang dipakai untuk satu generate. */
enum class PromptEngine { GEMINI, GROQ }

/** Target output di Google Flow. */
enum class PromptTarget {
    /** Gemini Omni Flash 1.2 — 10 detik, 720p, gratis (default). */
    OMNI_10S,
    /** Veo 3.1 Lite — 8 detik, gratis; prompt dikompres. */
    VEO_8S
}

/** Hasil akhir: prompt Inggris siap salin + gloss Indonesia (BUKAN prompt,
 *  hanya penjelasan — gloss TIDAK BOLEH ikut tersalin ke Flow). */
data class PromptResult(
    val prompt: String,
    val glossId: String,
    val engine: PromptEngine,
    val target: PromptTarget,
    /** TRUE kalau baris audio di prompt hanya perkiraan dari gambar. */
    val audioInferred: Boolean
)

/** Skema JSON yang diminta ke model analisis (dipakai Gemini
 *  response_schema dan instruksi JSON Groq — satu sumber kebenaran). */
object ClipAnalysisSchema {
    /** response_schema Gemini (JSON Schema subset). */
    fun geminiResponseSchema(): JSONObject {
        fun strProp(desc: String) = JSONObject().apply {
            put("type", "STRING")
            put("description", desc)
        }
        val props = JSONObject().apply {
            put("subject", strProp(
                "Generic physical description of the main subject. " +
                    "NEVER a franchise or character name. " +
                    "Example: a muscular young man with spiky black hair, " +
                    "wearing an orange martial-arts gi."
            ))
            put("action_beats", JSONObject().apply {
                put("type", "ARRAY")
                put("description", "Exactly 2 actions: first half, second half.")
                put("items", JSONObject().put("type", "STRING"))
            })
            put("setting", strProp("Where the scene happens, time of day."))
            put("camera_movement", strProp(
                "One of: static, pan, tilt, dolly, tracking, handheld, " +
                    "zoom, crane, unknown."
            ))
            put("camera_confidence", strProp("One of: high, medium, low."))
            put("lighting", strProp("Lighting description, short."))
            put("mood", strProp("Mood of the scene, short."))
            put("audio_cues", strProp(
                "Sound effects and ambience heard in the clip, short."
            ))
            put("dialogue", strProp(
                "One short spoken line heard in the clip, TRANSLATED " +
                    "into English — this field is ALWAYS English, " +
                    "whatever language was spoken (the Flow voiceover " +
                    "follows this text), or empty string if nobody " +
                    "speaks."
            ))
            put("dialogue_original", strProp(
                "The exact spoken line as heard, in the ORIGINAL " +
                    "language, NEVER translated (used only for the " +
                    "Indonesian gloss, never in the prompt), or empty " +
                    "string if nobody speaks."
            ))
            put("dialogue_language", strProp(
                "Name of the language spoken in the clip, e.g. " +
                    "\"Tagalog\", \"English\", \"Japanese\", or empty " +
                    "string if nobody speaks."
            ))
        }
        return JSONObject().apply {
            put("type", "OBJECT")
            put("properties", props)
            put("required", JSONArray(listOf(
                "subject", "action_beats", "setting", "camera_movement",
                "camera_confidence", "lighting", "mood", "audio_cues",
                "dialogue", "dialogue_original", "dialogue_language"
            )))
        }
    }

    /** Instruksi JSON untuk Groq (JSON mode, tanpa response_schema). */
    const val GROQ_SYSTEM_INSTRUCTION = "You analyse video keyframes and " +
        "return ONLY a JSON object with these fields: subject (generic " +
        "physical description, NEVER a franchise or character name), " +
        "action_beats (array of exactly 2 strings: first half, second " +
        "half), setting, camera_movement (one of: static, pan, tilt, " +
        "dolly, tracking, handheld, zoom, crane, unknown), " +
        "camera_confidence (high, medium or low), lighting, mood, " +
        "audio_cues (sound effects you would EXPECT from what you see — " +
        "you cannot hear the clip), dialogue (empty string, you cannot " +
        "hear the clip), dialogue_original (null — you cannot hear the " +
        "original language), dialogue_language (null — you cannot hear " +
        "the original language). If you nevertheless infer a spoken " +
        "line from lip movement, write it in English only. " +
        "No markdown, no extra text, JSON only."
}
