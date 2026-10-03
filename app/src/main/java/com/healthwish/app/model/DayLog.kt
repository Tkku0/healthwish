package com.healthwish.app.model

import com.google.firebase.Timestamp

// One doc per user per day: couples/{coupleId}/days/{dateKey_uid}
data class HabitTask(
    val id: String = "",
    val title: String = "",
    val emoji: String = "✅",
    val done: Boolean = false,
    val isDefault: Boolean = true,
    val time: String = "" // optional HH:mm
)

data class DayLog(
    val id: String = "",
    val ownerUid: String = "",
    val ownerName: String = "", // Me / GF display name
    val coupleId: String = "",
    val dateKey: String = "", // yyyy-MM-dd
    val breakfast: String = "",
    val lunch: String = "",
    val dinner: String = "",
    val snacks: String = "",
    val waterGlasses: Int = 0,
    val wakeupTime: String = "", // HH:mm
    val sleepTime: String = "",  // HH:mm
    val sleepMood: String = "",  // emoji code: awesome/good/ok/tired/bad
    val dayMood: String = "",    // emoji code: loved/happy/ok/sad/stress
    val dayNote: String = "",
    val tasks: List<HabitTask> = defaultTasks(),
    val updatedAt: Timestamp? = null
) {
    companion object {
        fun defaultTasks() = listOf(
            HabitTask("morning_walk", "Morning Walk", "🚶", false, true, "6:30 AM"),
            HabitTask("exercise", "Exercise", "💪", false, true),
            HabitTask("meditation", "Meditation", "🧘", false, true),
            HabitTask("evening_walk", "Evening Walk", "🌇", false, true, "6:00 PM")
        )
        val SLEEP_MOODS = listOf("😍", "😊", "😐", "🥱", "😩") // need kesi thi
        val DAY_MOODS = listOf("❤️", "😄", "🙂", "😔", "😤") // day kesa raha
        val MEAL_TYPES = listOf("breakfast", "lunch", "dinner", "snacks")
    }
}
