package com.healthwish.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.healthwish.app.model.DayLog
import com.healthwish.app.model.HabitTask

// Professional cozy UI: Me/GF tabs + Today timeline + Sleep + Mood
@Composable
fun HealthWishApp(onLogout: () -> Unit = {}) {
    var selectedTab by remember { mutableIntStateOf(0) } // 0=Me 1=GF
    var dark by remember { mutableStateOf(true) }
    var showAddSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("💜 HealthWish") },
                actions = {
                    IconButton(onClick = { dark = !dark }) {
                        Icon(if (dark) Icons.Filled.LightMode else Icons.Filled.DarkMode, null)
                    }
                    IconButton(onClick = onLogout) {
                        Icon(Icons.Filled.Logout, null)
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = true, onClick = {}, icon = { Icon(Icons.Filled.Today, null) }, label = { Text("Today") })
                NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.Filled.CalendarMonth, null) }, label = { Text("History") })
                NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.Filled.FileDownload, null) }, label = { Text("Export") })
                NavigationBarItem(selected = false, onClick = {}, icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("Settings") })
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { showAddSheet = true }) {
                Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text("Add Meal")
            }
        }
    ) { pad ->
        LazyColumn(modifier = Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = selectedTab == 0, onClick = { selectedTab = 0 }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("🧑 Me") }
                    SegmentedButton(selected = selectedTab == 1, onClick = { selectedTab = 1 }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("👩 GF") }
                }
            }
            item { TodayTimelineCard() }
            item { DailyChecklistCard() }
            item { SleepMoodCard() }
            item { DayCloseCard() }
        }
        if (showAddSheet) AddMealSheet(onDismiss = { showAddSheet = false })
    }
}

@Composable
fun TodayTimelineCard() {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Today's Plates 🍱", style = MaterialTheme.typography.titleMedium)
            MealRow("🌅", "Breakfast", "Poha + chai")
            MealRow("🌞", "Lunch", "— tap to add —")
            MealRow("🌙", "Dinner", "— tap to add —")
            MealRow("🍿", "Snacks", "Makhana")
            MealRow("💧", "Water", "5 / 8 glasses")
        }
    }
}

@Composable
fun MealRow(emoji: String, title: String, value: String) {
    ListItem(headlineContent = { Text("$emoji  $title") }, supportingContent = { Text(value) })
}

@Composable
fun DailyChecklistCard() {
    var tasks by remember { mutableStateOf(DayLog.defaultTasks()) }
    var showAdd by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Today's Rituals ✅", style = MaterialTheme.typography.titleMedium)
                Text("${tasks.count { it.done }}/${tasks.size}", style = MaterialTheme.typography.labelLarge)
            }
            LinearProgressIndicator(progress = { if (tasks.isEmpty()) 0f else tasks.count { it.done } / tasks.size.toFloat() }, modifier = Modifier.fillMaxWidth())
            tasks.forEachIndexed { i, t ->
                ListItem(
                    leadingContent = { Checkbox(checked = t.done, onCheckedChange = {
                        tasks = tasks.toMutableList().also { l -> l[i] = t.copy(done = it) }
                    }) },
                    headlineContent = { Text("${t.emoji}  ${t.title}") },
                    supportingContent = { if (t.time.isNotEmpty()) Text(t.time) },
                    trailingContent = { if (!t.isDefault) TextButton(onClick = { tasks = tasks - t }) { Text("✕") } }
                )
            }
            OutlinedButton(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) { Text("+ Custom Task — sirf aaj ke liye") }
            if (showAdd) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(newTitle, { newTitle = it }, label = { Text("Ex: Drink coconut water") }, modifier = Modifier.weight(1f))
                    Button(onClick = {
                        if (newTitle.isBlank()) return@Button
                        tasks = tasks + HabitTask("c_${System.currentTimeMillis()}", newTitle.trim(), "⭐", false, false)
                        newTitle = ""; showAdd = false
                    }) { Text("Add") }
                }
                Text("Ye custom task sirf iss dateKey wale day me save hoga, kal fresh list aayegi.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun SleepMoodCard() {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sleep & Wake 😴", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {}) { Text("⏰ Wakeup: 7:00 AM") }
                OutlinedButton(onClick = {}) { Text("😴 Sleep: 11:30 PM") }
            }
            Text("Neend kaisi thi?")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DayLog.SLEEP_MOODS.forEach { FilterChip(selected = false, onClick = {}, label = { Text(it, style = MaterialTheme.typography.headlineSmall) }) }
            }
        }
    }
}

@Composable
fun DayCloseCard() {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Day kaisa raha? ✨", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DayLog.DAY_MOODS.forEach { FilterChip(selected = false, onClick = {}, label = { Text(it, style = MaterialTheme.typography.headlineSmall) }) }
            }
            OutlinedTextField(value = "", onValueChange = {}, label = { Text("One line day note...") }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMealSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Add Meal", style = MaterialTheme.typography.titleLarge)
            var text by remember { mutableStateOf("") }
            OutlinedTextField(text, { text = it }, label = { Text("Kya khaya?") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Save & Sync 💜") }
            Spacer(Modifier.height(20.dp))
        }
    }
}
