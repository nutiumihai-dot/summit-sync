package ro.summit.sync

import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.reflect.KClass

val READ_TYPES: List<KClass<out Record>> = listOf(
    StepsRecord::class, HeartRateRecord::class, RestingHeartRateRecord::class,
    HeartRateVariabilityRmssdRecord::class, SleepSessionRecord::class, ExerciseSessionRecord::class,
    DistanceRecord::class, ActiveCaloriesBurnedRecord::class, TotalCaloriesBurnedRecord::class,
    OxygenSaturationRecord::class, RespiratoryRateRecord::class, FloorsClimbedRecord::class,
    ElevationGainedRecord::class,
)
const val PERM_ROUTES = "android.permission.health.READ_EXERCISE_ROUTES"
val ALL_PERMS: Set<String> = READ_TYPES.map { HealthPermission.getReadPermission(it) }.toSet() + PERM_ROUTES

suspend fun readAll(c: HealthConnectClient, type: KClass<out Record>, start: Instant, end: Instant): List<Record> {
    @Suppress("UNCHECKED_CAST") val k = type as KClass<Record>
    val all = mutableListOf<Record>()
    var token: String? = null
    do {
        val r = c.readRecords(ReadRecordsRequest(k, TimeRangeFilter.between(start, end), pageSize = 5000, pageToken = token))
        all += r.records
        token = r.pageToken
    } while (!token.isNullOrEmpty())
    return all
}

fun span(r: Record): Pair<Instant, Instant> = when (r) {
    is IntervalRecord -> r.startTime to r.endTime
    is InstantaneousRecord -> r.time to r.time
    else -> Instant.EPOCH to Instant.EPOCH
}

private val Z: ZoneId get() = ZoneId.systemDefault()
private fun t(i: Instant) = DateTimeFormatter.ofPattern("MM-dd HH:mm").format(i.atZone(Z))
private fun hm(i: Instant) = DateTimeFormatter.ofPattern("HH:mm").format(i.atZone(Z))
private fun day(i: Instant) = i.atZone(Z).toLocalDate()
private fun pct(l: List<Long>, p: Double): Long = if (l.isEmpty()) -1 else l.sorted()[((l.size - 1) * p).toInt()]

private val DEV = mapOf(0 to "necunoscut", 1 to "ceas", 2 to "telefon", 3 to "cântar", 4 to "inel", 5 to "cască", 6 to "brățară", 7 to "centură", 8 to "ecran")
private val METH = mapOf(0 to "necunoscut", 1 to "activ", 2 to "automat", 3 to "manual")
private val STAGE = mapOf(0 to "necun", 1 to "treaz", 2 to "doarme", 3 to "dinPat", 4 to "ușor", 5 to "profund", 6 to "REM", 7 to "treazÎnPat")

