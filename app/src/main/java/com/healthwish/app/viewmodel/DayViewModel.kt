package com.healthwish.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.healthwish.app.data.DayRepository
import com.healthwish.app.model.DayLog
import com.healthwish.app.model.HabitTask
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class DayViewModel : ViewModel() {
    private val uid get() = FirebaseAuth.getInstance().currentUser?.uid ?: "local"
    private val name get() = FirebaseAuth.getInstance().currentUser?.displayName ?: "Me"

    private val _me = MutableStateFlow(DayLog(ownerUid = uid, dateKey = DayRepository.todayKey()))
    private val _gf = MutableStateFlow<DayLog?>(null)
    val me: StateFlow<DayLog> = _me.asStateFlow()
    val gf: StateFlow<DayLog?> = _gf.asStateFlow()

    init {
        viewModelScope.launch {
            // local today load
            DayRepository.loadDay(uid)?.let { _me.value = it }
            // live listen me
            DayRepository.listenToday(uid) { it?.let { _me.value = it } }
        }
    }

    fun watchGf(gfUid: String) {
        DayRepository.listenToday(gfUid) { _gf.value = it }
    }

    fun update(fn: (DayLog) -> DayLog) {
        _me.value = fn(_me.value.copy(ownerUid = uid, ownerName = name, dateKey = DayRepository.todayKey(), id = DayRepository.docId(DayRepository.todayKey(), uid)))
        viewModelScope.launch { runCatching { DayRepository.saveDay(_me.value) } }
    }

    fun toggleTask(task: HabitTask) = update { l ->
        l.copy(tasks = l.tasks.map { if (it.id == task.id) it.copy(done = !it.done) else it })
    }
    fun addCustomTask(title: String) = update { l ->
        l.copy(tasks = l.tasks + HabitTask("c_${System.currentTimeMillis()}", title, "⭐", false, false))
    }
    fun setMeal(type: String, text: String) = update { l ->
        when (type) {
            "breakfast" -> l.copy(breakfast = text)
            "lunch" -> l.copy(lunch = text)
            "dinner" -> l.copy(dinner = text)
            else -> l.copy(snacks = text)
        }
    }
    fun setSleep(wake: String, sleep: String, mood: String) = update { l -> l.copy(wakeupTime = wake, sleepTime = sleep, sleepMood = mood) }
    fun setDayMood(mood: String, note: String) = update { l -> l.copy(dayMood = mood, dayNote = note) }
    fun setWater(n: Int) = update { l -> l.copy(waterGlasses = n) }
}
