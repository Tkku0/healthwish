package com.healthwish.app.data

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.healthwish.app.model.DayLog
import com.healthwish.app.security.FieldCrypto
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object DayRepository {
    private val db get() = FirebaseFirestore.getInstance()
    private const val COUPLE_ID = "LOVE1"

    @Volatile private var appCtx: Context? = null
    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    // Purani encryption-build ka password (agar is phone me set hua tha) — sirf purana data recover karne ke liye.
    // Ek baar padh ke cache kar lo (har read par Keystore IO nahi).
    @Volatile private var legacyPassLoaded = false
    @Volatile private var legacyPassCached: String? = null
    private fun legacyPass(): String? {
        if (legacyPassLoaded) return legacyPassCached
        legacyPassCached = try {
            val c = appCtx ?: return null
            val prefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                c, "healthwish_privacy",
                androidx.security.crypto.MasterKey.Builder(c)
                    .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build(),
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            prefs.getString("sync_password", null)?.ifBlank { null }
        } catch (e: Exception) {
            null
        }
        legacyPassLoaded = true
        return legacyPassCached
    }

    // ENC1: ciphertext ko readable banao: password ho to decrypt, nahi to blank (code kabhi mat dikhao).
    // Naye saves hamesha plaintext hote hain, isliye purana kachra dheere-dheere khud saaf ho jayega.
    // Default tasks ke naam/emoji fixed hain — unhe hamesha restore karo; unrecoverable custom tasks hatao.
    private fun cleanLog(log: DayLog): DayLog {
        val defaults = DayLog.defaultTasks().associateBy { it.id }
        var changed = hasLegacyCipher(log)
        val pw = if (changed) legacyPass() else null
        val salt = FieldCrypto.saltFor(COUPLE_ID)
        fun fix(v: String): String {
            if (!v.startsWith(FieldCrypto.PREFIX)) return v
            if (pw == null) return ""
            val p = FieldCrypto.dec(v, pw, salt)
            return if (p.startsWith(FieldCrypto.PREFIX)) "" else p
        }
        val tasks = log.tasks.mapNotNull { t ->
            val d = defaults[t.id]
            if (d != null && (t.title.isBlank() || t.title.startsWith(FieldCrypto.PREFIX))) {
                changed = true
                t.copy(title = d.title, emoji = d.emoji, time = d.time)
            } else if (t.id.startsWith("c_") && t.title.isBlank()) {
                changed = true
                null // unrecoverable custom task — blank row mat dikhao
            } else {
                val nt = t.title
                val ne = t.emoji
                val nm = t.time
                val ft = fix(nt)
                val fe = fix(ne)
                val fm = fix(nm)
                if (ft != nt || fe != ne || fm != nm) changed = true
                t.copy(title = ft, emoji = fe, time = fm)
            }
        }
        if (!changed) return log
        return log.copy(
            breakfast = fix(log.breakfast),
            lunch = fix(log.lunch),
            dinner = fix(log.dinner),
            snacks = fix(log.snacks),
            wakeupTime = fix(log.wakeupTime),
            sleepTime = fix(log.sleepTime),
            sleepMood = fix(log.sleepMood),
            dayMood = fix(log.dayMood),
            dayNote = fix(log.dayNote),
            screenOnTime = fix(log.screenOnTime),
            tasks = tasks
        )
    }

    private fun hasLegacyCipher(log: DayLog): Boolean {
        fun isEnc(v: String) = v.startsWith(FieldCrypto.PREFIX)
        return isEnc(log.breakfast) || isEnc(log.lunch) || isEnc(log.dinner) ||
            isEnc(log.snacks) || isEnc(log.wakeupTime) || isEnc(log.sleepTime) ||
            isEnc(log.sleepMood) || isEnc(log.dayMood) || isEnc(log.dayNote) ||
            isEnc(log.screenOnTime) ||
            log.tasks.any { isEnc(it.title) || isEnc(it.emoji) || isEnc(it.time) }
    }

    fun todayKey() = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
    fun yesterdayKey() = LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_DATE)
    fun docId(dateKey: String, profile: String) = "${dateKey}_${profile.lowercase()}"

    suspend fun loadDay(profile: String, dateKey: String = todayKey()): DayLog? {
        return try {
            val snap = db.collection("couples").document(COUPLE_ID)
                .collection("days").document(docId(dateKey, profile)).get().await()
            snap.toObject(DayLog::class.java)?.let { cleanLog(it) }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun saveDay(log: DayLog, profile: String) {
        val targetId = docId(log.dateKey, profile)
        try {
            val toSave = log.copy(id = targetId, ownerName = profile, coupleId = COUPLE_ID)
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
            val patch = mapOf(
                "screenOnTime" to screenTime,
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
                onChange(s?.toObject(DayLog::class.java)?.let { cleanLog(it) })
            }
    }

    // Bounded query: sirf fromDateKey ke baad ke docs download hote hain (poori collection scan nahi).
    // Single-field range hai, isliye composite index ki zaroorat nahi.
    suspend fun loadSince(profile: String, fromDateKey: String, limit: Int = 60): List<DayLog> {
        return try {
            val snap = db.collection("couples").document(COUPLE_ID)
                .collection("days")
                .whereGreaterThanOrEqualTo("dateKey", fromDateKey)
                .get().await()
            snap.toObjects(DayLog::class.java).map { cleanLog(it) }
                .filter { it.ownerName.equals(profile, ignoreCase = true) || it.id.endsWith(profile.lowercase()) }
                .sortedByDescending { it.dateKey }
                .take(limit)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun loadRange(profile: String, days: Int = 30): List<DayLog> {
        val from = try {
            java.time.LocalDate.now().minusDays((days * 3).toLong()).toString()
        } catch (e: Exception) {
            "2000-01-01"
        }
        return loadSince(profile, from, days)
    }

    fun monthStartKey(): String {
        return try {
            java.time.LocalDate.now().withDayOfMonth(1).toString()
        } catch (e: Exception) {
            todayKey()
        }
    }
}
