package com.healthwish.app.ui.screens

import android.app.TimePickerDialog
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.healthwish.app.data.DayRepository
import com.healthwish.app.model.DayLog
import com.healthwish.app.model.ScoreHelper
import com.healthwish.app.ui.components.*
import com.healthwish.app.utils.PreferenceHelper
import com.healthwish.app.utils.ScreenTimeHelper
import com.healthwish.app.viewmodel.DayViewModel
import java.util.Calendar

// Helper function: Strictly requires previous night's sleep time!
fun calculateSleepHours(sleepStr: String, wakeStr: String): String? {
    if (sleepStr.isBlank() || wakeStr.isBlank()) return null
    return try {
        fun toMinutes(s: String): Int {
            val clean = s.trim().uppercase()
            val isPM = clean.contains("PM")
            val isAM = clean.contains("AM")
            val parts = clean.replace("AM", "").replace("PM", "").trim().split(":")
            var h = parts[0].toInt()
            val m = parts[1].toInt()
            if (isPM && h < 12) h += 12
            if (isAM && h == 12) h = 0
            return h * 60 + m
        }
        val sleepMin = toMinutes(sleepStr)
        val wakeMin = toMinutes(wakeStr)
        val diffMin = if (wakeMin >= sleepMin) {
            wakeMin - sleepMin
        } else {
            (1440 - sleepMin) + wakeMin
        }
        val hours = diffMin / 60
        val mins = diffMin % 60
        if (mins == 0) "$hours hrs" else "${hours}h ${mins}m"
    } catch (e: Exception) {
        null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(vm: DayViewModel) {
    val ctx = LocalContext.current
    var myDeviceProfile by remember { mutableStateOf(PreferenceHelper.getMyProfile(ctx)) }
    var selectedProfile by remember { mutableStateOf(myDeviceProfile ?: "Tkku") }

    val tkkuLog by vm.tkkuLog.collectAsState()
    val shumuLog by vm.shumuLog.collectAsState()
    val tkkuYday by vm.tkkuYesterday.collectAsState()
    val shumuYday by vm.shumuYesterday.collectAsState()

    val activeLog = if (selectedProfile == "Tkku") tkkuLog else shumuLog
    val ydayLog = if (selectedProfile == "Tkku") tkkuYday else shumuYday

    // Month data → battle scoreboard, streak, weekly summary, weight chart
    val monthTkku by vm.monthTkku.collectAsState()
    val monthShumu by vm.monthShumu.collectAsState()
    val todayKey = remember { DayRepository.todayKey() }
    val weekStart = remember { DayRepository.weekStartKey() }
    val monthName = remember(todayKey) {
        try {
            java.time.Month.of(todayKey.substring(5, 7).toInt()).name.lowercase()
                .replaceFirstChar { it.uppercase() }
        } catch (e: Exception) { "" }
    }
    val tkkuAvg = remember(monthTkku) {
        if (monthTkku.isEmpty()) 0 else monthTkku.map { ScoreHelper.dailyScore(it) }.average().toInt()
    }
    val shumuAvg = remember(monthShumu) {
        if (monthShumu.isEmpty()) 0 else monthShumu.map { ScoreHelper.dailyScore(it) }.average().toInt()
    }
    val activeMonth = if (selectedProfile == "Tkku") monthTkku else monthShumu
    val todayScore = remember(activeLog) { ScoreHelper.dailyScore(activeLog) }
    val streak = remember(activeMonth) {
        ScoreHelper.streakDays(activeMonth.associateBy { it.dateKey }, todayKey)
    }
    val weekly = remember(activeMonth, weekStart) {
        ScoreHelper.weekly(activeMonth.filter { it.dateKey >= weekStart })
    }
    val weightPts = remember(activeMonth) {
        activeMonth.filter { it.weightKg > 0 }.sortedBy { it.dateKey }.takeLast(30)
            .map { it.dateKey to it.weightKg }
    }

    // FIXED SLEEP LOGIC: Only calculate if yesterday actually has a sleep time logged!
    val actualLastNightSleep = ydayLog?.sleepTime ?: ""
    val calculatedSleepDuration = if (actualLastNightSleep.isNotBlank() && activeLog.wakeupTime.isNotBlank()) {
        calculateSleepHours(actualLastNightSleep, activeLog.wakeupTime)
    } else {
        null
    }

    // Auto sync screen time for the profile belonging to this phone (executed on IO coroutine)
    var hasUsagePermission by remember { mutableStateOf(ScreenTimeHelper.hasUsagePermission(ctx)) }
    LaunchedEffect(myDeviceProfile, hasUsagePermission) {
        val prof = myDeviceProfile ?: return@LaunchedEffect
        if (hasUsagePermission) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                // 1. Sync Today's Screen Time
                val todayMillis = ScreenTimeHelper.getScreenOnTimeMillis(ctx, 0)
                val formattedToday = ScreenTimeHelper.formatMillis(todayMillis)
                vm.syncScreenTime(prof, formattedToday)

                // 2. Auto backfill yesterday's final screen time if missing
                val ydayMillis = ScreenTimeHelper.getScreenOnTimeMillis(ctx, -1)
                val formattedYday = ScreenTimeHelper.formatMillis(ydayMillis)
                vm.backfillYesterdayScreenTime(prof, formattedYday)
            }
        }
    }

    var editingMealType by remember { mutableStateOf<String?>(null) }
    var editingMealValue by remember { mutableStateOf("") }

    // FIRST-TIME SETUP: "Who is using this phone?"
    if (myDeviceProfile == null) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color(0xFF0D0D12)).padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            ModernCard {
                Text("Welcome to HealthWish 💜", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Ye phone kiska hai? Select karo taaki auto screen time aur data sahi profile me sync ho sake:", color = TextMuted, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        PreferenceHelper.setMyProfile(ctx, "Tkku")
                        myDeviceProfile = "Tkku"
                        selectedProfile = "Tkku"
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPink)
                ) {
                    Text("🌸 Main Tkku hoon", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Button(
                    onClick = {
                        PreferenceHelper.setMyProfile(ctx, "Shumu")
                        myDeviceProfile = "Shumu"
                        selectedProfile = "Shumu"
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPurple)
                ) {
                    Text("💙 Main Shumu hoon", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 90.dp)
    ) {
        // 0. HEADER: Month Battle + today ring + streak
        item(key = "battle_header") {
            BattleHeaderCard(
                monthName = monthName,
                todayScore = todayScore,
                streak = streak,
                tkkuAvg = tkkuAvg,
                shumuAvg = shumuAvg,
                hasData = monthTkku.isNotEmpty() || monthShumu.isNotEmpty()
            )
        }

        // 1. Tkku & Shumu Selector
        item(key = "profile_selector") {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                SegmentedButton(
                    selected = selectedProfile == "Tkku",
                    onClick = { selectedProfile = "Tkku" },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = AccentPink,
                        activeContentColor = Color.White
                    )
                ) {
                    Text(
                        if (myDeviceProfile == "Tkku") "🌸 Tkku (Me)" else "🌸 Tkku",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
                SegmentedButton(
                    selected = selectedProfile == "Shumu",
                    onClick = { selectedProfile = "Shumu" },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = AccentPurple,
                        activeContentColor = Color.White
                    )
                ) {
                    Text(
                        if (myDeviceProfile == "Shumu") "💙 Shumu (Me)" else "💙 Shumu",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }

        // 2. SYSTEMATIC STEP 1: Morning Wake-up & Sleep Quality
        item(key = "sleep_card") {
            SectionHeader(
                title = "1. Sleep & Wake-up 😴",
                subtitle = "Subah uthne ka time aur neend ki quality"
            )
            ModernCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val cal = Calendar.getInstance()
                            TimePickerDialog(ctx, { _, h, m ->
                                val formatted = String.format("%02d:%02d %s", if (h % 12 == 0) 12 else h % 12, m, if (h >= 12) "PM" else "AM")
                                vm.setSleep(selectedProfile, formatted, activeLog.sleepTime, activeLog.sleepMood)
                            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("⏰ Wake-up Time", fontSize = 12.sp, color = TextMuted)
                            Text(activeLog.wakeupTime.ifBlank { "--:--" }, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            val cal = Calendar.getInstance()
                            TimePickerDialog(ctx, { _, h, m ->
                                val formatted = String.format("%02d:%02d %s", if (h % 12 == 0) 12 else h % 12, m, if (h >= 12) "PM" else "AM")
                                vm.setSleep(selectedProfile, activeLog.wakeupTime, formatted, activeLog.sleepMood)
                            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("🌙 Sleep Time", fontSize = 12.sp, color = TextMuted)
                            Text(activeLog.sleepTime.ifBlank { "--:--" }, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }

                // Neend kaisi thi + SLEPT HOURS (Only shown if last night recorded)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Neend kaisi thi?", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    if (calculatedSleepDuration != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = AccentPink.copy(alpha = 0.15f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, AccentPink.copy(alpha = 0.4f))
                        ) {
                            Text(
                                "💤 Slept: $calculatedSleepDuration",
                                color = AccentPink,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    DayLog.SLEEP_MOODS.forEach { mood ->
                        val isSelected = activeLog.sleepMood == mood
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) AccentPink.copy(alpha = 0.25f) else Color(0xFF1E1E2C))
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) AccentPink else CardBorder,
                                    shape = CircleShape
                                )
                                .clickable {
                                    vm.setSleep(selectedProfile, activeLog.wakeupTime, activeLog.sleepTime, mood)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(mood, fontSize = 22.sp)
                        }
                    }
                }
            }
        }

        // 3. AUTO SCREEN ON TIME CARD
        item(key = "screen_time_card") {
            SectionHeader(
                title = "📱 Screen On Time",
                subtitle = "Automatic daily phone usage tracking"
            )
            ModernCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(42.dp).clip(CircleShape).background(Color(0xFF262638)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Smartphone, null, tint = AccentPurple)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("$selectedProfile's Screen Time", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text(
                                if (activeLog.screenOnTime.isNotBlank()) activeLog.screenOnTime else "Syncing...",
                                fontWeight = FontWeight.Bold,
                                color = AccentPurple,
                                fontSize = 18.sp
                            )
                        }
                    }

                    if (selectedProfile == myDeviceProfile && !hasUsagePermission) {
                        Button(
                            onClick = {
                                ScreenTimeHelper.openUsageSettings(ctx)
                                hasUsagePermission = true
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentPink)
                        ) {
                            Text("Enable Access", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // 4. SYSTEMATIC STEP 2: Morning Walk, Exercise & Rituals
        item(key = "rituals_card") {
            val tasks = activeLog.tasks
            val completedCount = tasks.count { it.done }
            val progress = if (tasks.isEmpty()) 0f else completedCount / tasks.size.toFloat()
            var showAddCustom by remember { mutableStateOf(false) }
            var customTaskTitle by remember { mutableStateOf("") }

            SectionHeader(
                title = "2. Daily Rituals & Fitness 🚶",
                subtitle = "$completedCount of ${tasks.size} done"
            )
            ModernCard {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = AccentGreen,
                    trackColor = Color(0xFF262638)
                )

                tasks.forEach { task ->
                    key(task.id) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF1E1E2C))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Checkbox(
                                    checked = task.done,
                                    onCheckedChange = { vm.toggleTask(selectedProfile, task) },
                                    colors = CheckboxDefaults.colors(checkedColor = AccentGreen)
                                )
                                Spacer(Modifier.width(6.dp))
                                Column {
                                    Text(
                                        "${task.emoji}  ${task.title}",
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 15.sp,
                                        color = if (task.done) Color.White.copy(alpha = 0.6f) else Color.White
                                    )
                                    if (task.time.isNotBlank()) {
                                        Text(task.time, color = TextMuted, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                if (!showAddCustom) {
                    OutlinedButton(
                        onClick = { showAddCustom = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Add Custom Task (Only for Today)")
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = customTaskTitle,
                            onValueChange = { customTaskTitle = it },
                            label = { Text("Task name...") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        Button(
                            onClick = {
                                if (customTaskTitle.isNotBlank()) {
                                    vm.addCustomTask(selectedProfile, customTaskTitle.trim())
                                    customTaskTitle = ""
                                    showAddCustom = false
                                }
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Add")
                        }
                        IconButton(onClick = { showAddCustom = false }) {
                            Icon(Icons.Default.Close, null)
                        }
                    }
                }
            }
        }

        // 5. SYSTEMATIC STEP 3: Breakfast, Lunch, Dinner & Snacks
        item(key = "food_card") {
            SectionHeader(
                title = "3. Food & Diet Log 🍱",
                subtitle = "Tap any plate to add or update"
            )
            ModernCard {
                MealItem(
                    emoji = "🌅",
                    title = "Breakfast",
                    value = activeLog.breakfast,
                    isEditable = true,
                    onEdit = {
                        editingMealType = "breakfast"
                        editingMealValue = activeLog.breakfast
                    }
                )
                MealItem(
                    emoji = "🌞",
                    title = "Lunch",
                    value = activeLog.lunch,
                    isEditable = true,
                    onEdit = {
                        editingMealType = "lunch"
                        editingMealValue = activeLog.lunch
                    }
                )
                MealItem(
                    emoji = "🌙",
                    title = "Dinner",
                    value = activeLog.dinner,
                    isEditable = true,
                    onEdit = {
                        editingMealType = "dinner"
                        editingMealValue = activeLog.dinner
                    }
                )
                MealItem(
                    emoji = "🍿",
                    title = "Snacks",
                    value = activeLog.snacks,
                    isEditable = true,
                    onEdit = {
                        editingMealType = "snacks"
                        editingMealValue = activeLog.snacks
                    }
                )
            }
        }

        // 6. Water Tracker
        item(key = "water_card") {
            WaterTrackerRow(
                glasses = activeLog.waterGlasses,
                isEditable = true,
                onUpdate = { vm.setWaterFast(selectedProfile, it) }
            )
        }

        // 6b. Weight Tracker + chart
        item(key = "weight_card") {
            WeightCard(
                weight = activeLog.weightKg,
                history = weightPts,
                onSet = { vm.setWeight(selectedProfile, it) }
            )
        }

        // 7. SYSTEMATIC STEP 4: Night Day Completion & Mood (Sabse aakhir me)
        item(key = "reflection_card") {
            var noteText by remember(activeLog.dayNote) { mutableStateOf(activeLog.dayNote) }
            SectionHeader(
                title = "4. End of Day Reflection ✨",
                subtitle = "Day kaisa raha? Share with each other"
            )
            ModernCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    DayLog.DAY_MOODS.forEach { mood ->
                        val isSelected = activeLog.dayMood == mood
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) AccentPurple.copy(alpha = 0.25f) else Color(0xFF1E1E2C))
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) AccentPurple else CardBorder,
                                    shape = CircleShape
                                )
                                .clickable {
                                    vm.setDayMood(selectedProfile, mood, noteText)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(mood, fontSize = 22.sp)
                        }
                    }
                }

                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("One-line day summary for each other...") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Button(
                    onClick = {
                        vm.setDayMood(selectedProfile, activeLog.dayMood, noteText)
                        Toast.makeText(ctx, "Day Note Saved & Synced! 💜", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPurple)
                ) {
                    Text("Save Reflection", fontWeight = FontWeight.Bold)
                }
            }
        }

        // 8. Weekly summary + share
        item(key = "weekly_card") {
            WeeklyCard(
                profile = selectedProfile,
                weekly = weekly,
                onShare = {
                    val text = ScoreHelper.shareText(selectedProfile, weekly)
                    ctx.startActivity(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }.let { Intent.createChooser(it, "Share weekly summary 💜") }
                    )
                }
            )
        }
    }

    // Modal Sheet for adding/editing meals
    if (editingMealType != null) {
        val type = editingMealType!!
        ModalBottomSheet(
            onDismissRequest = { editingMealType = null },
            containerColor = Color(0xFF161622)
        ) {
            Column(
                modifier = Modifier.padding(24.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    "Log $selectedProfile's ${type.replaceFirstChar { it.uppercase() }} 🍱",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                OutlinedTextField(
                    value = editingMealValue,
                    onValueChange = { editingMealValue = it },
                    label = { Text("Kya khaya? (e.g., 2 Roti, Daal, Salad)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    minLines = 3
                )
                Button(
                    onClick = {
                        vm.setMeal(selectedProfile, type, editingMealValue.trim())
                        editingMealType = null
                        Toast.makeText(ctx, "$selectedProfile's $type saved & synced!", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPink)
                ) {
                    Text("Save & Sync 💜", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun BattleHeaderCard(
    monthName: String,
    todayScore: Int,
    streak: Int,
    tkkuAvg: Int,
    shumuAvg: Int,
    hasData: Boolean
) {
    val ring by animateFloatAsState(targetValue = todayScore / 100f, label = "ring")
    ModernCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier.size(76.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    progress = { ring },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 8.dp,
                    color = AccentPink,
                    trackColor = Color(0xFF262638)
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$todayScore", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("today", color = TextMuted, fontSize = 10.sp)
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (monthName.isNotBlank()) "$monthName Battle 🏆" else "Month Battle 🏆",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text("🔥 $streak", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = AccentPink)
                }
                if (!hasData) {
                    Text("Log karo, battle shuru hogi 💪", color = TextMuted, fontSize = 13.sp)
                } else {
                    ScoreBar(
                        name = "🌸 Tkku",
                        score = tkkuAvg,
                        color = AccentPink,
                        max = maxOf(tkkuAvg, shumuAvg, 1),
                        crown = tkkuAvg >= shumuAvg
                    )
                    ScoreBar(
                        name = "💙 Shumu",
                        score = shumuAvg,
                        color = AccentPurple,
                        max = maxOf(tkkuAvg, shumuAvg, 1),
                        crown = shumuAvg > tkkuAvg
                    )
                }
            }
        }
    }
}

@Composable
private fun ScoreBar(name: String, score: Int, color: Color, max: Int, crown: Boolean) {
    val p by animateFloatAsState(
        targetValue = if (max <= 0) 0f else (score / max.toFloat()).coerceIn(0f, 1f),
        label = "bar"
    )
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(
                if (crown) "$score 👑" else "$score",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = color
            )
        }
        LinearProgressIndicator(
            progress = { p },
            modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)),
            color = color,
            trackColor = Color(0xFF262638)
        )
    }
}

@Composable
private fun WeightCard(
    weight: Float,
    history: List<Pair<String, Float>>,
    onSet: (Float) -> Unit
) {
    SectionHeader(
        title = "Weight ⚖️",
        subtitle = if (weight > 0) "Today: ${"%.1f".format(weight)} kg" else "Aaj ka weight log karo"
    )
    ModernCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledIconButton(
                onClick = { onSet((if (weight <= 0) 60f else weight) - 0.5f) },
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF262638))
            ) {
                Icon(Icons.Default.Remove, null, tint = Color.White)
            }
            Text(
                if (weight > 0) "${"%.1f".format(weight)} kg" else "-- kg",
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                color = AccentGreen
            )
            FilledIconButton(
                onClick = { onSet((if (weight <= 0) 60f else weight) + 0.5f) },
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = AccentGreen)
            ) {
                Icon(Icons.Default.Add, null, tint = Color(0xFF101018))
            }
        }
        if (history.size >= 2) {
            WeightChart(history)
            Text(
                "Last ${history.size} entries • min ${"%.1f".format(history.minOf { it.second })} / max ${"%.1f".format(history.maxOf { it.second })} kg",
                color = TextMuted,
                fontSize = 12.sp
            )
        } else {
            Text("Roz weight log karo, yahan trend banega 📈", color = TextMuted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun WeightChart(pts: List<Pair<String, Float>>) {
    Canvas(
        modifier = Modifier.fillMaxWidth().height(110.dp)
    ) {
        val min = pts.minOf { it.second }
        val max = pts.maxOf { it.second }
        val span = (max - min).coerceAtLeast(1f)
        val pad = 12.dp.toPx()
        val w = size.width - pad * 2
        val h = size.height - pad * 2
        val stepX = if (pts.size > 1) w / (pts.size - 1) else 0f
        val path = Path()
        pts.forEachIndexed { i, (_, v) ->
            val x = pad + i * stepX
            val y = pad + h - ((v - min) / span) * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = AccentGreen, style = Stroke(width = 3.dp.toPx()))
        pts.forEachIndexed { i, (_, v) ->
            val x = pad + i * stepX
            val y = pad + h - ((v - min) / span) * h
            drawCircle(color = AccentGreen, radius = 4.dp.toPx(), center = androidx.compose.ui.geometry.Offset(x, y))
        }
    }
}

@Composable
private fun WeeklyCard(
    profile: String,
    weekly: ScoreHelper.WeeklySummary,
    onShare: () -> Unit
) {
    SectionHeader(
        title = "This Week 📊",
        subtitle = "$profile ka 7-day summary"
    )
    ModernCard {
        if (weekly.daysLogged == 0) {
            Text("Is hafte abhi kuch log nahi hua — aaj se shuru karo 💜", color = TextMuted, fontSize = 13.sp)
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                WeeklyStat("${weekly.daysLogged}/7", "days")
                WeeklyStat("${weekly.avgScore}", "avg score")
                WeeklyStat("%.1f".format(weekly.avgWater), "water/day")
                WeeklyStat("${weekly.tasksDone}/${weekly.tasksTotal}", "tasks")
            }
            Text("Best day: ${weekly.bestDay} ⭐", color = Color(0xFFFFD6A5), fontSize = 13.sp)
            OutlinedButton(
                onClick = onShare,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Rounded.Share, null, tint = AccentPink)
                Spacer(Modifier.width(6.dp))
                Text("Share with each other 💜")
            }
        }
    }
}

@Composable
private fun WeeklyStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
        Text(label, color = TextMuted, fontSize = 11.sp)
    }
}
