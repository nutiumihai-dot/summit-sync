package ro.summit.sync

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Logica pură (fără Health Connect), testată pe JVM. Toate regulile din designul aprobat.

data class Hr(val t: Instant, val bpm: Int)
data class StepRec(val start: Instant, val end: Instant, val count: Long, val band: Boolean)
data class Stage(val start: Instant, val end: Instant, val type: Int)
data class Sleep(val start: Instant, val end: Instant, val stages: List<Stage> = emptyList())
data class Exercise(val start: Instant, val end: Instant, val type: Int, val title: String?, val km: Double?)
data class Resting(val t: Instant, val bpm: Int)

data class Input(
    val zone: ZoneId,
    val now: Instant,
    val permsOk: Boolean,
    val bandRecent: Boolean,          // V1: date de la brățară în ultimele 72 h
    val lastSync: Instant?,           // ora celei mai recente mostre de puls de la brățară
    val lastWrite: Instant? = lastSync, // când a scris Mi Fitness ultima dată în Health Connect
    val hr: List<Hr>,                 // doar brățara
    val steps: List<StepRec>,
    val sleeps: List<Sleep>,
    val exercises: List<Exercise>,
    val resting: List<Resting>,
)

data class Request(
    val from: LocalDate,
    val to: LocalDate,
    val azi: Boolean = false,         // modul de test „azi până acum”
    val scos: Boolean = false,        // răspuns la V2: „am scos brățara atunci”
    val tinta: LocalDate? = null,     // ziua pe care se dă verdictul (implicit ieri; azi în modul azi)
    val det: List<Pair<Instant, Instant>> = emptyList(),
)

object P {
    var WIN_MIN = 15L                 // fereastra de purtare
    var MERGE_GAP_MIN = 60L           // sesiuni de somn unite
    var NIGHT_MIN_H = 3L              // sub 3 h = somn de zi
    var HR_MAX = 184
    var WALK_BUCKET = 1000L           // pași într-un bloc de 30 min (Mi Fitness scrie pașii pe 30 min)
    var WALK_SPM_EST = 105L           // pași/min pentru durata estimată
    var AZI_FRESH_MIN = 30L           // modul azi: ultima sincronizare cel mult acum 30 min
}

private val AWAKE = setOf(1, 3, 7)
private const val DEEP = 5
private const val REM = 6
private const val LIGHT = 4

fun isAwake(s: Stage) = s.type in AWAKE
fun mins(a: Instant, b: Instant) = Duration.between(a, b).toMinutes()
private fun overlap(a0: Instant, a1: Instant, b0: Instant, b1: Instant): Long =
    max(0L, Duration.between(maxOf(a0, b0), minOf(a1, b1)).seconds)

/** Sesiunile de somn cu pauză sub 60 min se unesc; fazele se păstrează, pauza devine „treaz”. */
fun mergeSleeps(list: List<Sleep>): List<Sleep> {
    val s = list.sortedBy { it.start }
    val out = mutableListOf<Sleep>()
    for (x in s) {
        val last = out.lastOrNull()
        if (last != null && mins(last.end, x.start) < P.MERGE_GAP_MIN) {
            val gap = if (x.start > last.end && (last.stages.isNotEmpty() || x.stages.isNotEmpty())) listOf(Stage(last.end, x.start, 1)) else emptyList()
            out[out.size - 1] = Sleep(last.start, maxOf(last.end, x.end), last.stages + gap + x.stages)
        } else out += x
    }
    return out
}

data class DaySleep(val night: Sleep?, val naps: List<Sleep>)

/** Noaptea zilei D = cel mai lung bloc (≥3 h) terminat în D între 03:00 și 15:00. Restul blocurilor terminate în D = somn de zi. */
fun sleepFor(d: LocalDate, merged: List<Sleep>, z: ZoneId): DaySleep {
    val ending = merged.filter { it.end.atZone(z).toLocalDate() == d }
    val night = ending.filter {
        val h = it.end.atZone(z).toLocalTime()
        h.hour in 3..14 && mins(it.start, it.end) >= P.NIGHT_MIN_H * 60
    }.maxByOrNull { mins(it.start, it.end) }
    return DaySleep(night, ending.filter { it !== night })
}

