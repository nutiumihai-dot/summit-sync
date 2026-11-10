package ro.summit.sync

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class CoreTest {
    private val z = ZoneId.of("Europe/Bucharest")
    private fun at(s: String): Instant = LocalDateTime.parse(s).atZone(z).toInstant()
    private fun st(a: String, b: String, t: Int) = Stage(at(a), at(b), t)

    /** Puls la fiecare 5 min între a și b. */
    private fun hrEvery(a: String, b: String, bpm: Int = 70, stepMin: Long = 5): List<Hr> {
        val out = mutableListOf<Hr>(); var t = at(a); val e = at(b)
        while (t < e) { out += Hr(t, bpm); t = t.plusSeconds(stepMin * 60) }
        return out
    }
    private fun input(now: String, hr: List<Hr>, sleeps: List<Sleep> = emptyList(), steps: List<StepRec> = emptyList(), lastSync: String? = null) =
        Input(z, at(now), true, true, lastSync?.let { at(it) } ?: hr.maxOfOrNull { it.t }, hr = hr, steps = steps, sleeps = sleeps, exercises = emptyList(), resting = emptyList())

    @Test fun snooze_treziriSiReveniri() {
        val s = Sleep(at("2026-11-09T23:40"), at("2026-11-10T07:22"), listOf(
            st("2026-11-09T23:40", "2026-11-09T23:58", 1),
            st("2026-11-09T23:58", "2026-11-10T02:00", 5),
            st("2026-11-10T02:00", "2026-11-10T02:10", 1),
            st("2026-11-10T02:10", "2026-11-10T06:45", 4),
            st("2026-11-10T06:45", "2026-11-10T06:50", 1),   // prima trezire din ultima oră
            st("2026-11-10T06:50", "2026-11-10T07:00", 4),
            st("2026-11-10T07:00", "2026-11-10T07:05", 1),
            st("2026-11-10T07:05", "2026-11-10T07:15", 6),
            st("2026-11-10T07:15", "2026-11-10T07:22", 1),
        ))
        val j = sleepSummary(s, z)
        assertEquals("23:58", j.getString("adormit"))
        assertEquals("06:45", j.getString("primaTrezire"))
        assertEquals(37, j.getInt("snoozeMin"))
        assertEquals(2, j.getInt("reveniri"))
        assertEquals(3, j.getInt("treziri"))   // 02:00, 06:45, 07:00
        assertEquals(462 - 18 - 10 - 5 - 5 - 7, j.getInt("min"))
        assertTrue(j.getInt("scor") in 1..100)
    }

    @Test fun somn_unireSesiuni_siZiuaTrezirii() {
        // trezit la 5, adormit iar la 5:50 → o singură noapte, cu 50 min treaz la mijloc
        val a = Sleep(at("2026-11-09T23:30"), at("2026-11-10T05:00"), listOf(st("2026-11-09T23:30", "2026-11-10T05:00", 4)))
        val b = Sleep(at("2026-11-10T05:50"), at("2026-11-10T08:00"), listOf(st("2026-11-10T05:50", "2026-11-10T08:00", 4)))
        val nap = Sleep(at("2026-11-10T15:00"), at("2026-11-10T15:40"))
        val m = mergeSleeps(listOf(b, nap, a))
        assertEquals(2, m.size)
        val d = sleepFor(LocalDate.parse("2026-11-10"), m, z)
        assertEquals(at("2026-11-09T23:30"), d.night!!.start)
        assertEquals(at("2026-11-10T08:00"), d.night!!.end)
        assertEquals(1, d.naps.size)
        assertEquals(50, sleepSummary(d.night!!, z).getInt("treaz"))
        // culcat la 4, trezit la 12 → tot pe ziua trezirii
        val late = mergeSleeps(listOf(Sleep(at("2026-11-11T04:00"), at("2026-11-11T12:00"))))
        assertNotNull(sleepFor(LocalDate.parse("2026-11-11"), late, z).night)
        assertNull(sleepFor(LocalDate.parse("2026-11-10"), late, z).night)
    }

    @Test fun ziParțială_pasiTelefonCompleteaza() {
        // brățara purtată 07:00–15:00, apoi scoasă; somn 00:00–07:00
        val hr = hrEvery("2026-11-09T07:00", "2026-11-09T15:00")
        val sleep = Sleep(at("2026-11-08T23:30"), at("2026-11-09T07:00"))
        val steps = listOf(
            StepRec(at("2026-11-09T10:00"), at("2026-11-09T11:00"), 1000, band = true),
            StepRec(at("2026-11-09T10:00"), at("2026-11-09T11:00"), 900, band = false),   // aceeași oră: nu se adună
            StepRec(at("2026-11-09T18:00"), at("2026-11-09T19:00"), 2000, band = false),  // oră fără brățară
        )
        val inp = input("2026-11-10T08:00", hr, listOf(sleep), steps, lastSync = "2026-11-10T07:50")
        val c = computeDay(LocalDate.parse("2026-11-09"), inp, mergeSleeps(inp.sleeps), at("2026-11-10T07:50"))
        assertEquals("partial", c.status)
        assertEquals(47, c.purtat)  // 8 h din 17 h treze
        val p = c.json.getJSONObject("pasi")
        assertEquals(3000, p.getLong("total"))
        assertEquals(1000, p.getLong("bratara"))
        assertEquals(2000, p.getLong("telefon"))
    }

    @Test fun schimbareaOrei_ziDe25h() {
        // 25 oct 2026: ora de vară se termină, ziua are 25 h
        val hr = hrEvery("2026-10-25T00:00", "2026-10-26T00:00")
        val inp = input("2026-10-26T09:00", hr, lastSync = "2026-10-26T08:00")
        val c = computeDay(LocalDate.parse("2026-10-25"), inp, emptyList(), at("2026-10-26T08:00"))
        assertEquals("complet", c.status)
        assertEquals(100, c.purtat)
        assertEquals("+03:00", c.json.getString("tz"))
        val (a, b) = dayBounds(LocalDate.parse("2026-10-25"), z)
        assertEquals(25, java.time.Duration.between(a, b).toHours())
    }

    @Test fun verificari_rosuGalbenVerde() {
        val hr = hrEvery("2026-11-09T07:00", "2026-11-10T09:00", stepMin = 5)
        val sleep = Sleep(at("2026-11-09T23:30"), at("2026-11-10T07:00"))
        // V2: ultima sincronizare ieri → roșu cu întrebare
        val old = input("2026-11-10T09:30", hr.filter { it.t < at("2026-11-09T15:20") }, listOf(sleep))
        val r = buildResult(old, Request(LocalDate.parse("2026-11-04"), LocalDate.parse("2026-11-10")), "k7Q2")
        assertEquals("rosu", r.getString("verdict"))
        assertEquals("V2", r.getJSONArray("intrebari").getJSONObject(0).getString("id"))
        assertFalse(r.has("zile"))
        // același import, cu „am scos brățara” → continuă
        val r2 = buildResult(old, Request(LocalDate.parse("2026-11-04"), LocalDate.parse("2026-11-10"), scos = true), "k7Q2")
        assertNotEquals("rosu", r2.getString("verdict"))
        // date complete, 9 nov purtat de la 7:00 → ziua 9 parțială (0–7 fără puls, fără somn) → galben
        val full = input("2026-11-10T09:30", hr, listOf(sleep))
        val r3 = buildResult(full, Request(LocalDate.parse("2026-11-04"), LocalDate.parse("2026-11-10")), "k7Q2")
        assertEquals("galben", r3.getString("verdict"))
        assertEquals("ok", r3.getJSONObject("verificari").getString("V3"))
        assertEquals("k7Q2", r3.getString("cod"))
        // modul azi: purtată toată dimineața, sincronizată acum 5 min → verde
        val azi = buildResult(input("2026-11-10T09:05", hr, listOf(sleep)), Request(LocalDate.parse("2026-11-10"), LocalDate.parse("2026-11-10"), azi = true), "x")
        assertEquals("verde", azi.getString("verdict"))
        // modul azi, Bluetooth oprit de 2 ore (Mi Fitness n-a mai scris) → roșu
        val stale = buildResult(input("2026-11-10T11:00", hr, listOf(sleep)), Request(LocalDate.parse("2026-11-10"), LocalDate.parse("2026-11-10"), azi = true), "x")
        assertEquals("rosu", stale.getString("verdict"))
    }

    @Test fun plimbareDetectata_peBlocuriDe30min() {
        val steps = listOf(
            StepRec(at("2026-11-09T11:30"), at("2026-11-09T11:59"), 300, true),
            StepRec(at("2026-11-09T12:00"), at("2026-11-09T12:29"), 1600, true),
            StepRec(at("2026-11-09T12:30"), at("2026-11-09T12:59"), 1100, true),
            StepRec(at("2026-11-09T18:00"), at("2026-11-09T18:29"), 2500, true),   // în antrenament → nu e plimbare
        )
        val ex = listOf(Exercise(at("2026-11-09T18:00"), at("2026-11-09T18:30"), 79, null, 2.0))
        val inp = input("2026-11-10T08:00", hrEvery("2026-11-09T00:00", "2026-11-10T00:00", stepMin = 10), steps = steps, lastSync = "2026-11-10T07:00").copy(exercises = ex)
        val c = computeDay(LocalDate.parse("2026-11-09"), inp, emptyList(), at("2026-11-10T07:00"))
        val w = c.json.getJSONArray("plimbari")
        assertEquals(1, w.length())
        assertEquals("12:00", w.getJSONObject(0).getString("start"))
        assertEquals(2700, w.getJSONObject(0).getLong("pasi"))
        assertEquals(25, w.getJSONObject(0).getInt("min"))
        assertEquals(1, c.json.getJSONArray("antrenamente").length())
        assertEquals("complet", c.status)   // puls la 10 min acoperă toate ferestrele de 15 min
    }

    @Test fun anomalie_pulsMareInRepaus() {
        val hr = hrEvery("2026-11-09T07:00", "2026-11-09T22:00", stepMin = 10).map { if (it.t >= at("2026-11-09T15:00") && it.t < at("2026-11-09T15:30")) Hr(it.t, 160) else it }
        val inp = input("2026-11-10T08:00", hr, lastSync = "2026-11-10T07:00")
        val a = computeDay(LocalDate.parse("2026-11-09"), inp, emptyList(), at("2026-11-10T07:00")).json.getJSONArray("anomalii")
        assertEquals(1, a.length())
        assertEquals("puls_mare", a.getJSONObject(0).getString("tip"))
        assertEquals("15:00", a.getJSONObject(0).getString("start"))
        assertTrue(a.getJSONObject(0).getJSONArray("detaliu").length() > 60)
    }

    @Test fun cerereImport_listaAlbaSiZile() {
        val today = LocalDate.parse("2026-11-10")
        fun q(m: Map<String, String>) = parseImport({ m[it] }, today)
        val ok = mapOf("cod" to "k7q2ab", "ret" to "https://nutiumihai-dot.github.io/summit-test/", "zile" to "2026-10-01..2026-11-12", "mod" to "azi")
        val r = q(ok)!!
        assertEquals(LocalDate.parse("2026-10-28"), r.req.from)   // max 14 zile
        assertEquals(today, r.req.to)
        assertTrue(r.req.azi)
        assertNull(q(ok + ("ret" to "https://evil.example/")))
        assertNull(q(ok + ("cod" to "AB!")))
    }

    @Test fun pasi_caInTestulDin10Nov() {
        // Mi Fitness: bloc 14:30 = max(brățară 500, telefon 944) = 944; sistemul (android) pe minut, 245 de pași 14:48–14:51
        val steps = listOf(
            StepRec(at("2026-11-10T14:30"), at("2026-11-10T14:59"), 944, band = true),
            StepRec(at("2026-11-10T14:48"), at("2026-11-10T14:49"), 89, band = false),
            StepRec(at("2026-11-10T14:49"), at("2026-11-10T14:51"), 155, band = false),
        )
        val hr = hrEvery("2026-11-10T14:00", "2026-11-10T15:30", stepMin = 10)
        val inp = input("2026-11-10T16:00", hr, steps = steps, lastSync = "2026-11-10T15:30")
        val p = computeDay(LocalDate.parse("2026-11-10"), inp, emptyList(), at("2026-11-10T15:30")).json.getJSONObject("pasi")
        assertEquals(944, p.getLong("total"))   // ora 14 purtată: brățara, fără să adune telefonul
        assertEquals(0, p.getLong("telefon"))
    }

    @Test fun plimbarePeMinut_dinTelefon() {
        val steps = (0 until 12).map { StepRec(at("2026-11-09T17:00").plusSeconds(it * 60L), at("2026-11-09T17:01").plusSeconds(it * 60L), 100, false) } +
            StepRec(at("2026-11-09T17:00"), at("2026-11-09T17:29"), 1300, true)
        val inp = input("2026-11-10T08:00", hrEvery("2026-11-09T00:00", "2026-11-10T00:00", stepMin = 10), steps = steps, lastSync = "2026-11-10T07:00")
        val w = computeDay(LocalDate.parse("2026-11-09"), inp, emptyList(), at("2026-11-10T07:00")).json.getJSONArray("plimbari")
        assertEquals(1, w.length())
        assertEquals(12, w.getJSONObject(0).getInt("min"))
        assertEquals(1200, w.getJSONObject(0).getLong("pasi"))
        assertFalse(w.getJSONObject(0).has("est"))
    }
}
