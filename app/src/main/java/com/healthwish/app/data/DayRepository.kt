package com.healthwish.app.data

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.healthwish.app.model.DayLog
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object DayRepository {
    private val db get() = FirebaseFirestore.getInstance()
    private const val COUPLE_ID = "LOVE1" // Settings se changeable, default couple

    fun todayKey() = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
    fun docId(dateKey: String, ownerUid: String) = "${dateKey}_${ownerUid}"

    suspend fun loadDay(ownerUid: String, dateKey: String = todayKey(), coupleId: String = COUPLE_ID): DayLog? {
        val snap = db.collection("couples").document(coupleId)
            .collection("days").document(docId(dateKey, ownerUid)).get().await()
        return snap.toObject(DayLog::class.java)
    }

    suspend fun saveDay(log: DayLog, coupleId: String = COUPLE_ID) {
        db.collection("couples").document(coupleId)
            .collection("days").document(log.id.ifEmpty { docId(log.dateKey, log.ownerUid) })
            .set(log.copy(coupleId = coupleId))
    }

    // Real-time: Me + GF dono ka aaj ka data live
    fun listenToday(ownerUid: String, onChange: (DayLog?) -> Unit): ListenerRegistration {
        return db.collection("couples").document(COUPLE_ID)
            .collection("days").document(docId(todayKey(), ownerUid))
            .addSnapshotListener { s, _ -> onChange(s?.toObject(DayLog::class.java)) }
    }

    suspend fun loadRange(ownerUid: String, days: Int = 30, coupleId: String = COUPLE_ID): List<DayLog> {
        val snap = db.collection("couples").document(coupleId)
            .collection("days").whereEqualTo("ownerUid", ownerUid)
            .limit(days * 2L).get().await()
        return snap.toObjects(DayLog::class.java).sortedByDescending { it.dateKey }
    }
}