private val HM = DateTimeFormatter.ofPattern("HH:mm")
fun hm(i: Instant, z: ZoneId): String = HM.format(i.atZone(z))
fun iso(i: Instant, z: ZoneId): String = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm").format(i.atZone(z))

/** Rezumatul unei nopți, cu snooze și scor. prevBeds = orele de culcare (min de la 12:00) din nopțile anterioare. */
fun sleepSummary(s: Sleep, z: ZoneId, prevBeds: List<Int> = emptyList()): JSONObject {
    val o = JSONObject()
    val total = mins(s.start, s.end)
    o.put("culcare", hm(s.start, z)).put("ridicare", hm(s.end, z))
    val st = s.stages.sortedBy { it.start }
    var asleep = total
    var treziri = 0
    var score = 0.0; var maxScore = 0.0
    if (st.isNotEmpty()) {
        fun sum(f: (Stage) -> Boolean) = st.filter(f).sumOf { mins(it.start, it.end) }
        val awake = sum(::isAwake)
        asleep = total - awake
        val firstSleep = st.firstOrNull { !isAwake(it) }
        val lastSleep = st.lastOrNull { !isAwake(it) }
        firstSleep?.let { o.put("adormit", hm(it.start, z)) }
        // treziri = segmente treaz între două segmente de somn
        val fi = st.indexOf(firstSleep); val li = st.indexOf(lastSleep)
        if (fi >= 0) for (i in fi..li) if (isAwake(st[i]) && (i == 0 || !isAwake(st[i - 1]))) treziri++
        // snooze: prima trezire din ultima oră înainte de ridicare
        val hourStart = s.end.minusSeconds(3600)
        // Mi Fitness nu trimite mereu fazele „treaz” (11 nov: niciuna); fără ele snooze-ul nu se poate deduce → vine din alarmă, în aplicație
        val firstWake = st.firstOrNull { isAwake(it) && it.end > hourStart && it.start < s.end }
        if (firstWake != null) {
            val wakeAt = maxOf(firstWake.start, hourStart)
            o.put("primaTrezire", hm(wakeAt, z)).put("snoozeMin", mins(wakeAt, s.end)).put("reveniri", st.count { !isAwake(it) && it.start >= wakeAt })
        }
        val deep = sum { it.type == DEEP }; val rem = sum { it.type == REM }
        val light = sum { it.type == LIGHT || it.type == 2 || it.type == 0 }
        o.put("profund", deep).put("rem", rem).put("usor", light).put("treaz", awake).put("treziri", treziri)
        // eficiență 20, profund+REM 20, treziri 10
        if (total > 0 && awake > 0) { score += 20 * clamp((asleep.toDouble() / total - 0.75) / 0.2); maxScore += 20 }
        if (asleep > 0 && (deep + rem) > 0) { score += 20 * clamp((deep + rem).toDouble() / asleep / 0.4); maxScore += 20 }
        score += 10 * clamp(1 - (treziri - 1).coerceAtLeast(0) / 5.0); maxScore += 10
    }
    o.put("min", asleep)
    // durată 40: 4 h = 0, 7,5 h = plin
    score += 40 * clamp((asleep - 240) / 210.0); maxScore += 40
    // regularitate 10: culcarea față de media nopților anterioare (min. 3)
    if (prevBeds.size >= 3) {
        val bed = bedMinutes(s.start, z)
        score += 10 * clamp(1 - abs(bed - prevBeds.average()) / 90.0); maxScore += 10
    }
    o.put("scor", (score * 100 / maxScore).roundToInt())
    return o
}

/** Ora de culcare ca minute de la 12:00 (ca 23:30 și 00:30 să fie apropiate). */
fun bedMinutes(i: Instant, z: ZoneId): Int { val t = i.atZone(z); return ((t.hour + 12) % 24) * 60 + t.minute }
private fun clamp(x: Double) = x.coerceIn(0.0, 1.0)

