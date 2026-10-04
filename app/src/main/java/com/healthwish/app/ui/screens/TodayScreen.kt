package com.healthwish.app.ui.screens

import android.app.TimePickerDialog
import android.widget.Toast
import androidx.compose.animation.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.healthwish.app.ai.AiMealResult
import com.healthwish.app.ai.AiRouter
import com.healthwish.app.data.DayRepository
import com.healthwish.app.model.DayLog
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

    // Month data → last weight lookup (bounded query, cheap)
    val monthTkku by vm.monthTkku.collectAsState()
    val monthShumu by vm.monthShumu.collectAsState()
    val activeMonth = if (selectedProfile == "Tkku") monthTkku else monthShumu
    // Sabse recent logged weight (aaj ya pichhle dino se)
    val lastWeight = remember(activeMonth, activeLog) {
        (activeMonth + activeLog).filter { it.weightKg > 0 }.maxByOrNull { it.dateKey }
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
    var editingMealKcal by remember { mutableStateOf("") }

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
                    } else {
                        Text(
                            if (actualLastNightSleep.isBlank()) "Kal raat ka sleep time log karo ⏳"
                            else "Aaj ka wake-up time dalo ⏳",
                            color = TextMuted,
                            fontSize = 12.sp
                        )
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

        // 5. SYSTEMATIC STEP 3: Breakfast, Lunch, Dinner & Snacks (+ calories)
        item(key = "food_card") {
            val totalKcal = activeLog.breakfastKcal + activeLog.lunchKcal +
                activeLog.dinnerKcal + activeLog.snacksKcal
            val kcalTarget = remember { PreferenceHelper.getCalorieTarget(ctx) }
            val aiOn = remember { AiRouter.isReady(ctx) }
            var showAiBox by remember { mutableStateOf(false) }
            var aiInput by remember { mutableStateOf("") }
            var aiBusy by remember { mutableStateOf(false) }
            var aiPreview by remember { mutableStateOf<AiMealResult?>(null) }
            var aiError by remember { mutableStateOf<String?>(null) }

            SectionHeader(
                title = "3. Food & Diet Log 🍱",
                subtitle = "Tap any plate to add or update"
            )
            ModernCard {
                // Daily calorie total
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "🔥 $totalKcal / $kcalTarget kcal",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = if (totalKcal > kcalTarget) AccentPink else AccentGreen
                    )
                    Text(
                        if (aiOn) "✨ AI on" else "AI off",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
                LinearProgressIndicator(
                    progress = { (totalKcal / kcalTarget.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = if (totalKcal > kcalTarget) AccentPink else AccentGreen,
                    trackColor = Color(0xFF262638)
                )
                MealItem(
                    emoji = "🌅",
                    title = "Breakfast",
                    value = activeLog.breakfast,
                    isEditable = true,
                    kcal = activeLog.breakfastKcal,
                    onEdit = {
                        editingMealType = "breakfast"
                        editingMealValue = activeLog.breakfast
                        editingMealKcal = if (activeLog.breakfastKcal > 0) activeLog.breakfastKcal.toString() else ""
                    }
                )
                MealItem(
                    emoji = "🌞",
                    title = "Lunch",
                    value = activeLog.lunch,
                    isEditable = true,
                    kcal = activeLog.lunchKcal,
                    onEdit = {
                        editingMealType = "lunch"
                        editingMealValue = activeLog.lunch
                        editingMealKcal = if (activeLog.lunchKcal > 0) activeLog.lunchKcal.toString() else ""
                    }
                )
                MealItem(
                    emoji = "🌙",
                    title = "Dinner",
                    value = activeLog.dinner,
                    isEditable = true,
                    kcal = activeLog.dinnerKcal,
                    onEdit = {
                        editingMealType = "dinner"
                        editingMealValue = activeLog.dinner
                        editingMealKcal = if (activeLog.dinnerKcal > 0) activeLog.dinnerKcal.toString() else ""
                    }
                )
                MealItem(
                    emoji = "🍿",
                    title = "Snacks",
                    value = activeLog.snacks,
                    isEditable = true,
                    kcal = activeLog.snacksKcal,
                    onEdit = {
                        editingMealType = "snacks"
                        editingMealValue = activeLog.snacks
                        editingMealKcal = if (activeLog.snacksKcal > 0) activeLog.snacksKcal.toString() else ""
                    }
                )

                // AI free-text entry
                if (!showAiBox) {
                    OutlinedButton(
                        onClick = { showAiBox = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = AccentPink)
                        Spacer(Modifier.width(6.dp))
                        Text(if (aiOn) "✨ AI se likho (e.g. dopahar ko 2 roti dal)" else "✨ AI se likho (setup Settings me)")
                    }
                } else {
                    OutlinedTextField(
                        value = aiInput,
                        onValueChange = { aiInput = it },
                        label = { Text("Kya khaya, kab khaya?") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        minLines = 2
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (aiInput.isBlank()) {
                                    Toast.makeText(ctx, "Pehle kuch likho 🙂", Toast.LENGTH_SHORT).show()
                                } else if (!AiRouter.isReady(ctx)) {
                                    Toast.makeText(ctx, "Settings > AI me setup karo (Key ya Google Login) 🔑", Toast.LENGTH_LONG).show()
                                } else {
                                    aiBusy = true
                                    aiPreview = null
                                    aiError = null
                                    vm.parseFoodInput(ctx, aiInput.trim()) { res, err ->
                                        aiBusy = false
                                        if (res == null) {
                                            aiError = err ?: "AI fail"
                                        } else {
                                            aiPreview = res
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentPink),
                            enabled = !aiBusy
                        ) {
                            Text(if (aiBusy) "Soch raha hai..." else "Analyze ✨", fontWeight = FontWeight.Bold)
                        }
                        IconButton(onClick = { showAiBox = false; aiPreview = null; aiError = null }) {
                            Icon(Icons.Default.Close, null)
                        }
                    }
                    val errText = aiError
                    if (errText != null) {
                        Text(
                            "⚠️ $errText",
                            color = AccentPink,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    val preview = aiPreview
                    if (preview != null) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = AccentGreen.copy(alpha = 0.12f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, AccentGreen.copy(alpha = 0.4f))
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    "${preview.meal.replaceFirstChar { it.uppercase() }} → ${preview.text}",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    "🔥 ${preview.calories} kcal",
                                    color = AccentGreen,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            vm.setMealWithKcal(selectedProfile, preview.meal, preview.text, preview.calories)
                                            aiInput = ""
                                            aiPreview = null
                                            showAiBox = false
                                            Toast.makeText(ctx, "Saved ✨", Toast.LENGTH_SHORT).show()
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                                    ) {
                                        Text("Save", color = Color(0xFF101018), fontWeight = FontWeight.Bold)
                                    }
                                    OutlinedButton(
                                        onClick = { aiPreview = null },
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text("Discard")
                                    }
                                }
                            }
                        }
                    }
                }
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

        // 6b. Weight (15 din me ek baar update kaafi)
        item(key = "weight_row") {
            var showWeightDlg by remember { mutableStateOf(false) }
            ModernCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⚖️", fontSize = 22.sp)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Weight", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(
                            if (lastWeight != null) {
                                "${"%.1f".format(lastWeight.weightKg)} kg • ${lastWeight.dateKey}"
                            } else {
                                "Not logged yet"
                            },
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                    }
                    TextButton(onClick = { showWeightDlg = true }) {
                        Text(
                            if (lastWeight != null) "Update" else "Add",
                            fontWeight = FontWeight.Bold,
                            color = AccentGreen
                        )
                    }
                }
                Text(
                    "15 din me ek baar update kaafi hai 🙂",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }
            if (showWeightDlg) {
                var weightInput by remember(lastWeight?.weightKg) {
                    mutableStateOf(
                        if ((lastWeight?.weightKg ?: 0f) > 0) "%.1f".format(lastWeight!!.weightKg) else ""
                    )
                }
                AlertDialog(
                    onDismissRequest = { showWeightDlg = false },
                    title = { Text("Weight update karo ⚖️", fontWeight = FontWeight.Bold) },
                    text = {
                        OutlinedTextField(
                            value = weightInput,
                            onValueChange = { weightInput = it.filter { c -> c.isDigit() || c == '.' } },
                            label = { Text("Weight in kg (e.g. 62.5)") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                            )
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val kg = weightInput.toFloatOrNull()
                                if (kg != null && kg in 20f..300f) {
                                    vm.setWeight(selectedProfile, kg)
                                    showWeightDlg = false
                                    Toast.makeText(ctx, "Weight saved: ${"%.1f".format(kg)} kg 💜", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(ctx, "Sahi weight likho (20-300 kg)", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                        ) {
                            Text("Save", color = Color(0xFF101018), fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showWeightDlg = false }) { Text("Cancel") }
                    },
                    containerColor = Color(0xFF161622)
                )
            }
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
                OutlinedTextField(
                    value = editingMealKcal,
                    onValueChange = { editingMealKcal = it.filter(Char::isDigit).take(5) },
                    label = { Text("Calories (kcal) — khaali = ✨ AI bharega") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    )
                )
                Button(
                    onClick = {
                        val text = editingMealValue.trim()
                        val kcal = editingMealKcal.toIntOrNull() ?: 0
                        if (kcal > 0) {
                            vm.setMealWithKcal(selectedProfile, type, text, kcal)
                        } else {
                            vm.setMeal(selectedProfile, type, text)
                            // Manual entry par AI auto-calorie (agar AI setup hai)
                            vm.estimateKcal(ctx, selectedProfile, type, text)
                        }
                        editingMealType = null
                        editingMealKcal = ""
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
