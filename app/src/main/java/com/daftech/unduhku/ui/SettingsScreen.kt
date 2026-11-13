package com.daftech.unduhku.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daftech.unduhku.prompt.ApiKeyStore

/**
 * Tab Pengaturan (v1.1.0) — kunci API untuk fitur "Video ke Prompt".
 *
 * Kunci disimpan di EncryptedSharedPreferences (lihat ApiKeyStore),
 * TIDAK PERNAH hardcode dan TIDAK PERNAH tampil sebagai teks biasa
 * (field kunci pakai PasswordVisualTransformation).
 *
 * Catatan jujur di layar (bukan di tempat tersembunyi):
 *  - Analisis Gemini gratis (free tier) — konten yang dikirim bisa
 *    dipakai Google untuk meningkatkan produknya.
 *  - Pakai hanya clip yang berhak kamu pakai.
 */
@Composable
fun SettingsScreen(keyStore: ApiKeyStore) {
    var geminiKey by remember { mutableStateOf(keyStore.geminiApiKey) }
    var groqKey by remember { mutableStateOf(keyStore.groqApiKey) }
    var geminiModel by remember { mutableStateOf(keyStore.geminiModelId) }
    var groqModel by remember { mutableStateOf(keyStore.groqModelId) }
    var wifiOnly by remember { mutableStateOf(keyStore.wifiOnly) }
    var saved by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Pengaturan", fontSize = 20.sp, color = Color.White)
            Text(
                if (keyStore.isEncrypted) {
                    "Kunci API tersimpan terenkripsi di HP ini."
                } else {
                    "⚠ Penyimpanan terenkripsi tidak tersedia di HP ini — " +
                        "kunci disimpan biasa. Tetap jangan bagikan kunci ke siapa pun."
                },
                fontSize = 13.sp,
                color = if (keyStore.isEncrypted) Color(0xFF9CA3AF) else Color(0xFFFBBF24)
            )
        }
        item {
            Text("Video ke Prompt", fontSize = 16.sp, color = Color.White)
            Text(
                "Isi kunci dari provider yang mau dipakai. Kunci Gemini " +
                    "gratis dari Google AI Studio; kunci Groq yang sama " +
                    "dengan yang dipakai ClipKu.",
                fontSize = 13.sp, color = Color(0xFF9CA3AF)
            )
        }
        item {
            OutlinedTextField(
                value = geminiKey,
                onValueChange = { geminiKey = it; saved = false },
                label = { Text("Kunci Gemini API") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = geminiModel,
                onValueChange = { geminiModel = it; saved = false },
                label = { Text("Model ID Gemini") },
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Model default: ${ApiKeyStore.DEFAULT_GEMINI_MODEL}. " +
                    "Kalau model tidak ditemukan (404), ganti ID di sini.",
                fontSize = 12.sp, color = Color(0xFF9CA3AF)
            )
        }
        item {
            OutlinedTextField(
                value = groqKey,
                onValueChange = { groqKey = it; saved = false },
                label = { Text("Kunci Groq API") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = groqModel,
                onValueChange = { groqModel = it; saved = false },
                label = { Text("Model ID Groq") },
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Model default: ${ApiKeyStore.DEFAULT_GROQ_MODEL}. " +
                    "Model Preview Groq bisa dicabut sewaktu-waktu — " +
                    "kalau 404, ganti ID di sini.",
                fontSize = 12.sp, color = Color(0xFF9CA3AF)
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Wi-Fi only untuk upload", fontSize = 14.sp, color = Color.White)
                    Text(
                        "Video clip dikirim ke Gemini/Groq hanya lewat " +
                            "Wi-Fi (hemat kuota seluler).",
                        fontSize = 12.sp, color = Color(0xFF9CA3AF)
                    )
                }
                Switch(checked = wifiOnly, onCheckedChange = {
                    wifiOnly = it
                    saved = false
                })
            }
        }
        item {
            Button(onClick = {
                keyStore.geminiApiKey = geminiKey
                keyStore.groqApiKey = groqKey
                keyStore.geminiModelId = geminiModel
                keyStore.groqModelId = groqModel
                keyStore.wifiOnly = wifiOnly
                saved = true
            }) { Text("Simpan Pengaturan") }
            if (saved) {
                Text(
                    "Tersimpan.",
                    fontSize = 13.sp, color = Color(0xFF4ADE80)
                )
            }
        }
        item {
            Text(
                "Catatan: analisis video dikirim ke server provider " +
                    "yang kamu pilih (Gemini / Groq). Di free tier " +
                    "Gemini, konten bisa dipakai Google untuk " +
                    "meningkatkan produknya. Pakai hanya clip yang " +
                    "berhak kamu pakai, dan jangan bagikan kunci API " +
                    "ke siapa pun.",
                fontSize = 12.sp, color = Color(0xFF9CA3AF)
            )
        }
    }
}