data class DayCalc(val json: JSONObject, val status: String, val purtat: Int)

fun dayBounds(d: LocalDate, z: ZoneId): Pair<Instant, Instant> = d.atStartOfDay(z).toInstant() to d.plusDays(1).atStartOfDay(z).toInstant()

/** Calculează o zi întreagă. cutoff = până unde avem date sigure (ultima sincronizare sau acum). */
fun computeDay(d: LocalDate, inp: Input, merged: List<Sleep>, cutoff: Instant, startAt: Instant? = null): DayCalc {
    val z = inp.zone
    val (d0, d1) = dayBounds(d, z)
    val end = minOf(d1, cutoff)
    val o = JSONObject()
    val sleepsToday = merged.filter { it.end > d0 && it.start < d1 }
    fun inSleep(t: Instant) = sleepsToday.any { t >= it.start && t < it.end }

    // ---- purtare, pe ferestre de 15 min (ziua întreagă, orele treze, până la cutoff)
    val w = Duration.ofMinutes(P.WIN_MIN)
    val hrDay = inp.hr.filter { it.t >= d0 && it.t < d1 }.sortedBy { it.t }
    val covered = HashSet<Long>()
    hrDay.forEach { covered += Duration.between(d0, it.t).toMinutes() / P.WIN_MIN }
    var awakeW = 0; var wornW = 0
    val wornHourKey = HashMap<Int, Boolean>() // index oră locală (0..24) → toate ferestrele purtate
    var t = d0; var idx = 0L
    while (t < d1) {
        val mid = t.plus(w.dividedBy(2))
        val hourIdx = Duration.between(d0, t).toHours().toInt()
        val past = t.plus(w) <= end && (startAt == null || t >= startAt)
        val sl = inSleep(mid)
        val cov = idx in covered
        if (past && !sl) { awakeW++; if (cov) wornW++ }
        val ok = past && (cov || (sl && sleepsToday.isNotEmpty()))
        wornHourKey[hourIdx] = (wornHourKey[hourIdx] ?: true) && ok
        t = t.plus(w); idx++
    }
    val purtat = if (awakeW == 0) 0 else (wornW * 100.0 / awakeW).roundToInt()
    val status = when { purtat >= 90 -> "complet"; purtat >= 30 -> "partial"; else -> "nepurtat" }
    o.put("status", status).put("purtat", purtat).put("tz", d0.atZone(z).offset.id)

    // ---- pași pe ore: oră purtată integral → brățara, altfel max(brățară, telefon)
    val hours = Duration.between(d0, d1).toHours().toInt()
    val band = LongArray(hours); val phone = LongArray(hours)
    for (r in inp.steps) {
        if (r.end <= d0 || r.start >= d1) continue
        val dur = Duration.between(r.start, r.end).seconds
        if (dur <= 0) { val h = Duration.between(d0, r.start).toHours().toInt(); if (h in 0 until hours) (if (r.band) band else phone)[h] += r.count; continue }
        for (h in 0 until hours) {
            val h0 = d0.plus(Duration.ofHours(h.toLong())); val h1 = h0.plus(Duration.ofHours(1))
            val ov = overlap(r.start, r.end, h0, h1)
            if (ov > 0) (if (r.band) band else phone)[h] += (r.count * ov + dur / 2) / dur
        }
    }
    var total = 0L; var fromBand = 0L; var fromPhone = 0L
    for (h in 0 until hours) {
        if (wornHourKey[h] == true || band[h] >= phone[h]) { total += band[h]; fromBand += band[h] }
        else { total += phone[h]; fromPhone += phone[h] }
    }
    o.put("pasi", JSONObject().put("total", total).put("bratara", fromBand).put("telefon", fromPhone))

    // ---- somn
    val ds = sleepFor(d, merged, z)
    if (ds.night != null) {
        val prev = (1..7).mapNotNull { k -> sleepFor(d.minusDays(k.toLong()), merged, z).night?.let { bedMinutes(it.start, z) } }
        o.put("somn", sleepSummary(ds.night, z, prev).put("status", "ok"))
    }
    if (ds.naps.isNotEmpty()) o.put("somnZi", JSONArray(ds.naps.map { JSONObject().put("start", hm(it.start, z)).put("min", mins(it.start, it.end)) }))

    // ---- puls
    val puls = JSONObject()
    val rest = inp.resting.filter { it.t >= d0 && it.t < d1 }.maxByOrNull { it.t }?.bpm
        ?: ds.night?.let { n -> lowestMean(inp.hr.filter { it.t >= n.start && it.t < n.end }, 30) }
    rest?.let { puls.put("repaus", it) }
    if (hrDay.isNotEmpty()) puls.put("mediu", hrDay.sumOf { it.bpm } / hrDay.size).put("maxim", hrDay.maxOf { it.bpm })
    if (puls.length() > 0) o.put("puls", puls)

    // ---- antrenamente
    val ex = inp.exercises.filter { it.start >= d0 && it.start < d1 }.sortedBy { it.start }
    o.put("antrenamente", JSONArray(ex.map { e ->
        val s = inp.hr.filter { it.t >= e.start && it.t <= e.end }.sortedBy { it.t }
        val j = JSONObject().put("tip", exName(e.type)).put("start", hm(e.start, z)).put("min", mins(e.start, e.end))
        e.title?.let { j.put("titlu", it) }
        e.km?.let { j.put("km", (it * 100).roundToInt() / 100.0) }
        if (s.isNotEmpty()) {
            j.put("pulsMediu", s.sumOf { it.bpm } / s.size).put("pulsMaxim", s.maxOf { it.bpm })
            j.put("zone", zones(s))
        }
        j
    }))

    // ---- minute (pași + puls) pentru plimbări și anomalii
    val minute = minuteSteps(inp.steps, d0, d1)
    // plimbări: pe minut din pedometrul telefonului (când telefonul a fost la el); altfel din blocurile Mi Fitness de 30 min
    val phoneMin = minuteSteps(inp.steps.filter { !it.band }, d0, d1)
    val exact = walksMinute(phoneMin, d0, ex)
    val est = walks(inp.steps.filter { it.band && it.start >= d0 && it.start < d1 }, ex).filter { (a, b, _) -> exact.none { overlap(it.first, it.second, a, b) > 0 } }
    val wl = exact.map { (a, b) -> Triple(a, b, stepsBetween(phoneMin, d0, a, b)) to false } + est.map { it to true }
    o.put("plimbari", JSONArray(wl.sortedBy { it.first.first }.map { (w, isEst) ->
        val (a, b, n) = w
        val s = inp.hr.filter { it.t >= a && it.t < b }
        JSONObject().put("start", hm(a, z)).put("min", if (isEst) (n / P.WALK_SPM_EST).toInt() else mins(a, b).toInt()).put("pasi", n)
            .also { if (isEst) it.put("est", true); if (s.isNotEmpty()) it.put("pulsMediu", s.sumOf { x -> x.bpm } / s.size) }
    }))
    o.put("anomalii", JSONArray(anomalies(inp, minute, d0, d1, ex, z)))
    return DayCalc(o, status, purtat)
}

