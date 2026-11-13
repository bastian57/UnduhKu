package com.daftech.unduhku.prompt

import android.util.Base64
import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Analisis keyframe via Groq vision (jalur CADANGAN — pakai kunci
 * Groq yang sudah dimiliki user, tanpa kunci baru).
 *
 * Mengirim 3 frame JPEG (0,5 / 5,0 / 9,5 dtk) dalam SATU request
 * chat completions ke model vision (default qwen/qwen3.8-27b,
 * user-editable di Settings — riset warning: model Preview, bisa
 * dicabut sewaktu-waktu; kalau 404, pesannya bilang ganti di
 * Pengaturan). JSON mode (response_format json_object).
 *
 * BATAS JALUR INI (jujur di UI): model TIDAK dengar audio dan TIDAK
 * lihat gerakan — hanya 3 foto diam. Maka [ClipAnalysis.audioHeard]
 * selalu FALSE: baris audio di prompt dilabeli "inferred from
 * visuals, not heard", dan field camera diberi flag confidence
 * (lihat gloss di PromptRenderer).
 *
 * Kunci API TIDAK PERNAH di-log.
 */
object GroqAnalyzer {

    private const val TAG = "UnduhKu"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    private const val USER_TEXT = "Analyse these 3 keyframes (start, " +
        "middle, end) of one 10-second video clip and describe it for " +
        "regenerating a realistic live-action version in Google Flow. " +
        "Describe the subject by visible traits ONLY — NEVER write a " +
        "franchise, movie, cartoon or character name. Infer the camera " +
        "movement from framing changes between the frames and state " +
        "your confidence honestly. You cannot hear audio: put expected " +
        "sound effects in audio_cues, leave dialogue empty and set " +
        "dialogue_original and dialogue_language to null — you cannot " +
        "hear the original language. If you nevertheless infer a " +
        "spoken line from lip movement, write it in English only."

    sealed class Outcome {
        data class Ok(val analysis: ClipAnalysis) : Outcome()
        data class Fail(val message: String) : Outcome()
    }

    suspend fun analyze(
        framesJpeg: List<ByteArray>,
        apiKey: String,
        modelId: String
    ): Outcome = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Outcome.Fail(
                "Kunci Groq belum diisi — buka tab Pengaturan."
            )
        }
        if (framesJpeg.isEmpty()) {
            return@withContext Outcome.Fail(
                "Keyframe video tidak bisa diekstrak — coba clip lain."
            )
        }
        val content = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", USER_TEXT)
            })
            for (frame in framesJpeg.take(3)) {
                put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put(
                            "url",
                            "data:image/jpeg;base64," + Base64.encodeToString(
                                frame, Base64.NO_WRAP
                            )
                        )
                    })
                })
            }
        }
        val body = JSONObject().apply {
            put("model", modelId)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", ClipAnalysisSchema.GROQ_SYSTEM_INSTRUCTION)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", content)
                })
            })
            put("response_format", JSONObject().put("type", "json_object"))
            put("temperature", 0.2)
            put("max_tokens", 1500)
        }
        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Outcome.Fail(
                        groqErrorMessage(response.code, raw, modelId)
                    )
                }
                return@withContext parseResponse(raw)
            }
        } catch (e: Exception) {
            Log.w(TAG, "GroqAnalyzer: request gagal", e)
            Outcome.Fail(
                "Tidak bisa menghubungi Groq — cek koneksi internet. " +
                    "(${e.message ?: "network error"})"
            )
        }
    }

    private fun parseResponse(raw: String): Outcome {
        return try {
            val root = JSONObject(raw)
            if (root.has("error")) {
                val err = root.getJSONObject("error")
                return Outcome.Fail(
                    "Groq menolak: ${err.optString("message", "error API")}"
                )
            }
            val text = root.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
            val analysis = ClipAnalysis.fromJson(JSONObject(text), false)
            Outcome.Ok(analysis)
        } catch (e: Exception) {
            Log.w(TAG, "GroqAnalyzer: parse respons gagal", e)
            Outcome.Fail(
                "Groq menjawab tapi formatnya tidak bisa dibaca — " +
                    "coba lagi."
            )
        }
    }

    private fun groqErrorMessage(code: Int, raw: String, modelId: String): String {
        val detail = try {
            JSONObject(raw).optJSONObject("error")
                ?.optString("message", "").orEmpty()
        } catch (e: Exception) {
            ""
        }
        return when (code) {
            404 -> "Model Groq \"$modelId\" tidak ditemukan (404) — " +
                "model Preview ini mungkin sudah dicabut; ganti Model " +
                "ID Groq di Pengaturan."
            401 -> "Kunci Groq ditolak — periksa lagi di Pengaturan."
            429 -> "Kuota Groq habis (429) — tunggu sebentar lalu coba " +
                "lagi, atau pakai engine Gemini."
            else -> "Groq error HTTP $code" +
                (if (detail.isNotBlank()) ": $detail" else "")
        }
    }
}
