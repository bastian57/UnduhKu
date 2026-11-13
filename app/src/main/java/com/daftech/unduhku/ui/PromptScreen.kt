package com.daftech.unduhku.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daftech.unduhku.prompt.PromptEngine
import com.daftech.unduhku.prompt.PromptRenderer
import com.daftech.unduhku.prompt.PromptTarget

/**
 * Layar "Video ke Prompt" (v1.1.0).
 *
 * Alur: pilih engine (Gemini / Groq — tampilkan mana yang kuncinya
 * sudah diisi) → Generate → prompt Inggris lengkap → Salin → user
 * tempel sendiri di Google Flow. Toggle target (Omni 10 dtk / Veo
 * 8 dtk) me-render ulang dari analisis terakhir TANPA panggil API
 * lagi. Gloss Indonesia HANYA penjelasan — tombol Salin HANYA
 * menyalin prompt Inggris, gloss tidak ikut.
 *
 * App TIDAK generate video dan TIDAK memanggil Flow — itu kerjaan
 * user di Flow (pembagian kerja v1.1.0).
 */
@Composable
fun PromptScreen(
    viewModel: UnduhkuViewModel,
    onOpenSettings: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Video ke Prompt", fontSize = 20.sp, color = Color.White)
                TextButton(onClick = { viewModel.requestClosePrompt() }) {
                    Text("Tutup")
                }
            }
            Text(
                viewModel.promptClipName,
                fontSize = 13.sp, color = Color(0xFF9CA3AF)
            )
            Text(
                "App menganalisis clip lalu menulis prompt text-to-video " +
                    "Bahasa Inggris. Salin prompt-nya, tempel di Google " +
                    "Flow (Text-to-Video), generate di sana.",
                fontSize = 13.sp, color = Color(0xFF9CA3AF)
            )
        }
        item {
            Text("Engine analisis:", fontSize = 14.sp, color = Color.White)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val geminiLabel = "Gemini" +
                    if (viewModel.promptGeminiKeySet) " ✓" else " (kunci kosong)"
                val groqLabel = "Groq" +
                    if (viewModel.promptGroqKeySet) " ✓" else " (kunci kosong)"
                if (viewModel.promptEngine == PromptEngine.GEMINI) {
                    Button(onClick = { viewModel.updatePromptEngine(PromptEngine.GEMINI) }) {
                        Text(geminiLabel, fontSize = 13.sp)
                    }
                    OutlinedButton(onClick = { viewModel.updatePromptEngine(PromptEngine.GROQ) }) {
                        Text(groqLabel, fontSize = 13.sp)
                    }
                } else {
                    OutlinedButton(onClick = { viewModel.updatePromptEngine(PromptEngine.GEMINI) }) {
                        Text(geminiLabel, fontSize = 13.sp)
                    }
                    Button(onClick = { viewModel.updatePromptEngine(PromptEngine.GROQ) }) {
                        Text(groqLabel, fontSize = 13.sp)
                    }
                }
            }
            Text(
                if (viewModel.promptEngine == PromptEngine.GEMINI) {
                    "Gemini menonton video + mendengar audio — hasil " +
                        "paling lengkap. Butuh kunci Gemini (gratis, " +
                        "AI Studio)."
                } else {
                    "Groq menganalisis 3 foto diam — cepat, pakai kunci " +
                        "Groq yang sudah ada. TIDAK dengar audio: baris " +
                        "suara di prompt hanya perkiraan."
                },
                fontSize = 12.sp, color = Color(0xFF9CA3AF)
            )
        }
        item {
            Button(
                onClick = {
                    copied = false
                    viewModel.requestGeneratePrompt()
                },
                enabled = !viewModel.promptLoading
            ) { Text("Buat Prompt") }
            if (viewModel.promptLoading) {
                CircularProgressIndicator()
                if (viewModel.promptProgress.isNotBlank()) {
                    Text(
                        viewModel.promptProgress,
                        fontSize = 13.sp, color = Color(0xFF9CA3AF)
                    )
                }
            }
            if (viewModel.promptError != null) {
                Text(
                    viewModel.promptError ?: "",
                    fontSize = 13.sp, color = Color(0xFFF87171)
                )
                val keyMissing = (viewModel.promptError ?: "").contains(
                    "belum diisi", ignoreCase = true
                ) || (viewModel.promptError ?: "").contains(
                    "Pengaturan", ignoreCase = true
                )
                if (keyMissing) {
                    OutlinedButton(onClick = onOpenSettings) {
                        Text("Buka Pengaturan")
                    }
                }
            }
        }
        val result = viewModel.promptResult
        if (result != null) {
            item {
                Text("Target di Flow:", fontSize = 14.sp, color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (viewModel.promptTarget == PromptTarget.OMNI_10S) {
                        Button(onClick = { viewModel.updatePromptTarget(PromptTarget.OMNI_10S) }) {
                            Text("Omni · 10 dtk", fontSize = 13.sp)
                        }
                        OutlinedButton(onClick = { viewModel.updatePromptTarget(PromptTarget.VEO_8S) }) {
                            Text("Veo · 8 dtk", fontSize = 13.sp)
                        }
                    } else {
                        OutlinedButton(onClick = { viewModel.updatePromptTarget(PromptTarget.OMNI_10S) }) {
                            Text("Omni · 10 dtk", fontSize = 13.sp)
                        }
                        Button(onClick = { viewModel.updatePromptTarget(PromptTarget.VEO_8S) }) {
                            Text("Veo · 8 dtk", fontSize = 13.sp)
                        }
                    }
                }
                if (viewModel.promptTarget == PromptTarget.VEO_8S) {
                    Text(
                        "Versi 8 detik — 2 detik dipangkas. Jangan pakai " +
                            "Extend (sudah pensiun di Flow).",
                        fontSize = 12.sp, color = Color(0xFFFBBF24)
                    )
                }
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF111827))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            result.prompt,
                            fontSize = 14.sp, color = Color.White
                        )
                        Button(onClick = {
                            clipboard.setText(AnnotatedString(result.prompt))
                            copied = true
                        }) {
                            Text(if (copied) "Disalin ✓" else "Salin Prompt")
                        }
                        if (result.audioInferred) {
                            Text(
                                "⚠ Suara di prompt ini hanya diperkirakan " +
                                    "dari gambar (engine Groq tidak bisa " +
                                    "mendengar audio) — cek sebelum ditempel.",
                                fontSize = 12.sp, color = Color(0xFFFBBF24)
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    "Penjelasan (bukan prompt — jangan disalin):",
                    fontSize = 13.sp, color = Color.White
                )
                Text(
                    result.glossId,
                    fontSize = 13.sp, color = Color(0xFF9CA3AF)
                )
                Text(
                    "${PromptRenderer.wordCount(result.prompt)} kata.",
                    fontSize = 12.sp, color = Color(0xFF9CA3AF)
                )
            }
        }
    }
}