suspend fun runDiagnostic(ctx: Context, days: Int = 14): String {
    val sb = StringBuilder()
    fun l(s: String = "") { sb.append(s).append('\n') }
    val now = Instant.now()
    val today = LocalDate.now(Z)
    val start = today.minusDays((days - 1).toLong()).atStartOfDay(Z).minusHours(6).toInstant()
    l("SUMMIT SYNC DIAGNOSTIC ${BuildConfigInfo.version(ctx)}")
    l("acum ${t(now)} fus ${Z.id} · ${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    for (p in listOf("com.google.android.healthconnect.controller", "com.google.android.apps.healthdata", "com.mi.health", "com.xiaomi.wearable", "com.xiaomi.hm.health")) {
        val v = try { ctx.packageManager.getPackageInfo(p, 0).versionName } catch (e: Exception) { null }
        if (v != null) l("pachet $p $v")
    }
    val st = HealthConnectClient.getSdkStatus(ctx)
    l("HC sdkStatus=$st (3=disponibil)")
    if (st != HealthConnectClient.SDK_AVAILABLE) return sb.toString()
    val c = HealthConnectClient.getOrCreate(ctx)
    val granted = c.permissionController.getGrantedPermissions()
    l("permisiuni ${granted.size}/${ALL_PERMS.size}; lipsă: ${(ALL_PERMS - granted).joinToString { it.substringAfterLast('.') }.ifEmpty { "-" }}")
    l("fereastră ${t(start)} → ${t(now)}")

    val data = mutableMapOf<KClass<out Record>, List<Record>>()
    l(); l("== TIPURI ==")
    for (type in READ_TYPES) {
        val name = type.simpleName!!.removeSuffix("Record")
        if (HealthPermission.getReadPermission(type) !in granted) { l("$name: fără permisiune"); continue }
        val recs = try { readAll(c, type, start, now) } catch (e: Exception) { l("$name: EROARE ${e.javaClass.simpleName} ${e.message}"); continue }
        data[type] = recs
        l("$name: ${recs.size}")
        for ((pkg, g) in recs.groupBy { it.metadata.dataOrigin.packageName }) {
            val meth = g.groupingBy { METH[it.metadata.recordingMethod] ?: "${it.metadata.recordingMethod}" }.eachCount()
            val dev = g.groupingBy { it.metadata.device?.let { d -> "${d.manufacturer}/${d.model}/${DEV[d.type] ?: d.type}" } ?: "-" }.eachCount()
            l("  $pkg ×${g.size} ${t(g.minOf { span(it).first })}→${t(g.maxOf { span(it).second })} modif≤${t(g.maxOf { it.metadata.lastModifiedTime })}")
            l("    metodă $meth · dispozitiv $dev")
        }
    }

    // ---- pași
    data[StepsRecord::class]?.let { recs ->
        val steps = recs.filterIsInstance<StepsRecord>()
        l(); l("== PAȘI ==")
        for ((pkg, g) in steps.groupBy { it.metadata.dataOrigin.packageName }) {
            val durs = g.map { Duration.between(it.startTime, it.endTime).toMinutes() }
            l("$pkg: durata înregistrării min p50=${pct(durs, .5)} p90=${pct(durs, .9)} max=${durs.maxOrNull()}")
            l("  pe zile: " + g.groupBy { day(it.startTime) }.toSortedMap().entries.joinToString(" ") { "${it.key.dayOfMonth}:${it.value.sumOf { r -> r.count }}" })
            for (d in listOf(today.minusDays(1), today)) {
                val h = LongArray(24)
                g.filter { day(it.startTime) == d }.forEach { h[it.startTime.atZone(Z).hour] += it.count }
                l("  ${d.dayOfMonth} pe ore: ${h.joinToString(",")}")
            }
        }
        try {
            val agg = c.aggregateGroupByPeriod(AggregateGroupByPeriodRequest(setOf(StepsRecord.COUNT_TOTAL),
                TimeRangeFilter.between(today.minusDays((days - 1).toLong()).atStartOfDay(), now.atZone(Z).toLocalDateTime()), Period.ofDays(1)))
            l("agregat HC pe zile: " + agg.joinToString(" ") { "${it.startTime.dayOfMonth}:${it.result[StepsRecord.COUNT_TOTAL] ?: 0}" })
        } catch (e: Exception) { l("agregat EROARE ${e.message}") }
    }

    // ---- puls
    data[HeartRateRecord::class]?.let { recs ->
        val hr = recs.filterIsInstance<HeartRateRecord>()
        l(); l("== PULS ==")
        for ((pkg, g) in hr.groupBy { it.metadata.dataOrigin.packageName }) {
            val s = g.flatMap { it.samples }.sortedBy { it.time }
            val perRec = g.map { it.samples.size }
            l("$pkg: ${s.size} mostre în ${g.size} înreg. (mostre/înreg p50=${pct(perRec.map { it.toLong() }, .5)} max=${perRec.maxOrNull()}); ultima ${s.lastOrNull()?.let { t(it.time) }}")
            val gapsDay = mutableListOf<Long>(); val gapsNight = mutableListOf<Long>()
            for (i in 1 until s.size) {
                val gap = Duration.between(s[i - 1].time, s[i].time).seconds
                if (gap <= 0 || gap > 3 * 3600) continue
                val h = s[i].time.atZone(Z).hour
                if (h in 9..20) gapsDay += gap else if (h in 1..5) gapsNight += gap
            }
            fun hist(g: List<Long>) = listOf(70L, 330, 630, 930, 1830, 3630, Long.MAX_VALUE).let { lim ->
                val names = listOf("≤1m", "≤5m", "≤10m", "≤15m", "≤30m", "≤60m", ">60m")
                var prev = 0L
                lim.mapIndexed { i, x -> val n = g.count { it in (prev + 1)..x }; prev = x; "${names[i]}:$n" }.joinToString(" ")
            }
            l("  pauze ziua 9–21 sec p50=${pct(gapsDay, .5)} p90=${pct(gapsDay, .9)} | ${hist(gapsDay)}")
            l("  pauze noaptea 1–6 sec p50=${pct(gapsNight, .5)} p90=${pct(gapsNight, .9)} | ${hist(gapsNight)}")
            val win = s.groupBy { day(it.time) }.toSortedMap()
            l("  ferestre 15 min cu puls / 96 (07–23 din 64): " + win.entries.joinToString(" ") { (d, l2) ->
                val w = l2.map { it.time.atZone(Z).let { z -> z.hour * 4 + z.minute / 15 } }.toSet()
                "${d.dayOfMonth}:${w.size}(${w.count { it in 28 until 92 }})"
            })
            l("  bpm min/med/max: ${s.minOfOrNull { it.beatsPerMinute }}/${if (s.isEmpty()) 0 else s.sumOf { it.beatsPerMinute } / s.size}/${s.maxOfOrNull { it.beatsPerMinute }}")
        }
    }

    // ---- somn
    data[SleepSessionRecord::class]?.let { recs ->
        l(); l("== SOMN ==")
        for (r in recs.filterIsInstance<SleepSessionRecord>()) {
            val tot = r.stages.groupBy { STAGE[it.stage] ?: "${it.stage}" }.mapValues { e -> e.value.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() } }
            l("${t(r.startTime)}→${hm(r.endTime)} ${Duration.between(r.startTime, r.endTime).toMinutes()}min ${r.metadata.dataOrigin.packageName} faze=${r.stages.size} $tot titlu=${r.title ?: "-"}")
            if (r.stages.isNotEmpty()) l("  faze: " + r.stages.take(40).joinToString(" ") { "${hm(it.startTime)}${STAGE[it.stage]?.take(3)}" } + if (r.stages.size > 40) " …" else "")
        }
    }

    // ---- antrenamente
    data[ExerciseSessionRecord::class]?.let { recs ->
        l(); l("== ANTRENAMENTE ==")
        val hr = data[HeartRateRecord::class]?.filterIsInstance<HeartRateRecord>()?.flatMap { it.samples } ?: emptyList()
        for (r in recs.filterIsInstance<ExerciseSessionRecord>()) {
            val inside = hr.filter { it.time >= r.startTime && it.time <= r.endTime }
            val route = when (val rr = r.exerciseRouteResult) {
                is ExerciseRouteResult.Data -> "traseu ${rr.exerciseRoute.route.size} pct"
                is ExerciseRouteResult.ConsentRequired -> "traseu: cere acord"
                else -> "fără traseu"
            }
            l("${t(r.startTime)}→${hm(r.endTime)} tip=${r.exerciseType} titlu=${r.title ?: "-"} ${r.metadata.dataOrigin.packageName} segm=${r.segments.size} tururi=${r.laps.size} $route puls ${inside.size} mostre${if (inside.isNotEmpty()) " med ${inside.sumOf { it.beatsPerMinute } / inside.size} max ${inside.maxOf { it.beatsPerMinute }}" else ""}")
        }
    }

    // ---- valori simple
    l(); l("== VALORI ==")
    data[RestingHeartRateRecord::class]?.filterIsInstance<RestingHeartRateRecord>()?.let { l("puls repaus: " + it.joinToString(" ") { r -> "${t(r.time)}=${r.beatsPerMinute}" }) }
    data[HeartRateVariabilityRmssdRecord::class]?.filterIsInstance<HeartRateVariabilityRmssdRecord>()?.let { l("HRV (${it.size}): " + it.takeLast(30).joinToString(" ") { r -> "${t(r.time)}=${r.heartRateVariabilityMillis.toInt()}" }) }
    data[OxygenSaturationRecord::class]?.filterIsInstance<OxygenSaturationRecord>()?.let { l("SpO2 (${it.size}): " + it.takeLast(15).joinToString(" ") { r -> "${t(r.time)}=${r.percentage.value.toInt()}" }) }
    data[DistanceRecord::class]?.filterIsInstance<DistanceRecord>()?.let { d -> l("distanță pe zile (m): " + d.groupBy { "${day(it.startTime).dayOfMonth}/${it.metadata.dataOrigin.packageName.takeLast(6)}" }.entries.joinToString(" ") { "${it.key}:${it.value.sumOf { r -> r.distance.inMeters }.toInt()}" }) }
    return sb.toString()
}

object BuildConfigInfo {
    fun version(ctx: Context): String = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }
}
