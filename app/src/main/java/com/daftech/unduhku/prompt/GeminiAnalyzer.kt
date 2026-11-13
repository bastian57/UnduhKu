package com.daftech.unduhku.prompt

import android.util.Base64
import android.util.Log
import java.io.File
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
 * Analisis video LANGSUNG via Gemini API (jalur utama).
 *
 * Mengirim file MP4 inline base64 ke
 * generativelanguage.googleapis.com/v1beta/models/{model}:generateContent
 * dan meminta structured JSON (response_mime_type + response_schema).
 * Gemini menonton video ~1 fps + MENDENGAR audio — satu-satunya jalur
 * yang bisa mengisi field dialog/audio dengan benar.
 *
 * Batas (riset Nov 2026): inline <100 MB; file di atas 90 MB ditolak
 * di sini dengan pesan jelas (jangan kirim 90 MB base64 sia-sia).
 * Model ID TIDAK hardcode mati — diambil dari Settings; kalau API
 * jawab 404, pesannya bilang "model tidak ditemukan, ganti di
 * Pengaturan" (bukan error mentah).
 *
 * Kunci API TIDAK PERNAH di-log (bahkan URL-nya tidak di-log utuh).
 */
object GeminiAnalyzer {

    private const val TAG = "UnduhKu"
    private const val MAX_INLINE_BYTES = 90L * 1024L * 1024L
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private const val INSTRUCTION = "Analyse this 10-second video clip " +
        "and describe it for regenerating a realistic live-action version " +
        "in Google Flow. Return the JSON object described by the schema. " +
        "Rules: describe the subject by visible traits ONLY — NEVER write " +
        "a franchise, movie, cartoon or character name. One camera " +
        "movement only. Dialogue: write the exact short line you hear, " +
        "or empty string if nobody speaks. Be concrete and visual."

    /** Hasil: sukses (analisis) atau gagal (pesan ramah untuk UI). */
    sealed class Outcome {
        data class Ok(val analysis: ClipAnalysis) : Outcome()
        data class Fail(val message: String) : Outcome()
    }

    suspend fun analyze(
        videoFile: File,
        apiKey: String,
        modelId: String
    ): Outcome = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Outcome.Fail(
                "Kunci Gemini belum diisi — buka tab Pengaturan."
            )
        }
        if (!videoFile.exists() || videoFile.length() <= 0L) {
            return@withContext Outcome.Fail("File video tidak ditemukan.")
        }
        if (videoFile.length() > MAX_INLINE_BYTES) {
            return@withContext Outcome.Fail(
                "Video terlalu besar untuk analisis inline Gemini " +
                    "(${videoFile.length() / 1024 / 1024} MB, maks 90 MB)."
            )
        }
        val base64 = try {
            Base64.encodeToString(videoFile.readBytes(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "GeminiAnalyzer: baca file gagal", e)
            return@withContext Outcome.Fail("File video tidak bisa dibaca.")
        }
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().put("text", INSTRUCTION))
                    put(JSONObject().put("inlineData", JSONObject().apply {
                        put("mimeType", "video/mp4")
                        put("data", base64)
                    }))
                })
            }))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.2)
                put("maxOutputTokens", 2048)
                put("responseMimeType", "application/json")
                put("responseSchema", ClipAnalysisSchema.geminiResponseSchema())
            })
        }
        val request = Request.Builder()
            .url(
                "https://generativelanguage.googleapis.com/v1beta/models/" +
                    "$modelId:generateContent"
            )
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Outcome.Fail(
                        geminiErrorMessage(response.code, raw, modelId)
                    )
                }
                return@withContext parseResponse(raw)
            }
        } catch (e: Exception) {
            Log.w(TAG, "GeminiAnalyzer: request gagal", e)
            Outcome.Fail(
                "Tidak bisa menghubungi Gemini — cek koneksi internet. " +
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
                    "Gemini menolak: ${err.optString("message", "error API")}"
                )
            }
            val text = root.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
            val analysis = ClipAnalysis.fromJson(JSONObject(text), true)
            Outcome.Ok(analysis)
        } catch (e: Exception) {
            Log.w(TAG, "GeminiAnalyzer: parse respons gagal", e)
            Outcome.Fail(
                "Gemini menjawab tapi formatnya tidak bisa dibaca — " +
                    "coba lagi."
            )
        }
    }

    /** Pesan error ramah per kode HTTP — tanpa membocorkan kunci. */
    private fun geminiErrorMessage(code: Int, raw: String, modelId: String): String {
        val detail = try {
            JSONObject(raw).optJSONObject("error")
                ?.optString("message", "").orEmpty()
        } catch (e: Exception) {
            ""
        }
        return when (code) {
            404 -> "Model Gemini \"$modelId\" tidak ditemukan (404) — " +
                "ganti Model ID Gemini di Pengaturan."
            400 -> if (detail.contains("API key", ignoreCase = true)) {
                "Kunci Gemini ditolak — periksa lagi di Pengaturan."
            } else {
                "Gemini menolak request (400): $detail"
            }
            429 -> "Kuota gratis Gemini habis untuk hari ini (429) — " +
                "coba lagi besok, atau pakai engine Groq."
            401, 403 -> "Kunci Gemini tidak punya akses (HTTP $code) — " +
                "periksa kunci di Pengaturan."
            else -> "Gemini error HTTP $code" +
                (if (detail.isNotBlank()) ": $detail" else "")
        }
    }
}
