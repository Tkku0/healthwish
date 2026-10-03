package com.healthwish.app.backup

import android.content.Context
import androidx.work.*
import com.google.firebase.firestore.FirebaseFirestore
import com.google.gson.Gson
import com.healthwish.app.security.Crypto
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.concurrent.TimeUnit

// Roz raat 2 baje auto-run: Firestore -> JSON -> Encrypted ZIP
class BackupWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val coupleId = inputData.getString("coupleId") ?: return Result.failure()
        val password = inputData.getString("password") ?: return Result.failure()
        return try {
            val db = FirebaseFirestore.getInstance()
            val snap = db.collection("couples").document(coupleId)
                .collection("days").get().await()
            val json = Gson().toJson(snap.documents.map { it.data })
            val out = File(applicationContext.filesDir, "healthwish_auto_${System.currentTimeMillis()}.zip")
            Crypto.encryptToFile(json, out, password)
            // purane 7 se zyada backup delete rakho storage bachao
            applicationContext.filesDir.listFiles()
                ?.filter { it.name.startsWith("healthwish_auto_") }
                ?.sortedBy { it.lastModified() }?.dropLast(7)?.forEach { it.delete() }
            Result.success()
        } catch (e: Exception) { Result.retry() }
    }

    companion object {
        fun schedule(ctx: Context, coupleId: String, password: String) {
            val req = PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS)
                .setInputData(workDataOf("coupleId" to coupleId, "password" to password))
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                "healthwish_backup", ExistingPeriodicWorkPolicy.UPDATE, req
            )
        }
    }
}
