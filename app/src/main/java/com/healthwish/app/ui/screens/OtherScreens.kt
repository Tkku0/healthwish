package com.healthwish.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.healthwish.app.backup.BackupWorker
import com.healthwish.app.backup.ExportHelper
import com.healthwish.app.data.DayRepository
import com.healthwish.app.model.DayLog
import com.healthwish.app.security.AppLockManager
import com.healthwish.app.ui.components.*
import kotlinx.coroutines.launch

@Composable
fun HistoryScreen(currentUid: String) {
    var logs by remember { mutableStateOf<List<DayLog>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(currentUid) {
        loading = true
        logs = DayRepository.loadRange(currentUid, days = 30)
        loading = false
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionHeader(
            title = "Past 30 Days History 🗓️",
            subtitle = "Everything you logged date by date"
        )

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentPink)
            }
        } else if (logs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No past history found yet.\nStart logging today!", color = TextMuted)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 90.dp)
            ) {
                items(logs) { log ->
                    ModernCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(log.dateKey, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = AccentPink)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (log.sleepMood.isNotBlank()) Text("Sleep: ${log.sleepMood}")
                                if (log.dayMood.isNotBlank()) Text("Day: ${log.dayMood}")
                            }
                        }
                        if (log.breakfast.isNotBlank()) Text("🌅 Breakfast: ${log.breakfast}", fontSize = 13.sp)
                        if (log.lunch.isNotBlank()) Text("🌞 Lunch: ${log.lunch}", fontSize = 13.sp)
                        if (log.dinner.isNotBlank()) Text("🌙 Dinner: ${log.dinner}", fontSize = 13.sp)
                        if (log.snacks.isNotBlank()) Text("🍿 Snacks: ${log.snacks}", fontSize = 13.sp)
                        Text("💧 Water: ${log.waterGlasses} glasses | Rituals done: ${log.tasks.count { it.done }}/${log.tasks.size}", color = TextMuted, fontSize = 12.sp)
                        if (log.dayNote.isNotBlank()) {
                            Text("Note: \"${log.dayNote}\"", color = Color(0xFFFFD6A5), fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ExportScreen(currentUid: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionHeader(
            title = "Export Diet Data 📊",
            subtitle = "Export your complete food & sleep decisions for analysis"
        )

        ModernCard {
            Text("Complete Diet History Summary", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(
                "Export your past logs to a clean CSV file. You can open it in Microsoft Excel, Google Sheets, or share it anywhere via WhatsApp or Email.",
                color = TextMuted,
                fontSize = 13.sp
            )

            Button(
                onClick = {
                    scope.launch {
                        exporting = true
                        try {
                            val data = DayRepository.loadRange(currentUid, days = 365)
                            if (data.isEmpty()) {
                                Toast.makeText(ctx, "No data to export yet!", Toast.LENGTH_SHORT).show()
                            } else {
                                val file = ExportHelper.exportCsv(ctx, data)
                                ExportHelper.share(ctx, file)
                            }
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        } finally {
                            exporting = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentPink),
                enabled = !exporting
            ) {
                if (exporting) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(22.dp))
                } else {
                    Icon(Icons.Rounded.Share, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Export & Share CSV File", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(currentUid: String, onLogout: () -> Unit) {
    val ctx = LocalContext.current
    var isLockEnabled by remember { mutableStateOf(AppLockManager.isEnabled(ctx)) }
    var pinText by remember { mutableStateOf("") }
    var backupPass by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 90.dp)
    ) {
        item {
            SectionHeader(
                title = "Security & App Lock 🔒",
                subtitle = "Biometric & 4-digit PIN protection"
            )
            ModernCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("App Lock Active", fontWeight = FontWeight.Bold)
                        Text("Require Fingerprint/PIN on launch", color = TextMuted, fontSize = 12.sp)
                    }
                    Switch(
                        checked = isLockEnabled,
                        onCheckedChange = {
                            isLockEnabled = it
                            AppLockManager.setEnabled(ctx, it)
                        }
                    )
                }

                OutlinedTextField(
                    value = pinText,
                    onValueChange = { if (it.length <= 4) pinText = it.filter(Char::isDigit) },
                    label = { Text("Set 4-Digit Security PIN") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Button(
                    onClick = {
                        if (pinText.length == 4) {
                            AppLockManager.savePin(ctx, pinText)
                            isLockEnabled = true
                            Toast.makeText(ctx, "PIN saved & Lock Enabled! 🔒", Toast.LENGTH_SHORT).show()
                            pinText = ""
                        } else {
                            Toast.makeText(ctx, "Enter 4 digits", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPurple)
                ) {
                    Text("Save PIN")
                }
            }
        }

        item {
            SectionHeader(
                title = "Encrypted Auto Backup 📦",
                subtitle = "AES-256 encrypted nightly backups"
            )
            ModernCard {
                Text(
                    "Backups are encrypted using AES-256 with your secret password. Nobody can open the backup file without this password.",
                    color = TextMuted,
                    fontSize = 13.sp
                )
                OutlinedTextField(
                    value = backupPass,
                    onValueChange = { backupPass = it },
                    label = { Text("Set Backup Secret Password") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Button(
                    onClick = {
                        if (backupPass.isNotBlank()) {
                            BackupWorker.schedule(ctx, "LOVE1", backupPass.trim())
                            Toast.makeText(ctx, "Nightly Encrypted Backup Scheduled! 📦", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                ) {
                    Text("Enable Auto Nightly Backup", color = Color(0xFF101018), fontWeight = FontWeight.Bold)
                }
            }
        }

        item {
            ModernCard {
                Button(
                    onClick = onLogout,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                ) {
                    Icon(Icons.Default.ExitToApp, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Logout", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