fun lowestMean(s: List<Hr>, windowMin: Long): Int? {
    val x = s.sortedBy { it.t }
    var best: Double? = null
    for (i in x.indices) {
        val until = x[i].t.plus(Duration.ofMinutes(windowMin))
        val win = x.subList(i, x.size).takeWhile { it.t < until }
        if (win.size >= 3 && mins(win.first().t, win.last().t) >= windowMin / 2) {
            val m = win.sumOf { it.bpm }.toDouble() / win.size
            if (best == null || m < best) best = m
        }
    }
    return best?.roundToInt()
}

/** Minute pe zone: usor < 60%, moderat 60–75%, intens > 75% din pulsul maxim. Fiecare mostră valorează până la următoarea (max 2 min). */
fun zones(s: List<Hr>): JSONObject {
    var u = 0L; var m = 0L; var i = 0L
    for (k in s.indices) {
        val sec = if (k + 1 < s.size) min(120L, Duration.between(s[k].t, s[k + 1].t).seconds) else 60L
        val f = s[k].bpm.toDouble() / P.HR_MAX
        when { f < 0.60 -> u += sec; f <= 0.75 -> m += sec; else -> i += sec }
    }
    return JSONObject().put("usor", u / 60).put("moderat", m / 60).put("intens", i / 60)
}

