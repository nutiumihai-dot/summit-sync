package ro.summit.sync

import android.content.Context
import android.util.Base64
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/** Mi Fitness scrie în Health Connect ca acest pachet (aflat din diagnostic, 10 nov 2026). */
const val BAND_PKG = "com.xiaomi.wearable"

/** Unde se poate întoarce rezultatul. Orice altă adresă e refuzată. */
val RETURN_OK = listOf(
    "https://nutiumihai-dot.github.io/summit-test/",
    "https://nutiumihai-dot.github.io/drumul-spre-partie/",
)

val NEEDED: Set<String> = listOf(StepsRecord::class, HeartRateRecord::class, RestingHeartRateRecord::class,
    SleepSessionRecord::class, ExerciseSessionRecord::class, DistanceRecord::class).map { HealthPermission.getReadPermission(it) }.toSet()

data class ImportReq(val req: Request, val cod: String, val ret: String)

/** summitsync://import?zile=2026-11-04..2026-11-10&cod=k7q2&ret=…&mod=azi&scos=1&tinta=…&det=A~B,C~D */
fun parseImport(q: (String) -> String?, today: LocalDate): ImportReq? {
    val cod = q("cod")?.takeIf { Regex("^[a-z0-9]{4,16}$").matches(it) } ?: return null
    val ret = q("ret")?.takeIf { it in RETURN_OK } ?: return null
    val (a, b) = (q("zile") ?: "").split("..").let { if (it.size == 2) it[0] to it[1] else return null }
    var from = LocalDate.parse(a); val to = minOf(LocalDate.parse(b), today)
    if (from.isBefore(today.minusDays(13))) from = today.minusDays(13)
    if (from.isAfter(to)) return null
    val z = ZoneId.systemDefault()
    val det = (q("det") ?: "").split(",").filter { "~" in it }.take(5).mapNotNull {
        runCatching { val (x, y) = it.split("~"); LocalDateTime.parse(x).atZone(z).toInstant() to LocalDateTime.parse(y).atZone(z).toInstant() }.getOrNull()
    }.filter { (x, y) -> y > x && java.time.Duration.between(x, y).toHours() <= 6 }
    return ImportReq(Request(from, to, azi = q("mod") == "azi", scos = q("scos") == "1",
        tinta = q("tinta")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }, det = det), cod, ret)
}

suspend fun loadInput(ctx: Context, req: Request): Input {
    val z = ZoneId.systemDefault()
    val now = Instant.now()
    val c = HealthConnectClient.getOrCreate(ctx)
    val granted = c.permissionController.getGrantedPermissions()
    val permsOk = granted.containsAll(NEEDED)
    if (!permsOk) return Input(z, now, false, false, null, null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    val dayStart = req.from.atStartOfDay(z).toInstant()
    val hrFrom = minOf(dayStart.minusSeconds(12 * 3600), now.minusSeconds(72 * 3600))
    val sleepFrom = req.from.minusDays(8).atStartOfDay(z).toInstant()

    val hrRecs = readAll(c, HeartRateRecord::class, hrFrom, now).filterIsInstance<HeartRateRecord>().filter { it.metadata.dataOrigin.packageName == BAND_PKG }
    val hr = hrRecs.flatMap { r -> r.samples.map { Hr(it.time, it.beatsPerMinute.toInt()) } }.distinctBy { it.t }.sortedBy { it.t }
    val stepRecs = readAll(c, StepsRecord::class, minOf(dayStart, hrFrom), now).filterIsInstance<StepsRecord>()
    val steps = stepRecs.map { StepRec(it.startTime, it.endTime, it.count, it.metadata.dataOrigin.packageName == BAND_PKG) }
    val sleeps = readAll(c, SleepSessionRecord::class, sleepFrom, now).filterIsInstance<SleepSessionRecord>()
        .map { s -> Sleep(s.startTime, s.endTime, s.stages.map { Stage(it.startTime, it.endTime, it.stage) }) }
    val dist = readAll(c, DistanceRecord::class, dayStart, now).filterIsInstance<DistanceRecord>()
    val ex = readAll(c, ExerciseSessionRecord::class, dayStart, now).filterIsInstance<ExerciseSessionRecord>().map { e ->
        val km = dist.filter { it.metadata.dataOrigin.packageName == e.metadata.dataOrigin.packageName && it.startTime >= e.startTime && it.endTime <= e.endTime }
            .sumOf { it.distance.inMeters } / 1000.0
        Exercise(e.startTime, e.endTime, e.exerciseType, e.title, km.takeIf { it > 0 })
    }
    val resting = readAll(c, RestingHeartRateRecord::class, dayStart, now).filterIsInstance<RestingHeartRateRecord>()
        .filter { it.metadata.dataOrigin.packageName == BAND_PKG }.map { Resting(it.time, it.beatsPerMinute.toInt()) }

    // Doar pulsul: Mi Fitness scrie sub același pachet și pașii numărați de telefon (văzut pe 10 nov, Bluetooth oprit),
    // deci pașii nu dovedesc că brățara s-a sincronizat.
    val bandMod = hrRecs.maxOfOrNull { it.metadata.lastModifiedTime }
    return Input(z, now, true, bandMod != null && bandMod >= now.minusSeconds(72 * 3600), hr.lastOrNull()?.t, bandMod,
        hr, steps, sleeps, ex, resting)
}

/** JSON → deflate (fără antet) → base64url fără padding, pentru fragmentul #hc=. */
fun pack(json: String): String {
    val bo = ByteArrayOutputStream()
    DeflaterOutputStream(bo, Deflater(9, true)).use { it.write(json.toByteArray(Charsets.UTF_8)) }
    return Base64.encodeToString(bo.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
