package com.healthwish.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthwish.app.data.DayRepository
import com.healthwish.app.model.DayLog
import com.healthwish.app.model.HabitTask
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class DayViewModel : ViewModel() {
    private val today = DayRepository.todayKey()

    // 2 explicit, clean profiles: Tkku & Shumu
    private val _tkkuLog = MutableStateFlow(DayLog(ownerName = "Tkku", dateKey = today))
    private val _shumuLog = MutableStateFlow(DayLog(ownerName = "Shumu", dateKey = today))

    // Yesterday's logs to accurately calculate last night sleep hours
    private val _tkkuYesterday = MutableStateFlow<DayLog?>(null)
    private val _shumuYesterday = MutableStateFlow<DayLog?>(null)

    val tkkuLog: StateFlow<DayLog> = _tkkuLog.asStateFlow()
    val shumuLog: StateFlow<DayLog> = _shumuLog.asStateFlow()
    val tkkuYesterday: StateFlow<DayLog?> = _tkkuYesterday.asStateFlow()
    val shumuYesterday: StateFlow<DayLog?> = _shumuYesterday.asStateFlow()

    // Server se initial load ho gaya ya nahi — isse pehle full-doc save karna unsafe hai
    @Volatile private var tkkuReady = false
    @Volatile private var shumuReady = false

    init {
        viewModelScope.launch {
            val yday = DayRepository.yesterdayKey()
            // Load initial
            val initialTkku = DayRepository.loadDay("Tkku", today)
            if (initialTkku != null) _tkkuLog.value = initialTkku
            tkkuReady = true
            val initialShumu = DayRepository.loadDay("Shumu", today)
            if (initialShumu != null) _shumuLog.value = initialShumu
            shumuReady = true
            DayRepository.loadDay("Tkku", yday)?.let { _tkkuYesterday.value = it }
            DayRepository.loadDay("Shumu", yday)?.let { _shumuYesterday.value = it }

            // Real-time live listener for Tkku
            DayRepository.listenDay("Tkku", today) { log ->
                if (log != null) {
                    _tkkuLog.value = log
                }
            }

            // Real-time live listener for Shumu
            DayRepository.listenDay("Shumu", today) { log ->
                if (log != null) {
                    _shumuLog.value = log
                }
            }
        }
    }

    fun updateProfile(profile: String, fn: (DayLog) -> DayLog) {
        if (profile.equals("Tkku", ignoreCase = true)) {
            val updated = fn(_tkkuLog.value.copy(ownerName = "Tkku", dateKey = today))
            _tkkuLog.value = updated
            viewModelScope.launch { DayRepository.saveDay(updated, "Tkku") }
        } else {
            val updated = fn(_shumuLog.value.copy(ownerName = "Shumu", dateKey = today))
            _shumuLog.value = updated
            viewModelScope.launch { DayRepository.saveDay(updated, "Shumu") }
        }
    }

    fun toggleTask(profile: String, task: HabitTask) = updateProfile(profile) { l ->
        l.copy(tasks = l.tasks.map { if (it.id == task.id) it.copy(done = !it.done) else it })
    }

    fun addCustomTask(profile: String, title: String) = updateProfile(profile) { l ->
        l.copy(tasks = l.tasks + HabitTask("c_${System.currentTimeMillis()}", title, "⭐", false, false))
    }

    fun setMeal(profile: String, type: String, text: String) = updateProfile(profile) { l ->
        when (type) {
            "breakfast" -> l.copy(breakfast = text)
            "lunch" -> l.copy(lunch = text)
            "dinner" -> l.copy(dinner = text)
            else -> l.copy(snacks = text)
        }
    }

    fun setSleep(profile: String, wake: String, sleep: String, mood: String) = updateProfile(profile) { l ->
        l.copy(wakeupTime = wake, sleepTime = sleep, sleepMood = mood)
    }

    fun setDayMood(profile: String, mood: String, note: String) = updateProfile(profile) { l ->
        l.copy(dayMood = mood, dayNote = note)
    }

    fun setWater(profile: String, n: Int) = updateProfile(profile) { l ->
        l.copy(waterGlasses = n)
    }

    // SAFE SOT sync: kabhi full-doc save nahi karta, sirf screenOnTime field merge karta hai.
    // Pehle server se fresh doc load karta hai taaki khaali local state diet ko wipe na kare.
    fun syncScreenTime(profile: String, screenTimeFormatted: String) {
        if (screenTimeFormatted.isBlank()) return
        viewModelScope.launch {
            try {
                val fresh = DayRepository.loadDay(profile, today)
                val isTkku = profile.equals("Tkku", ignoreCase = true)
                val base = fresh ?: if (isTkku) _tkkuLog.value else _shumuLog.value
                // Agar server/local dono me same SOT hai to write skip — har launch par Firestore write nahi
                if (fresh != null && fresh.screenOnTime == screenTimeFormatted) {
                    if (isTkku) { _tkkuLog.value = fresh.copy(ownerName = "Tkku", dateKey = today) }
                    else { _shumuLog.value = fresh.copy(ownerName = "Shumu", dateKey = today) }
                    return@launch
                }
                if (fresh == null && base.screenOnTime == screenTimeFormatted) return@launch
                DayRepository.updateScreenTimeField(profile, today, screenTimeFormatted)
                // Local UI turant update, lekin sirf SOT field — baaki sab freshest source se
                if (isTkku) {
                    _tkkuLog.value = base.copy(ownerName = "Tkku", dateKey = today, screenOnTime = screenTimeFormatted)
                    tkkuReady = true
                } else {
                    _shumuLog.value = base.copy(ownerName = "Shumu", dateKey = today, screenOnTime = screenTimeFormatted)
                    shumuReady = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Auto checks and backfills yesterday's final screen time if missing (single-field merge only)
    fun backfillYesterdayScreenTime(profile: String, yesterdayScreenTimeFormatted: String) {
        viewModelScope.launch {
            try {
                if (yesterdayScreenTimeFormatted.isBlank()) return@launch
                val yday = DayRepository.yesterdayKey()
                val existing = DayRepository.loadDay(profile, yday)
                if (existing != null && existing.screenOnTime.isBlank()) {
                    DayRepository.updateScreenTimeField(profile, yday, yesterdayScreenTimeFormatted)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