/** Pașii pe minut ai zilei (brățară; unde brățara lipsește, telefonul). */
fun minuteSteps(steps: List<StepRec>, d0: Instant, d1: Instant): LongArray {
    val n = Duration.between(d0, d1).toMinutes().toInt()
    val b = LongArray(n); val p = LongArray(n)
    for (r in steps) {
        if (r.end <= d0 || r.start >= d1) continue
        val dur = max(1L, Duration.between(r.start, r.end).seconds)
        val m0 = max(0, Duration.between(d0, r.start).toMinutes().toInt())
        val m1 = min(n - 1, Duration.between(d0, r.end.minusMillis(1)).toMinutes().toInt())
        for (k in m0..m1) {
            val a = d0.plusSeconds(k * 60L)
            val ov = if (r.start == r.end) dur else overlap(r.start, r.end, a, a.plusSeconds(60))
            (if (r.band) b else p)[k] += r.count * ov / dur
        }
    }
    return LongArray(n) { if (b[it] > 0) b[it] else p[it] }
}

fun stepsBetween(m: LongArray, d0: Instant, a: Instant, b: Instant): Long {
    val i0 = mins(d0, a).toInt().coerceIn(0, m.size); val i1 = mins(d0, b).toInt().coerceIn(0, m.size)
    return (i0 until i1).sumOf { m[it] }
}

/** Plimbări: blocuri de pași ale brățării cu ≥1000 pași / 30 min, unite dacă sunt alăturate, în afara antrenamentelor.
 *  Mi Fitness scrie pașii pe 30 min, deci durata e estimată din pași (≈105 pași/min). */
fun walks(band: List<StepRec>, ex: List<Exercise>): List<Triple<Instant, Instant, Long>> {
    val out = mutableListOf<Triple<Instant, Instant, Long>>()
    var cur: Triple<Instant, Instant, Long>? = null
    for (r in band.sortedBy { it.start }) {
        val big = r.count >= P.WALK_BUCKET && ex.none { overlap(it.start, it.end, r.start, r.end) > 0 }
        if (!big) { cur?.let { out += it }; cur = null; continue }
        cur = if (cur != null && mins(cur.second, r.start) <= 1) Triple(cur.first, r.end, cur.third + r.count) else { cur?.let { out += it }; Triple(r.start, r.end, r.count) }
    }
    cur?.let { out += it }
    return out
}

/** Plimbări pe minut: min. 10 min la peste 80 pași/min, pauze sub 2 min, în afara antrenamentelor. */
fun walksMinute(m: LongArray, d0: Instant, ex: List<Exercise>): List<Pair<Instant, Instant>> {
    val out = mutableListOf<Pair<Instant, Instant>>()
    var start = -1; var lastGood = -1
    fun close() {
        if (start >= 0 && lastGood - start + 1 >= 10) {
            val a = d0.plusSeconds(start * 60L); val b = d0.plusSeconds((lastGood + 1) * 60L)
            if (ex.none { overlap(it.start, it.end, a, b) > 0 }) out += a to b
        }
        start = -1
    }
    for (k in m.indices) {
        if (m[k] >= 80) { if (start < 0) start = k; lastGood = k } else if (start >= 0 && k - lastGood > 2) close()
    }
    close()
    return out
}

