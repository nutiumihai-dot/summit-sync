package ro.summit.sync

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File
import java.time.Instant

// Jurnalul alarmelor: Android anunță orice schimbare a „următoarei alarme” (sună → snooze → următoarea e peste 10 min;
// oprită → următoarea e mâine). Notăm doar [acum, următoarea alarmă, aplicația de ceas], local, 14 zile. Fără internet.

private fun logFile(ctx: Context) = File(ctx.filesDir, "alarme.log")

/** Notează alarma curentă. Apelat de receptor la fiecare schimbare, la pornirea telefonului și la deschiderea aplicației. */
fun logNextAlarm(ctx: Context, why: String) {
    val am = ctx.getSystemService(AlarmManager::class.java) ?: return
    val info = am.nextAlarmClock
    val now = System.currentTimeMillis()
    val f = logFile(ctx)
    val lines = runCatching { f.readLines() }.getOrDefault(emptyList())
    val next = info?.triggerTime ?: 0L
    // fără dubluri: dacă ultima linie are aceeași alarmă și e o simplă redeschidere, nu mai scriem
    val last = lines.lastOrNull()?.split(";")
    if (why == "deschis" && last != null && last.getOrNull(1)?.toLongOrNull() == next) return
    val pkg = runCatching { info?.showIntent?.creatorPackage }.getOrNull() ?: ""
    val keep = lines.filter { it.substringBefore(";").toLongOrNull()?.let { t -> t > now - 14L * 86400_000 } == true }
    f.writeText((keep + "$now;$next;$pkg;$why").joinToString("\n") + "\n")
}

fun readAlarmLog(ctx: Context): List<AlarmEv> = runCatching { logFile(ctx).readLines() }.getOrDefault(emptyList()).mapNotNull { l ->
    val p = l.split(";")
    val t = p.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
    val n = p.getOrNull(1)?.toLongOrNull() ?: 0L
    AlarmEv(Instant.ofEpochMilli(t), if (n > 0) Instant.ofEpochMilli(n) else null)
}

fun alarmLogText(ctx: Context): String {
    val lines = runCatching { logFile(ctx).readLines() }.getOrDefault(emptyList())
    if (lines.isEmpty()) return "Jurnalul alarmelor e gol. Deschide Summit Sync o dată și verifică Autostart."
    val z = java.time.ZoneId.systemDefault()
    val f = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
    val sb = StringBuilder("JURNAL ALARME (acum → următoarea alarmă · aplicație · motiv)\n")
    for (l in lines.takeLast(80)) {
        val p = l.split(";")
        val t = p[0].toLong(); val n = p.getOrNull(1)?.toLongOrNull() ?: 0
        sb.append("${f.format(Instant.ofEpochMilli(t).atZone(z))} → ${if (n > 0) f.format(Instant.ofEpochMilli(n).atZone(z)) else "nicio alarmă"} · ${p.getOrNull(2) ?: ""} · ${p.getOrNull(3) ?: ""}\n")
    }
    return sb.toString()
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val why = when (intent.action) {
            AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED -> "schimbare"
            Intent.ACTION_BOOT_COMPLETED -> "pornire"
            else -> intent.action ?: "?"
        }
        runCatching { logNextAlarm(ctx, why) }
    }
}
