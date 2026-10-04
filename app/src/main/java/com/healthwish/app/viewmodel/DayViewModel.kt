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

    init {
        viewModelScope.launch {
            val yday = DayRepository.yesterdayKey()
            // Load initial
            val initialTkku = DayRepository.loadDay("Tkku", today)
            if (initialTkku != null) _tkkuLog.value = initialTkku
            val initialShumu = DayRepository.loadDay("Shumu", today)
            if (initialShumu != null) _shumuLog.value = initialShumu
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

    fun syncScreenTime(profile: String, screenTimeFormatted: String) {
        if (screenTimeFormatted.isBlank()) return
        val currentLog = if (profile.equals("Tkku", ignoreCase = true)) _tkkuLog.value else _shumuLog.value
        if (currentLog.screenOnTime != screenTimeFormatted) {
            updateProfile(profile) { l -> l.copy(screenOnTime = screenTimeFormatted) }
        }
    }

    // Auto checks and backfills yesterday's final screen time if missing
    fun backfillYesterdayScreenTime(profile: String, yesterdayScreenTimeFormatted: String) {
        viewModelScope.launch {
            val yday = DayRepository.yesterdayKey()
            val existing = DayRepository.loadDay(profile, yday)
            if (existing != null && existing.screenOnTime.isBlank() && yesterdayScreenTimeFormatted.isNotBlank()) {
                DayRepository.saveDay(existing.copy(screenOnTime = yesterdayScreenTimeFormatted), profile)
            }
        }
    }
}
