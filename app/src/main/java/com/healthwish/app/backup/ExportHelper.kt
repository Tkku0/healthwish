package com.healthwish.app.backup

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.healthwish.app.model.DayLog
import java.io.File

// Full data export: CSV taaki diet summary kahin bhi banao (Excel/Sheets)
object ExportHelper {
    fun exportCsv(ctx: Context, logs: List<DayLog>): File {
        val sb = StringBuilder()
        sb.appendLine("date,owner,screen_on_time,breakfast,lunch,dinner,snacks,water,wakeup,sleep,sleepMood,dayMood,dayNote,tasks_done,tasks_total")
        logs.sortedBy { it.dateKey }.forEach { l ->
            val done = l.tasks.count { it.done }
            fun esc(s: String) = "\"${s.replace("\"", "'").replace("\n", " ")}\""
            sb.appendLine(
                listOf(l.dateKey, l.ownerName, esc(l.screenOnTime), esc(l.breakfast), esc(l.lunch), esc(l.dinner),
                    esc(l.snacks), l.waterGlasses.toString(), l.wakeupTime, l.sleepTime,
                    l.sleepMood, l.dayMood, esc(l.dayNote), done.toString(), l.tasks.size.toString()
                ).joinToString(",")
            )
        }
        val f = File(ctx.cacheDir, "healthwish_export_${System.currentTimeMillis()}.csv")
        f.writeText(sb.toString())
        return f
    }

    fun share(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", file)
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"; putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Export HealthWish 💜").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    }
}
