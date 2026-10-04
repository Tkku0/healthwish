package com.healthwish.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.healthwish.app.data.DayRepository
import com.healthwish.app.model.DayLog
import com.healthwish.app.model.HabitTask
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    // Month logs (bounded query): last weight lookup ke liye
    private val _monthTkku = MutableStateFlow<List<DayLog>>(emptyList())
    private val _monthShumu = MutableStateFlow<List<DayLog>>(emptyList())
    val monthTkku: StateFlow<List<DayLog>> = _monthTkku.asStateFlow()
    val monthShumu: StateFlow<List<DayLog>> = _monthShumu.asStateFlow()

    // Server se initial load ho gaya ya nahi — isse pehle full-doc save karna unsafe hai
    @Volatile private var tkkuReady = false
    @Volatile private var shumuReady = false

    // Water stepper ke rapid taps ko coalesce karta hai (1 write per burst)
    private var waterJobTkku: Job? = null
    private var waterJobShumu: Job? = null

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
            refreshMonthData()

            // Real-time live listener for Tkku
            DayRepository.listenDay("Tkku", today) { log ->
                if (log != null) {
                    _tkkuLog.value = log
                    patchMonthLocal("Tkku", log)
                }
            }

            // Real-time live listener for Shumu
            DayRepository.listenDay("Shumu", today) { log ->
                if (log != null) {
                    _shumuLog.value = log
                    patchMonthLocal("Shumu", log)
                }
            }
        }
    }

    fun refreshMonthData() {
        viewModelScope.launch {
            try {
                val from = DayRepository.monthStartKey()
                _monthTkku.value = DayRepository.loadSince("Tkku", from, 40)
                _monthShumu.value = DayRepository.loadSince("Shumu", from, 40)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun patchMonthLocal(profile: String, updated: DayLog) {
        if (updated.dateKey != today) return
        if (profile.equals("Tkku", ignoreCase = true)) {
            val list = _monthTkku.value.toMutableList()
            val i = list.indexOfFirst { it.dateKey == today }
            if (i >= 0) list[i] = updated else list.add(0, updated)
            _monthTkku.value = list
        } else {
            val list = _monthShumu.value.toMutableList()
            val i = list.indexOfFirst { it.dateKey == today }
            if (i >= 0) list[i] = updated else list.add(0, updated)
            _monthShumu.value = list
        }
    }

    fun updateProfile(profile: String, fn: (DayLog) -> DayLog) {
        if (profile.equals("Tkku", ignoreCase = true)) {
            val updated = fn(_tkkuLog.value.copy(ownerName = "Tkku", dateKey = today))
            _tkkuLog.value = updated
            patchMonthLocal("Tkku", updated)
            viewModelScope.launch { DayRepository.saveDay(updated, "Tkku") }
        } else {
            val updated = fn(_shumuLog.value.copy(ownerName = "Shumu", dateKey = today))
            _shumuLog.value = updated
            patchMonthLocal("Shumu", updated)
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
            "breakfast" -> if (l.breakfast != text) l.copy(breakfast = text, breakfastKcal = 0) else l
            "lunch" -> if (l.lunch != text) l.copy(lunch = text, lunchKcal = 0) else l
            "dinner" -> if (l.dinner != text) l.copy(dinner = text, dinnerKcal = 0) else l
            else -> if (l.snacks != text) l.copy(snacks = text, snacksKcal = 0) else l
        }
    }

    fun setMealWithKcal(profile: String, type: String, text: String, kcal: Int) =
        updateProfile(profile) { l ->
            val k = kcal.coerceAtLeast(0)
            when (type) {
                "breakfast" -> l.copy(breakfast = text, breakfastKcal = k)
                "lunch" -> l.copy(lunch = text, lunchKcal = k)
                "dinner" -> l.copy(dinner = text, dinnerKcal = k)
                else -> l.copy(snacks = text, snacksKcal = k)
            }
        }

    // Manual entry ke baad AI auto-calorie: sirf tab jab text abhi bhi wahi hai (stale overwrite nahi).
    // Fail ho to chup nahi — chhota reason toast me (sirf jab AI setup hai).
    fun estimateKcal(ctx: android.content.Context, profile: String, type: String, text: String) {
        val appCtx = ctx.applicationContext
        viewModelScope.launch {
            try {
                if (!com.healthwish.app.security.AiConfigManager.isConfigured(appCtx)) return@launch
                if (text.isBlank()) return@launch
                val (kcal, err) = com.healthwish.app.ai.AiClient.estimateCalories(
                    com.healthwish.app.security.AiConfigManager.get(appCtx), text
                )
                if (kcal == null) {
                    android.widget.Toast.makeText(
                        appCtx, "Calorie AI fail: ${(err ?: "pata nahi").take(100)}", android.widget.Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                val isTkku = profile.equals("Tkku", ignoreCase = true)
                val current = if (isTkku) _tkkuLog.value else _shumuLog.value
                val curText = when (type) {
                    "breakfast" -> current.breakfast
                    "lunch" -> current.lunch
                    "dinner" -> current.dinner
                    else -> current.snacks
                }
                if (curText != text) return@launch // user ne beech me badal diya — purana estimate discard
                setMealWithKcal(profile, type, text, kcal)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // AI free-text entry: callback me (result, error?). error null matlab success.
    fun parseFoodInput(
        ctx: android.content.Context,
        input: String,
        onResult: (com.healthwish.app.ai.AiMealResult?, String?) -> Unit
    ) {
        val appCtx = ctx.applicationContext
        viewModelScope.launch {
            try {
                if (!com.healthwish.app.security.AiConfigManager.isConfigured(appCtx)) {
                    onResult(null, "Settings > AI Setup me key dalo 🔑")
                    return@launch
                }
                val (res, err) = com.healthwish.app.ai.AiClient.parseFoodInput(
                    com.healthwish.app.security.AiConfigManager.get(appCtx), input
                )
                onResult(res, err)
            } catch (e: Exception) {
                e.printStackTrace()
                onResult(null, e.message ?: "AI fail")
            }
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

    // Water +/- ke rapid taps: UI turant, server write 700ms debounce (burst = 1 write)
    fun setWaterFast(profile: String, n: Int) {
        val safe = n.coerceAtLeast(0)
        if (profile.equals("Tkku", ignoreCase = true)) {
            val updated = _tkkuLog.value.copy(ownerName = "Tkku", dateKey = today, waterGlasses = safe)
            _tkkuLog.value = updated
            patchMonthLocal("Tkku", updated)
            waterJobTkku?.cancel()
            waterJobTkku = viewModelScope.launch {
                delay(700)
                DayRepository.saveDay(_tkkuLog.value.copy(ownerName = "Tkku", dateKey = today), "Tkku")
            }
        } else {
            val updated = _shumuLog.value.copy(ownerName = "Shumu", dateKey = today, waterGlasses = safe)
            _shumuLog.value = updated
            patchMonthLocal("Shumu", updated)
            waterJobShumu?.cancel()
            waterJobShumu = viewModelScope.launch {
                delay(700)
                DayRepository.saveDay(_shumuLog.value.copy(ownerName = "Shumu", dateKey = today), "Shumu")
            }
        }
    }

    fun setWeight(profile: String, kg: Float) = updateProfile(profile) { l ->
        l.copy(weightKg = kg.coerceIn(0f, 300f))
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