/** Anomalii: puls ≥ 150 ≥3 min în afara antrenamentelor; puls < 38 treaz ≥3 min; peste 220 pași/min. Max 3 pe zi, cu detaliu pe minut ±30 min. */
fun anomalies(inp: Input, m: LongArray, d0: Instant, d1: Instant, ex: List<Exercise>, z: ZoneId): List<JSONObject> {
    val hs = inp.hr.filter { it.t >= d0 && it.t < d1 }.sortedBy { it.t }
    val found = mutableListOf<Triple<String, Int, Int>>()
    fun outsideEx(t: Instant) = ex.none { t >= it.start && t <= it.end }
    // ≥2 mostre consecutive (pauză ≤ 15 min) care îndeplinesc condiția
    fun scan(tip: String, f: (Hr) -> Boolean) {
        var run = mutableListOf<Hr>()
        fun close() { if (run.size >= 2) found += Triple(tip, mins(d0, run.first().t).toInt(), mins(d0, run.last().t).toInt()); run = mutableListOf() }
        for (h in hs) {
            if (f(h)) { if (run.isNotEmpty() && mins(run.last().t, h.t) > 15) close(); run += h } else close()
        }
        close()
    }
    scan("puls_mare") { it.bpm >= 150 && outsideEx(it.t) }
    scan("puls_mic") { it.bpm < 38 }
    for (k in m.indices) if (m[k] > 220 && found.none { it.first == "pasi_mari" && k - it.third <= 2 }) found += Triple("pasi_mari", k, k)
    return found.take(3).map { (tip, a, b) ->
        JSONObject().put("tip", tip).put("start", hm(d0.plusSeconds(a * 60L), z)).put("min", b - a + 1)
            .put("detaliu", detail(inp, d0.plusSeconds((a - 30) * 60L), d0.plusSeconds((b + 31) * 60L), z))
    }
}

/** Detaliu pe minut: [„HH:mm”, puls mediu sau null, pași]. Valori rezumate, nu mostre brute. */
fun detail(inp: Input, a: Instant, b: Instant, z: ZoneId): JSONArray {
    val rows = JSONArray()
    val m = minuteSteps(inp.steps, a, b)
    val hr = inp.hr.filter { it.t >= a && it.t < b }.groupBy { mins(a, it.t).toInt() }
    for (k in m.indices) {
        val bpm = hr[k]?.map { it.bpm }?.average()?.roundToInt()
        rows.put(JSONArray().put(hm(a.plusSeconds(k * 60L), z)).put(bpm ?: JSONObject.NULL).put(m[k]))
    }
    return rows
}

fun exName(t: Int): String = when (t) {
    79 -> "mers"; 56 -> "alergare"; 57 -> "banda"; 8 -> "bicicleta"; 9 -> "bicicleta_fixa"; 37 -> "drumetie"
    70 -> "forta"; 81 -> "forta"; 25 -> "eliptica"; 74 -> "inot"; 73 -> "inot"; 48 -> "yoga"; 61 -> "scari"; 0 -> "altul"
    else -> "tip$t"
}

