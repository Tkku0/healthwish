package com.healthwish.app.model

// Scoring rules (simple + explainable, dono phone me same):
// dailyScore (0-100) = tasks 50 + water 20 + meals 20 + sleep logged 10
// monthScore = us month ke logged dino ka average. Tie = dono winner.
object ScoreHelper {

    fun dailyScore(l: DayLog): Int {
        val tasksPct = if (l.tasks.isEmpty()) 0f else l.tasks.count { it.done } / l.tasks.size.toFloat()
        val waterPct = (l.waterGlasses.coerceAtLeast(0).coerceAtMost(8)) / 8f
        var meals = 0
        if (l.breakfast.isNotBlank()) meals++
        if (l.lunch.isNotBlank()) meals++
        if (l.dinner.isNotBlank()) meals++
        if (l.snacks.isNotBlank()) meals++
        val sleepPts = if (l.wakeupTime.isNotBlank() || l.sleepTime.isNotBlank()) 10 else 0
        return (tasksPct * 50 + waterPct * 20 + (meals / 4f) * 20 + sleepPts).toInt().coerceIn(0, 100)
    }

    fun isDayLogged(l: DayLog): Boolean =
        l.breakfast.isNotBlank() || l.lunch.isNotBlank() || l.dinner.isNotBlank() ||
            l.snacks.isNotBlank() || l.wakeupTime.isNotBlank() || l.waterGlasses > 0 ||
            l.tasks.any { it.done }

    // Aaj/chaloo streak: pichhle lagatar logged-din jinme score >= 40
    fun streakDays(logsByDate: Map<String, DayLog>, todayKey: String): Int {
        var streak = 0
        var cursor = todayKey
        // Agar aaj abhi kuch log nahi hua to kal se gino (streak toota nahi)
        if (logsByDate[cursor]?.let { !isDayLogged(it) } != false) {
            cursor = prevDate(cursor)
        }
        while (true) {
            val l = logsByDate[cursor] ?: break
            if (!isDayLogged(l) || dailyScore(l) < 40) break
            streak++
            cursor = prevDate(cursor)
        }
        return streak
    }

    private fun prevDate(iso: String): String {
        return try {
            java.time.LocalDate.parse(iso).minusDays(1).toString()
        } catch (e: Exception) {
            ""
        }
    }

    data class WeeklySummary(
        val daysLogged: Int,
        val avgScore: Int,
        val avgWater: Float,
        val tasksDone: Int,
        val tasksTotal: Int,
        val bestDay: String
    )

    fun weekly(logs: List<DayLog>): WeeklySummary {
        if (logs.isEmpty()) return WeeklySummary(0, 0, 0f, 0, 0, "--")
        val scores = logs.map { dailyScore(it) }
        val best = logs.maxByOrNull { dailyScore(it) }?.dateKey ?: "--"
        return WeeklySummary(
            daysLogged = logs.size,
            avgScore = scores.average().toInt(),
            avgWater = logs.map { it.waterGlasses }.average().toFloat(),
            tasksDone = logs.sumOf { l -> l.tasks.count { it.done } },
            tasksTotal = logs.sumOf { it.tasks.size },
            bestDay = best
        )
    }

    fun shareText(profile: String, w: WeeklySummary): String {
        return "💜 $profile's Week: ${w.daysLogged}/7 days logged | Avg score ${w.avgScore} | " +
            "Water ${"%.1f".format(w.avgWater)}/day | Tasks ${w.tasksDone}/${w.tasksTotal} | Best day: ${w.bestDay}"
    }
}
