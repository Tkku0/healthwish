package com.healthwish.app.data

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.healthwish.app.model.DayLog
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object DayRepository {
    private val db get() = FirebaseFirestore.getInstance()
    private const val COUPLE_ID = "LOVE1"

    fun todayKey() = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
    fun yesterdayKey() = LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_DATE)
    fun docId(dateKey: String, profile: String) = "${dateKey}_${profile.lowercase()}"

    suspend fun loadDay(profile: String, dateKey: String = todayKey()): DayLog? {
        return try {
            val snap = db.collection("couples").document(COUPLE_ID)
                .collection("days").document(docId(dateKey, profile)).get().await()
            snap.toObject(DayLog::class.java)
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

    // Real-time: Live listener on fixed profile (Tkku / Shumu)
    fun listenDay(profile: String, dateKey: String = todayKey(), onChange: (DayLog?) -> Unit): ListenerRegistration {
        return db.collection("couples").document(COUPLE_ID)
            .collection("days").document(docId(dateKey, profile))
            .addSnapshotListener { s, e ->
                if (e != null) {
                    return@addSnapshotListener
                }
                onChange(s?.toObject(DayLog::class.java))
            }
    }

    suspend fun loadRange(profile: String, days: Int = 30): List<DayLog> {
        return try {
            val snap = db.collection("couples").document(COUPLE_ID)
                .collection("days").get().await()
            val all = snap.toObjects(DayLog::class.java)
            all.filter { it.ownerName.equals(profile, ignoreCase = true) || it.id.endsWith(profile.lowercase()) }
                .sortedByDescending { it.dateKey }
                .take(days)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}