/** Verificarea V0–V4 și rezultatul complet, gata de pus în fragmentul URL-ului. */
fun buildResult(inp: Input, req: Request, cod: String): JSONObject {
    val z = inp.zone
    val today = inp.now.atZone(z).toLocalDate()
    val res = JSONObject().put("v", 1).put("cod", cod).put("gen", iso(inp.now, z)).put("mod", if (req.azi) "azi" else "zile")
    val checks = JSONObject(); val q = JSONArray()
    fun stop(id: String, msg: String, verdict: String = "rosu"): JSONObject {
        checks.put(id, msg); return res.put("verdict", verdict).put("verificari", checks).put("intrebari", q)
    }
    if (!inp.permsOk) return stop("V0", "Lipsesc permisiuni Health Connect.")
    checks.put("V0", "ok")
    if (!inp.bandRecent || inp.lastSync == null) return stop("V1", "Mi Fitness nu mai trimite în Health Connect. Verifică legătura din Mi Fitness → Third Party.")
    checks.put("V1", "ok")
    val ls = inp.lastSync
    res.put("sincronizatPana", iso(ls, z))
    // modul azi: Mi Fitness a scris în ultimele 30 min (Bluetooth pornit); altfel ultima mostră e de azi
    val v2ok = if (req.azi) inp.lastWrite != null && mins(inp.lastWrite, inp.now) <= P.AZI_FRESH_MIN else ls >= today.atStartOfDay(z).toInstant()
    if (!v2ok && !req.scos) {
        q.put(JSONObject().put("id", "V2").put("text", "Ultimele date de la brățară: ${if (ls.atZone(z).toLocalDate() == today) "azi" else ls.atZone(z).toLocalDate().toString()} ${hm(ls, z)}. Sincronizează brățara în Mi Fitness sau spune că ai scos-o atunci.")
            .put("butoane", JSONArray().put("reincearca").put("scos")))
        return stop("V2", "Ultima sincronizare: ${iso(ls, z)}")
    }
    checks.put("V2", if (v2ok) "ok" else "scoasă după ${iso(ls, z)}")

    val cutoff = if (req.scos) inp.now else minOf(inp.now, ls)
    val merged = mergeSleeps(inp.sleeps)
    val zile = JSONObject()
    val status = HashMap<LocalDate, String>()
    var d = req.from
    while (!d.isAfter(req.to)) {
        // modul azi: purtarea se socotește de la trezire (sau de la prima mostră de azi)
        val startAt = if (req.azi && d == today) sleepFor(today, merged, z).night?.end
            ?: inp.hr.filter { it.t >= today.atStartOfDay(z).toInstant() }.minOfOrNull { it.t } else null
        val c = computeDay(d, inp, merged, cutoff, startAt)
        // zi fără nicio dată (nici pași, nici puls, nici somn, nici antrenament): nu se scrie, ca să nu pară „0 pași”
        val empty = c.json.getJSONObject("pasi").getLong("total") == 0L && !c.json.has("puls") && !c.json.has("somn") &&
            !c.json.has("somnZi") && c.json.getJSONArray("antrenamente").length() == 0
        if (empty) status[d] = "fara_date"
        else { c.json.put("sincronizatPana", iso(ls, z)); zile.put(d.toString(), c.json); status[d] = c.status }
        d = d.plusDays(1)
    }
    res.put("zile", zile)

    // V3: somnul de azi-noapte (doar dacă azi e în cerere și nu e modul azi)
    var v3 = "nu se aplică"
    if (!req.azi && !req.to.isBefore(today)) {
        val night = sleepFor(today, merged, z).night
        val (d0, _) = dayBounds(today, z)
        v3 = when {
            night != null && mins(night.end, ls) >= 10 -> "ok"
            night != null -> "procesare"
            inp.hr.any { it.t >= d0 && it.t < d0.plusSeconds(6 * 3600) } -> "procesare"
            else -> "lipsa"
        }
        val day = zile.optJSONObject(today.toString())
        if (v3 == "procesare") {
            q.put(JSONObject().put("id", "V3").put("text", "Mi Fitness încă procesează somnul. Reîncearcă în 15 minute.").put("butoane", JSONArray().put("reincearca")))
            day?.put("somn", JSONObject().put("status", "procesare"))
        } else if (v3 == "lipsa") {
            q.put(JSONObject().put("id", "V3").put("text", "Brățara nu a înregistrat somnul de azi-noapte. Poți trece de mână ora de culcare și de trezire.").put("butoane", JSONArray().put("manual")))
            day?.put("somn", JSONObject().put("status", "lipsa"))
        }
    }
    checks.put("V3", v3)
    val tinta = req.tinta ?: if (req.azi) today else today.minusDays(1)
    val st = status[tinta]
    checks.put("V4", "$tinta: ${st ?: "în afara cererii"}")
    val verdict = if ((st == null || st == "complet") && (v3 == "ok" || v3 == "nu se aplică")) "verde" else "galben"
    res.put("tinta", tinta.toString())
    if (req.det.isNotEmpty()) res.put("detalii", JSONArray(req.det.map { (a, b) -> JSONObject().put("start", iso(a, z)).put("end", iso(b, z)).put("minute", detail(inp, a, b, z)) }))
    return res.put("verdict", verdict).put("verificari", checks).put("intrebari", q)
}
