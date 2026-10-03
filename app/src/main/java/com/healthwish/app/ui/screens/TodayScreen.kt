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
import com.healthwish.app.model.HabitTask
import com.healthwish.app.ui.components.*
import com.healthwish.app.viewmodel.DayViewModel
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(vm: DayViewModel) {
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Me, 1 = GF
    val meLog by vm.me.collectAsState()
    val gfLog by vm.gf.collectAsState()
    val activeLog = if (selectedTab == 0) meLog else (gfLog ?: DayLog(ownerName = "GF", dateKey = meLog.dateKey))
    val isEditable = selectedTab == 0

    var editingMealType by remember { mutableStateOf<String?>(null) }
    var editingMealValue by remember { mutableStateOf("") }
    val ctx = LocalContext.current

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 90.dp)
    ) {
        // Switch between Me & GF
        item {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                SegmentedButton(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = AccentPink,
                        activeContentColor = Color.White
                    )
                ) {
                    Text("🧑 My Diet", fontWeight = FontWeight.Bold)
                }
                SegmentedButton(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = AccentPurple,
                        activeContentColor = Color.White
                    )
                ) {
                    Text("👩 GF's Diet", fontWeight = FontWeight.Bold)
                }
            }
        }

        // Food timeline
        item {
            SectionHeader(
                title = if (isEditable) "Today's Plates 🍱" else "GF's Plates 🍱",
                subtitle = "Breakfast, Lunch, Dinner & Snacks"
            )
            ModernCard {
                MealItem(
                    emoji = "🌅",
                    title = "Breakfast",
                    value = activeLog.breakfast,
                    isEditable = isEditable,
                    onEdit = {
                        editingMealType = "breakfast"
                        editingMealValue = activeLog.breakfast
                    }
                )
                MealItem(
                    emoji = "🌞",
                    title = "Lunch",
                    value = activeLog.lunch,
                    isEditable = isEditable,
                    onEdit = {
                        editingMealType = "lunch"
                        editingMealValue = activeLog.lunch
                    }
                )
                MealItem(
                    emoji = "🌙",
                    title = "Dinner",
                    value = activeLog.dinner,
                    isEditable = isEditable,
                    onEdit = {
                        editingMealType = "dinner"
                        editingMealValue = activeLog.dinner
                    }
                )
                MealItem(
                    emoji = "🍿",
                    title = "Snacks",
                    value = activeLog.snacks,
                    isEditable = isEditable,
                    onEdit = {
                        editingMealType = "snacks"
                        editingMealValue = activeLog.snacks
                    }
                )
            }
        }

        // Water
        item {
            WaterTrackerRow(
                glasses = activeLog.waterGlasses,
                isEditable = isEditable,
                onUpdate = { vm.setWater(it) }
            )
        }

        // Daily Rituals & Custom Tasks
        item {
            val tasks = activeLog.tasks
            val completedCount = tasks.count { it.done }
            val progress = if (tasks.isEmpty()) 0f else completedCount / tasks.size.toFloat()
            var showAddCustom by remember { mutableStateOf(false) }
            var customTaskTitle by remember { mutableStateOf("") }

            SectionHeader(
                title = "Daily Rituals ✅",
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
                                onCheckedChange = { if (isEditable) vm.toggleTask(task) },
                                enabled = isEditable,
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

                if (isEditable) {
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
                                        vm.addCustomTask(customTaskTitle.trim())
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
        }

        // Sleep & Wake
        item {
            SectionHeader(
                title = "Sleep & Wake 😴",
                subtitle = "Log wake-up, sleep time and quality"
            )
            ModernCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (isEditable) {
                                val cal = Calendar.getInstance()
                                TimePickerDialog(ctx, { _, h, m ->
                                    val formatted = String.format("%02d:%02d %s", if (h % 12 == 0) 12 else h % 12, m, if (h >= 12) "PM" else "AM")
                                    vm.setSleep(formatted, activeLog.sleepTime, activeLog.sleepMood)
                                }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        enabled = isEditable
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("⏰ Wake-up", fontSize = 12.sp, color = TextMuted)
                            Text(activeLog.wakeupTime.ifBlank { "--:--" }, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            if (isEditable) {
                                val cal = Calendar.getInstance()
                                TimePickerDialog(ctx, { _, h, m ->
                                    val formatted = String.format("%02d:%02d %s", if (h % 12 == 0) 12 else h % 12, m, if (h >= 12) "PM" else "AM")
                                    vm.setSleep(activeLog.wakeupTime, formatted, activeLog.sleepMood)
                                }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        enabled = isEditable
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("😴 Sleep Time", fontSize = 12.sp, color = TextMuted)
                            Text(activeLog.sleepTime.ifBlank { "--:--" }, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }

                Text("Neend kaisi thi?", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
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
                                .clickable(enabled = isEditable) {
                                    vm.setSleep(activeLog.wakeupTime, activeLog.sleepTime, mood)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(mood, fontSize = 22.sp)
                        }
                    }
                }
            }
        }

        // Day Summary & Mood
        item {
            var noteText by remember(activeLog.dayNote) { mutableStateOf(activeLog.dayNote) }
            SectionHeader(
                title = "Day Completion ✨",
                subtitle = "Day kaisa raha? Share with your partner"
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
                                .clickable(enabled = isEditable) {
                                    vm.setDayMood(mood, noteText)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(mood, fontSize = 22.sp)
                        }
                    }
                }

                if (isEditable) {
                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        label = { Text("One-line day note for each other...") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Button(
                        onClick = {
                            vm.setDayMood(activeLog.dayMood, noteText)
                            Toast.makeText(ctx, "Day Note Saved 💜", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPurple)
                    ) {
                        Text("Save Day Note", fontWeight = FontWeight.Bold)
                    }
                } else if (activeLog.dayNote.isNotBlank()) {
                    Text("💌 \"${activeLog.dayNote}\"", fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = Color.White)
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
                    "Log ${type.replaceFirstChar { it.uppercase() }} 🍱",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                OutlinedTextField(
                    value = editingMealValue,
                    onValueChange = { editingMealValue = it },
                    label = { Text("Kya khaya? (e.g., 2 Roti, Paneer, Salad)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    minLines = 3
                )
                Button(
                    onClick = {
                        vm.setMeal(type, editingMealValue.trim())
                        editingMealType = null
                        Toast.makeText(ctx, "$type saved & synced!", Toast.LENGTH_SHORT).show()
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
