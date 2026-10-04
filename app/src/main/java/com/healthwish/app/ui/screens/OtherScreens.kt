package com.healthwish.app.ui.screens

import android.app.DatePickerDialog
import android.widget.Toast
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.healthwish.app.backup.BackupWorker
import com.healthwish.app.backup.ExportHelper
import com.healthwish.app.data.DayRepository
import com.healthwish.app.model.DayLog
import com.healthwish.app.ai.AiClient
import com.healthwish.app.security.AiConfig
import com.healthwish.app.security.AiConfigManager
import com.healthwish.app.security.AppLockManager
import com.healthwish.app.ui.components.*
import com.healthwish.app.utils.PreferenceHelper
import kotlinx.coroutines.launch
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen() {
    var selectedProfile by remember { mutableStateOf("Tkku") }
    var logs by remember { mutableStateOf<List<DayLog>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var specificDateLog by remember { mutableStateOf<DayLog?>(null) }
    var selectedDateText by remember { mutableStateOf<String?>(null) }
    var shownCount by remember { mutableStateOf(30) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    fun loadData() {
        scope.launch {
            loading = true
            logs = DayRepository.loadRange(selectedProfile, days = shownCount)
            loading = false
        }
    }

    LaunchedEffect(selectedProfile, shownCount) {
        selectedDateText = null
        specificDateLog = null
        loadData()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionHeader(
            title = "Diet History 🗓️",
            subtitle = "Jump to any date or browse past logs"
        )

        // Profile switcher
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = selectedProfile == "Tkku",
                onClick = { selectedProfile = "Tkku" },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                colors = SegmentedButtonDefaults.colors(activeContainerColor = AccentPink, activeContentColor = Color.White)
            ) {
                Text("Tkku's History", fontWeight = FontWeight.Bold)
            }
            SegmentedButton(
                selected = selectedProfile == "Shumu",
                onClick = { selectedProfile = "Shumu" },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                colors = SegmentedButtonDefaults.colors(activeContainerColor = AccentPurple, activeContentColor = Color.White)
            ) {
                Text("Shumu's History", fontWeight = FontWeight.Bold)
            }
        }

        // Jump to Date Button
        OutlinedButton(
            onClick = {
                val cal = Calendar.getInstance()
                DatePickerDialog(ctx, { _, y, m, d ->
                    val chosen = String.format("%04d-%02d-%02d", y, m + 1, d)
                    selectedDateText = chosen
                    scope.launch {
                        loading = true
                        specificDateLog = DayRepository.loadDay(selectedProfile, chosen)
                        loading = false
                    }
                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Rounded.DateRange, null, tint = AccentPink)
            Spacer(Modifier.width(8.dp))
            Text(if (selectedDateText == null) "📅 Jump to Specific Date" else "Showing: $selectedDateText (Tap to change)")
        }

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentPink)
            }
        } else if (selectedDateText != null) {
            val log = specificDateLog
            if (log == null || (log.breakfast.isBlank() && log.lunch.isBlank() && log.dinner.isBlank() && log.wakeupTime.isBlank())) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No records found for $selectedDateText", color = TextMuted)
                }
            } else {
                ModernCard {
                    Text("Record for $selectedDateText", fontWeight = FontWeight.Bold, color = AccentPink, fontSize = 16.sp)
                    if (log.wakeupTime.isNotBlank() || log.sleepMood.isNotBlank()) {
                        Text("😴 Wake: ${log.wakeupTime} | Sleep: ${log.sleepTime} | Mood: ${log.sleepMood}", fontSize = 14.sp)
                    }
                    if (log.screenOnTime.isNotBlank()) Text("📱 Screen On Time: ${log.screenOnTime}", color = AccentPurple, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    if (log.breakfast.isNotBlank()) Text("🌅 Breakfast: ${log.breakfast}", fontSize = 13.sp)
                    if (log.lunch.isNotBlank()) Text("🌞 Lunch: ${log.lunch}", fontSize = 13.sp)
                    if (log.dinner.isNotBlank()) Text("🌙 Dinner: ${log.dinner}", fontSize = 13.sp)
                    if (log.snacks.isNotBlank()) Text("🍿 Snacks: ${log.snacks}", fontSize = 13.sp)
                    if (log.breakfastKcal + log.lunchKcal + log.dinnerKcal + log.snacksKcal > 0) {
                        Text(
                            "🔥 Total: ${log.breakfastKcal + log.lunchKcal + log.dinnerKcal + log.snacksKcal} kcal",
                            color = AccentPink, fontSize = 13.sp, fontWeight = FontWeight.Bold
                        )
                    }
                    Text("💧 Water: ${log.waterGlasses} glasses | Tasks: ${log.tasks.count { it.done }}/${log.tasks.size}", color = TextMuted, fontSize = 12.sp)
                    if (log.weightKg > 0) Text("⚖️ Weight: ${"%.1f".format(log.weightKg)} kg", color = AccentGreen, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    if (log.dayNote.isNotBlank()) Text("💌 Note: \"${log.dayNote}\"", color = Color(0xFFFFD6A5), fontSize = 13.sp)
                }
            }
        } else if (logs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No past history found yet for $selectedProfile.\nStart logging today!", color = TextMuted)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 90.dp)
            ) {
                items(logs, key = { it.id.ifBlank { "${it.dateKey}_${it.ownerName}" } }) { log ->
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
                        if (log.wakeupTime.isNotBlank()) Text("😴 Wake: ${log.wakeupTime} | Sleep: ${log.sleepTime}", fontSize = 13.sp)
                        if (log.screenOnTime.isNotBlank()) Text("📱 Screen Time: ${log.screenOnTime}", color = AccentPurple, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        if (log.breakfast.isNotBlank()) Text("🌅 Breakfast: ${log.breakfast}", fontSize = 13.sp)
                        if (log.lunch.isNotBlank()) Text("🌞 Lunch: ${log.lunch}", fontSize = 13.sp)
                        if (log.dinner.isNotBlank()) Text("🌙 Dinner: ${log.dinner}", fontSize = 13.sp)
                        if (log.snacks.isNotBlank()) Text("🍿 Snacks: ${log.snacks}", fontSize = 13.sp)
                        if (log.breakfastKcal + log.lunchKcal + log.dinnerKcal + log.snacksKcal > 0) {
                            Text(
                                "🔥 Total: ${log.breakfastKcal + log.lunchKcal + log.dinnerKcal + log.snacksKcal} kcal",
                                color = AccentPink, fontSize = 13.sp, fontWeight = FontWeight.Bold
                            )
                        }
                        Text("💧 Water: ${log.waterGlasses} glasses | Tasks done: ${log.tasks.count { it.done }}/${log.tasks.size}", color = TextMuted, fontSize = 12.sp)
                        if (log.dayNote.isNotBlank()) {
                            Text("Note: \"${log.dayNote}\"", color = Color(0xFFFFD6A5), fontSize = 13.sp)
                        }
                        if (log.weightKg > 0) {
                            Text("⚖️ Weight: ${"%.1f".format(log.weightKg)} kg", color = AccentGreen, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                item(key = "load_more") {
                    OutlinedButton(
                        onClick = { shownCount += 30 },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Load older days ⬇️ ($shownCount shown)")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var isLockEnabled by remember { mutableStateOf(AppLockManager.isEnabled(ctx)) }
    var isFingerprintOn by remember { mutableStateOf(AppLockManager.isBiometricEnabled(ctx)) }
    var pinText by remember { mutableStateOf("") }
    var backupPass by remember { mutableStateOf("") }
    var exporting by remember { mutableStateOf(false) }
    // AI Setup state
    val aiPresets = remember { AiConfigManager.PRESETS.keys.toList() }
    var aiPreset by remember { mutableStateOf("Gemini (Google AI Studio)") }
    var aiBase by remember { mutableStateOf(AiConfigManager.PRESETS["Gemini (Google AI Studio)"]!!.first) }
    var aiKey by remember { mutableStateOf("") }
    var aiModel by remember { mutableStateOf(AiConfigManager.PRESETS["Gemini (Google AI Studio)"]!!.second) }
    var aiDrop by remember { mutableStateOf(false) }
    var aiTesting by remember { mutableStateOf(false) }
    var aiStatus by remember { mutableStateOf<String?>(null) }
    var calTargetText by remember { mutableStateOf(PreferenceHelper.getCalorieTarget(ctx).toString()) }
    LaunchedEffect(Unit) {
        val saved = AiConfigManager.get(ctx)
        if (saved.baseUrl.isNotBlank()) aiBase = saved.baseUrl
        if (saved.model.isNotBlank()) aiModel = saved.model
        if (saved.apiKey.isNotBlank()) aiKey = "••••••••"
        val match = AiConfigManager.PRESETS.entries.find { it.value.first == saved.baseUrl }?.key
        if (match != null) aiPreset = match
        aiStatus = if (AiConfigManager.isConfigured(ctx)) "AI connected ✓ (${aiModel.ifBlank { saved.model }})" else null
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 90.dp)
    ) {
        // Export Section in Settings
        item {
            SectionHeader(
                title = "Data Export 📊",
                subtitle = "Export diet history for analysis"
            )
            ModernCard {
                Text("Export Complete History (CSV)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("Generate a CSV file of Tkku and Shumu's complete records to open in Excel or Google Sheets.", color = TextMuted, fontSize = 13.sp)
                Button(
                    onClick = {
                        scope.launch {
                            exporting = true
                            try {
                                val tkkuData = DayRepository.loadRange("Tkku", 365)
                                val shumuData = DayRepository.loadRange("Shumu", 365)
                                val combined = (tkkuData + shumuData).sortedByDescending { it.dateKey }
                                if (combined.isEmpty()) {
                                    Toast.makeText(ctx, "No data to export yet!", Toast.LENGTH_SHORT).show()
                                } else {
                                    val f = ExportHelper.exportCsv(ctx, combined)
                                    ExportHelper.share(ctx, f)
                                }
                            } catch (e: Exception) {
                                Toast.makeText(ctx, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                            } finally {
                                exporting = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPink),
                    enabled = !exporting
                ) {
                    if (exporting) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                    } else {
                        Icon(Icons.Rounded.Share, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Export & Share CSV", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // AI Setup (custom key + model) + calorie target
        item(key = "ai_setup") {
            SectionHeader(
                title = "AI Assistant ✨",
                subtitle = "Apni API key + model — calorie auto-count"
            )
            ModernCard {
                if (aiStatus != null) {
                    Text(aiStatus!!, color = AccentGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Text(
                    "Key sirf is phone me rehti hai, kahin upload nahi hoti. Gemini key: aistudio.google.com se free me lo.",
                    color = TextMuted,
                    fontSize = 12.sp
                )
                // Provider preset dropdown
                ExposedDropdownMenuBox(
                    expanded = aiDrop,
                    onExpandedChange = { aiDrop = !aiDrop }
                ) {
                    OutlinedTextField(
                        value = aiPreset,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Provider") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(aiDrop) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = aiDrop,
                        onDismissRequest = { aiDrop = false }
                    ) {
                        aiPresets.forEach { name ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    aiPreset = name
                                    val p = AiConfigManager.PRESETS[name]!!
                                    if (p.first.isNotBlank()) aiBase = p.first
                                    if (p.second.isNotBlank()) aiModel = p.second
                                    aiDrop = false
                                }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = aiBase,
                    onValueChange = { aiBase = it },
                    label = { Text("Base URL (OpenAI-compatible)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
                OutlinedTextField(
                    value = aiKey,
                    onValueChange = { aiKey = it },
                    label = { Text("API Key (paste karo)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                )
                OutlinedTextField(
                    value = aiModel,
                    onValueChange = { aiModel = it },
                    label = { Text("Model (e.g. gemini-2.0-flash)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (aiBase.isBlank() || aiKey.isBlank() || aiModel.isBlank()) {
                                Toast.makeText(ctx, " teeno fields bharo 🙂", Toast.LENGTH_SHORT).show()
                            } else {
                                // "••••" dikhe to purani saved key reuse karo
                                val keyToSave = if (aiKey.all { it == '•' }) {
                                    AiConfigManager.get(ctx).apiKey
                                } else {
                                    aiKey.trim()
                                }
                                AiConfigManager.save(ctx, AiConfig(aiBase.trim(), keyToSave, aiModel.trim()))
                                aiStatus = "AI saved ✓ — Test dabake check karo"
                                Toast.makeText(ctx, "AI Setup saved 🔑", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                    ) {
                        Text("Save", color = Color(0xFF101018), fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            val cfg = AiConfigManager.get(ctx)
                            if (!AiConfigManager.isConfigured(ctx)) {
                                Toast.makeText(ctx, "Pehle Save karo 🙂", Toast.LENGTH_SHORT).show()
                            } else {
                                aiTesting = true
                                aiStatus = "Testing..."
                                scope.launch {
                                    try {
                                        val res = AiClient.test(cfg)
                                        aiStatus = "AI connected ✓ ($res)"
                                    } catch (e: Exception) {
                                        aiStatus = "Failed: ${(e.message ?: "error").take(120)}"
                                    } finally {
                                        aiTesting = false
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        enabled = !aiTesting
                    ) {
                        Text(if (aiTesting) "..." else "Test 🔌")
                    }
                }
                OutlinedButton(
                    onClick = {
                        AiConfigManager.clear(ctx)
                        aiKey = ""
                        aiStatus = null
                        Toast.makeText(ctx, "AI Removed — manual mode", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Remove AI Key")
                }
                OutlinedTextField(
                    value = calTargetText,
                    onValueChange = { calTargetText = it.filter(Char::isDigit).take(5) },
                    label = { Text("Daily calorie target (kcal)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
                Button(
                    onClick = {
                        val t = calTargetText.toIntOrNull()
                        if (t != null && t in 500..10000) {
                            PreferenceHelper.setCalorieTarget(ctx, t)
                            Toast.makeText(ctx, "Target: $t kcal 🎯", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(ctx, "500-10000 ke beech likho", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPurple)
                ) {
                    Text("Save Target")
                }
            }
        }

        // AI Prompt editor (test karke perfect karo)
        item(key = "ai_prompt") {
            var promptText by remember { mutableStateOf(AiConfigManager.getPrompt(ctx)) }
            var promptCustom by remember { mutableStateOf(AiConfigManager.isPromptCustomized(ctx)) }
            SectionHeader(
                title = "AI Prompt ✏️",
                subtitle = if (promptCustom) "Custom prompt active" else "Default rules active"
            )
            ModernCard {
                Text(
                    "Ye instructions har calorie request ke sath jati hain. Portion values badlo, test karo, best wali rakho.",
                    color = TextMuted,
                    fontSize = 12.sp
                )
                OutlinedTextField(
                    value = promptText,
                    onValueChange = { promptText = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    shape = RoundedCornerShape(12.dp),
                    minLines = 6
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (promptText.isBlank()) {
                                Toast.makeText(ctx, "Khaali mat chhodo 🙂", Toast.LENGTH_SHORT).show()
                            } else {
                                AiConfigManager.savePrompt(ctx, promptText)
                                promptCustom = true
                                Toast.makeText(ctx, "Prompt saved — ab test karo ✨", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPink)
                    ) {
                        Text("Save Prompt", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = {
                            AiConfigManager.resetPrompt(ctx)
                            promptText = AiConfigManager.DEFAULT_RULES
                            promptCustom = false
                            Toast.makeText(ctx, "Default wapas ✓", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Reset Default")
                    }
                }
            }
        }

        // App Lock
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text("App Lock Active", fontWeight = FontWeight.Bold)
                        Text("Require PIN on launch", color = TextMuted, fontSize = 12.sp)
                    }
                    Switch(
                        checked = isLockEnabled,
                        onCheckedChange = {
                            isLockEnabled = it
                            AppLockManager.setEnabled(ctx, it)
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Fingerprint Unlock", fontWeight = FontWeight.Bold)
                        Text(
                            if (AppLockManager.canUseBiometric(ctx)) "Ask fingerprint on launch"
                            else "Not available on this device",
                            color = TextMuted, fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = isFingerprintOn,
                        enabled = AppLockManager.canUseBiometric(ctx),
                        onCheckedChange = {
                            isFingerprintOn = it
                            AppLockManager.setBiometricEnabled(ctx, it)
                            Toast.makeText(
                                ctx,
                                if (it) "Fingerprint ON" else "Fingerprint OFF — only PIN",
                                Toast.LENGTH_SHORT
                            ).show()
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

        // Auto Backup
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

        // Logout
        item {
            ModernCard {
                Button(
                    onClick = onLogout,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                ) {
                    Icon(Icons.Rounded.ExitToApp, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Logout", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
