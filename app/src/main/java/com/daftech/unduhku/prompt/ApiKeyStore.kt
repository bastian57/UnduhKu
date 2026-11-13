package com.daftech.unduhku.prompt

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Penyimpanan kunci API + model ID (v1.1.0).
 *
 * ATURAN KEAMANAN (JANGAN dilanggar):
 *  - Kunci disimpan di EncryptedSharedPreferences (AES256-SIV kunci,
 *    AES256-GCM nilai, MasterKey AES256-GCM di AndroidKeyStore).
 *  - Kunci TIDAK PERNAH hardcode di source, TIDAK PERNAH di-log
 *    (bahkan sebagian), TIDAK PERNAH dikirim ke mana pun kecuali ke
 *    provider yang dipilih user (Gemini / Groq) lewat HTTPS.
 *  - Kalau EncryptedSharedPreferences gagal dibuat (mis. KeyStore
 *    bermasalah), jatuh ke SharedPreferences biasa dan tandai
 *    [isEncrypted] = false supaya UI bisa menampilkan peringatan —
 *    jangan pernah crash karena ini.
 */
class ApiKeyStore(context: Context) {

    companion object {
        private const val TAG = "UnduhKu"
        private const val PREFS_NAME = "unduhku_api_keys"
        private const val KEY_GEMINI = "gemini_api_key"
        private const val KEY_GROQ = "groq_api_key"
        private const val KEY_GEMINI_MODEL = "gemini_model_id"
        private const val KEY_GROQ_MODEL = "groq_model_id"
        private const val KEY_WIFI_ONLY = "wifi_only_upload"

        /** Default dari riset (Nov 2026) — user bisa ganti di Settings. */
        const val DEFAULT_GEMINI_MODEL = "gemini-3.5-flash-lite"
        const val DEFAULT_GROQ_MODEL = "qwen/qwen3.8-27b"
    }

    private val prefs: SharedPreferences

    /** TRUE kalau penyimpanan benar-benar terenkripsi. */
    var isEncrypted: Boolean = false
        private set

    init {
        // Tipe eksplisit SharedPreferences (bukan nullable): kedua cabang
        // try/catch mengembalikan platform type yang dipaksa non-null di sini.
        val loaded: SharedPreferences = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            isEncrypted = true
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w(TAG, "ApiKeyStore: EncryptedSharedPreferences gagal, " +
                "fallback ke SharedPreferences biasa", e)
            isEncrypted = false
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        prefs = loaded
    }

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GEMINI, value.trim()).apply()

    var groqApiKey: String
        get() = prefs.getString(KEY_GROQ, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GROQ, value.trim()).apply()

    var geminiModelId: String
        get() = prefs.getString(KEY_GEMINI_MODEL, DEFAULT_GEMINI_MODEL)
            ?: DEFAULT_GEMINI_MODEL
        set(value) = prefs.edit().putString(
            KEY_GEMINI_MODEL,
            value.trim().ifEmpty { DEFAULT_GEMINI_MODEL }
        ).apply()

    var groqModelId: String
        get() = prefs.getString(KEY_GROQ_MODEL, DEFAULT_GROQ_MODEL)
            ?: DEFAULT_GROQ_MODEL
        set(value) = prefs.edit().putString(
            KEY_GROQ_MODEL,
            value.trim().ifEmpty { DEFAULT_GROQ_MODEL }
        ).apply()

    /** Upload video ke cloud HANYA lewat Wi-Fi (default ON — hemat kuota). */
    var wifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, true)
        set(value) = prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    fun hasGeminiKey(): Boolean = geminiApiKey.isNotBlank()

    fun hasGroqKey(): Boolean = groqApiKey.isNotBlank()
}
