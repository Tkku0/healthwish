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
import com.healthwish.app.model.DayLog
import com.healthwish.app.ui.components.*
import com.healthwish.app.viewmodel.DayViewModel
import java.util.Calendar

// Helper function to calculate duration between sleep time (last night) and wakeup time (morning)
fun calculateSleepHours(sleepStr: String, wakeStr: String): String? {
    if (sleepStr.isBlank() || wakeStr.isBlank()) return null
    return try {
        fun toMinutes(s: String): Int {
            // formats: "11:30 PM", "07:00 AM", "23:30", "7:00"
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
    var selectedProfile by remember { mutableStateOf("Tkku") }
    val tkkuLog by vm.tkkuLog.collectAsState()
    val shumuLog by vm.shumuLog.collectAsState()
    val tkkuYday by vm.tkkuYesterday.collectAsState()
    val shumuYday by vm.shumuYesterday.collectAsState()

    val activeLog = if (selectedProfile == "Tkku") tkkuLog else shumuLog
    val ydayLog = if (selectedProfile == "Tkku") tkkuYday else shumuYday

    // Calculate sleep duration using yesterday's sleep time if available, or today's sleep time
    val lastNightSleepTime = ydayLog?.sleepTime?.ifBlank { activeLog.sleepTime } ?: activeLog.sleepTime
    val calculatedSleepDuration = calculateSleepHours(lastNightSleepTime, activeLog.wakeupTime)

    var editingMealType by remember { mutableStateOf<String?>(null) }
    var editingMealValue by remember { mutableStateOf("") }
    val ctx = LocalContext.current

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 90.dp)
    ) {
        // 1. Tkku & Shumu Selector
        item {
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
                    Text("🌸 Tkku", fontWeight = FontWeight.Bold, fontSize = 15.sp)
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
                    Text("💙 Shumu", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }

        // 2. SYSTEMATIC STEP 1: Morning Wake-up & Sleep Quality (With live calculated sleep hours!)
        item {
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

                // Header with calculated sleep hours directly displayed!
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

        // 3. SYSTEMATIC STEP 2: Morning Walk, Exercise & Rituals
        item {
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

        // 4. SYSTEMATIC STEP 3: Breakfast, Lunch, Dinner & Snacks
        item {
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

        // 5. Water Tracker
        item {
            WaterTrackerRow(
                glasses = activeLog.waterGlasses,
                isEditable = true,
                onUpdate = { vm.setWater(selectedProfile, it) }
            )
        }

        // 6. SYSTEMATIC STEP 4: Night Day Completion & Mood (Sabse aakhir me)
        item {
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
