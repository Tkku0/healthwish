package com.healthwish.app.data

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.healthwish.app.model.DayLog
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object DayRepository {
    private val db get() = FirebaseFirestore.getInstance()
    private const val COUPLE_ID = "LOVE1"

    fun todayKey() = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
    fun docId(dateKey: String, ownerUid: String) = "${dateKey}_${ownerUid}"

    // Auto discover GF's UID from couple doc; agar doc nahi bana to auto bana dega
    suspend fun getGfUid(myUid: String, coupleId: String = COUPLE_ID): String? {
        return try {
            val docRef = db.collection("couples").document(coupleId)
            val doc = docRef.get().await()

            if (!doc.exists()) {
                // Pehli baar user ne login kiya to auto create LOVE1 doc
                docRef.set(mapOf("members" to listOf(myUid)))
                null
            } else {
                val members = (doc.get("members") as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                if (!members.contains(myUid)) {
                    // Agar dusra partner (GF) login kare to uska UID bhi auto add kar do
                    val updated = members + myUid
                    docRef.update("members", updated)
                    members.firstOrNull { it != myUid }
                } else {
                    members.firstOrNull { it != myUid }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun loadDay(ownerUid: String, dateKey: String = todayKey(), coupleId: String = COUPLE_ID): DayLog? {
        return try {
            val snap = db.collection("couples").document(coupleId)
                .collection("days").document(docId(dateKey, ownerUid)).get().await()
            snap.toObject(DayLog::class.java)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun saveDay(log: DayLog, coupleId: String = COUPLE_ID) {
        val targetId = log.id.ifEmpty { docId(log.dateKey, log.ownerUid) }
        try {
            db.collection("couples").document(coupleId)
                .collection("days").document(targetId)
                .set(log.copy(id = targetId, coupleId = coupleId))
        } catch (e: Exception) {
            // Firestore write error logged silently
        }
    }

    // Real-time: Me / GF live updates
    fun listenToday(ownerUid: String, onChange: (DayLog?) -> Unit): ListenerRegistration {
        return db.collection("couples").document(COUPLE_ID)
            .collection("days").document(docId(todayKey(), ownerUid))
            .addSnapshotListener { s, _ ->
                onChange(s?.toObject(DayLog::class.java))
            }
    }

    // Listen to changes in the couple doc to detect when partner registers
    fun listenCouple(coupleId: String = COUPLE_ID, onChange: (List<String>) -> Unit): ListenerRegistration {
        return db.collection("couples").document(coupleId)
            .addSnapshotListener { s, _ ->
                val members = (s?.get("members") as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                onChange(members)
            }
    }

    suspend fun loadRange(ownerUid: String, days: Int = 30, coupleId: String = COUPLE_ID): List<DayLog> {
        return try {
            val snap = db.collection("couples").document(coupleId)
                .collection("days").whereEqualTo("ownerUid", ownerUid)
                .limit(days * 2L).get().await()
            snap.toObjects(DayLog::class.java).sortedByDescending { it.dateKey }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
