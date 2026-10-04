package com.healthwish.app.data

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.healthwish.app.model.DayLog
import com.healthwish.app.model.HabitTask
import com.healthwish.app.security.FieldCrypto
import com.healthwish.app.security.PrivacyManager
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object DayRepository {
    private val db get() = FirebaseFirestore.getInstance()
    private const val COUPLE_ID = "LOVE1"

    // App start par MainActivity se set hota hai — password padhne ke liye chahiye
    @Volatile private var appCtx: Context? = null
    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    private fun syncPass(): String? = appCtx?.let { PrivacyManager.getPassword(it) }
    private fun salt() = FieldCrypto.saltFor(COUPLE_ID)

    // Password set nahi hai to plaintext (purana behavior). Set hai to content-fields encrypt.
    // Identity fields (id/ownerName/dateKey/coupleId) hamesha plaintext taaki query/sort chale.
    private fun encLog(log: DayLog): DayLog {
        val pw = syncPass() ?: return log
        return log.copy(
            breakfast = FieldCrypto.enc(log.breakfast, pw, salt()),
            lunch = FieldCrypto.enc(log.lunch, pw, salt()),
            dinner = FieldCrypto.enc(log.dinner, pw, salt()),
            snacks = FieldCrypto.enc(log.snacks, pw, salt()),
            wakeupTime = FieldCrypto.enc(log.wakeupTime, pw, salt()),
            sleepTime = FieldCrypto.enc(log.sleepTime, pw, salt()),
            sleepMood = FieldCrypto.enc(log.sleepMood, pw, salt()),
            dayMood = FieldCrypto.enc(log.dayMood, pw, salt()),
            dayNote = FieldCrypto.enc(log.dayNote, pw, salt()),
            screenOnTime = FieldCrypto.enc(log.screenOnTime, pw, salt()),
            tasks = log.tasks.map { encTask(it, pw) }
        )
    }

    private fun decLog(log: DayLog): DayLog {
        val pw = syncPass() ?: return log
        return log.copy(
            breakfast = FieldCrypto.dec(log.breakfast, pw, salt()),
            lunch = FieldCrypto.dec(log.lunch, pw, salt()),
            dinner = FieldCrypto.dec(log.dinner, pw, salt()),
            snacks = FieldCrypto.dec(log.snacks, pw, salt()),
            wakeupTime = FieldCrypto.dec(log.wakeupTime, pw, salt()),
            sleepTime = FieldCrypto.dec(log.sleepTime, pw, salt()),
            sleepMood = FieldCrypto.dec(log.sleepMood, pw, salt()),
            dayMood = FieldCrypto.dec(log.dayMood, pw, salt()),
            dayNote = FieldCrypto.dec(log.dayNote, pw, salt()),
            screenOnTime = FieldCrypto.dec(log.screenOnTime, pw, salt()),
            tasks = log.tasks.map { decTask(it, pw) }
        )
    }

    private fun encTask(t: HabitTask, pw: String) = t.copy(
        title = FieldCrypto.enc(t.title, pw, salt()),
        emoji = FieldCrypto.enc(t.emoji, pw, salt()),
        time = FieldCrypto.enc(t.time, pw, salt())
    )

    private fun decTask(t: HabitTask, pw: String) = t.copy(
        title = FieldCrypto.dec(t.title, pw, salt()),
        emoji = FieldCrypto.dec(t.emoji, pw, salt()),
        time = FieldCrypto.dec(t.time, pw, salt())
    )

    fun todayKey() = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
    fun yesterdayKey() = LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_DATE)
    fun docId(dateKey: String, profile: String) = "${dateKey}_${profile.lowercase()}"

    suspend fun loadDay(profile: String, dateKey: String = todayKey()): DayLog? {
        return try {
            val snap = db.collection("couples").document(COUPLE_ID)
                .collection("days").document(docId(dateKey, profile)).get().await()
            snap.toObject(DayLog::class.java)?.let { decLog(it) }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun saveDay(log: DayLog, profile: String) {
        val targetId = docId(log.dateKey, profile)
        try {
            val toSave = encLog(log.copy(id = targetId, ownerName = profile, coupleId = COUPLE_ID))
            db.collection("couples").document(COUPLE_ID)
                .collection("days").document(targetId)
                .set(toSave, SetOptions.merge())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // SAFE: only touches screenOnTime (+ identity fields). Diet fields ko kabhi overwrite nahi karta.
    // Full-doc set() se hota tha data wipe jab local state khaali hota tha — isliye SOT ke liye hamesha ye use karo.
    suspend fun updateScreenTimeField(profile: String, dateKey: String, screenTime: String) {
        if (screenTime.isBlank()) return
        val targetId = docId(dateKey, profile)
        try {
            val pw = syncPass()
            val sotValue = if (pw != null) FieldCrypto.enc(screenTime, pw, salt()) else screenTime
            val patch = mapOf(
                "screenOnTime" to sotValue,
                "id" to targetId,
                "ownerName" to profile,
                "coupleId" to COUPLE_ID,
                "dateKey" to dateKey
            )
            db.collection("couples").document(COUPLE_ID)
                .collection("days").document(targetId)
                .set(patch, SetOptions.merge())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Real-time: Live listener on fixed profile (Tkku / Shumu)
    fun listenDay(profile: String, dateKey: String = todayKey(), onChange: (DayLog?) -> Unit): ListenerRegistration {
        return db.collection("couples").document(COUPLE_ID)
            .collection("days").document(docId(dateKey, profile))
            .addSnapshotListener { s, e ->
                if (e != null) {
                    return@addSnapshotListener
                }
                onChange(s?.toObject(DayLog::class.java)?.let { decLog(it) })
            }
    }

    suspend fun loadRange(profile: String, days: Int = 30): List<DayLog> {
        return try {
            val snap = db.collection("couples").document(COUPLE_ID)
                .collection("days").get().await()
            val all = snap.toObjects(DayLog::class.java).map { decLog(it) }
            all.filter { it.ownerName.equals(profile, ignoreCase = true) || it.id.endsWith(profile.lowercase()) }
                .sortedByDescending { it.dateKey }
                .take(days)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}
