package com.daftech.unduhku.prompt

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Orkestrator "Video ke Prompt" (v1.1.0).
 *
 * Alur:
 *  1. Cek toggle Wi-Fi only (kalau ON dan koneksi bukan Wi-Fi → tolak
 *     dengan pesan jelas, jangan bakar kuota seluler diam-diam).
 *  2. Gemini (utama): kirim file video langsung → analisis → render.
 *  3. Groq (cadangan): ekstrak 3 keyframe → analisis → render
 *     (audio dilabeli inferred).
 *
 * Semua kerja network/IO di Dispatchers.IO; progress dilaporkan lewat
 * callback [onProgress] — pemanggil (ViewModel) yang menulis state
 * DI MAIN, bukan dari sini.
 */
class VideoPromptGenerator(
    private val app: Application,
    private val keyStore: ApiKeyStore
) {

    sealed class Outcome {
        data class Ok(val result: PromptResult, val analysis: ClipAnalysis) : Outcome()
        data class Fail(val message: String) : Outcome()
    }

    /** Generate prompt dari file clip yang sudah diekspor. */
    suspend fun generate(
        clipFile: File,
        clipDurationMs: Long,
        engine: PromptEngine,
        target: PromptTarget,
        onProgress: (String) -> Unit
    ): Outcome = withContext(Dispatchers.IO) {
        if (!clipFile.exists() || clipFile.length() <= 0L) {
            return@withContext Outcome.Fail("File clip tidak ditemukan.")
        }
        if (keyStore.wifiOnly && !isOnWifi()) {
            return@withContext Outcome.Fail(
                "\"Wi-Fi only\" aktif dan HP tidak tersambung Wi-Fi — " +
                    "hubungkan Wi-Fi, atau matikan opsi itu di Pengaturan."
            )
        }
        when (engine) {
            PromptEngine.GEMINI -> {
                onProgress("Menganalisis video dengan Gemini… (5–15 dtk)")
                when (val r = GeminiAnalyzer.analyze(
                    clipFile, keyStore.geminiApiKey, keyStore.geminiModelId
                )) {
                    is GeminiAnalyzer.Outcome.Ok -> Outcome.Ok(
                        PromptRenderer.render(r.analysis, target),
                        r.analysis
                    )
                    is GeminiAnalyzer.Outcome.Fail -> Outcome.Fail(r.message)
                }
            }
            PromptEngine.GROQ -> {
                onProgress("Mengekstrak 3 keyframe…")
                val uri = Uri.fromFile(clipFile)
                val frames = ClipFrameExtractor.extractKeyframes(
                    app, uri, clipDurationMs
                )
                if (frames.isEmpty()) {
                    return@withContext Outcome.Fail(
                        "Keyframe video tidak bisa diekstrak — " +
                            "coba pakai engine Gemini."
                    )
                }
                onProgress("Menganalisis 3 keyframe dengan Groq…")
                when (val r = GroqAnalyzer.analyze(
                    frames, keyStore.groqApiKey, keyStore.groqModelId
                )) {
                    is GroqAnalyzer.Outcome.Ok -> Outcome.Ok(
                        PromptRenderer.render(r.analysis, target),
                        r.analysis
                    )
                    is GroqAnalyzer.Outcome.Fail -> Outcome.Fail(r.message)
                }
            }
        }
    }

    /** TRUE kalau koneksi aktif saat ini adalah Wi-Fi. */
    private fun isOnWifi(): Boolean {
        return try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } catch (e: Exception) {
            false
        }
    }
}
