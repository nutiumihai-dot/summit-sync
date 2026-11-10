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
        Input(z, at(now), true, true, lastSync?.let { at(it) } ?: hr.maxOfOrNull { it.t }, hr, steps, sleeps, emptyList(), emptyList())

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
        // modul azi, Bluetooth oprit de 2 ore → roșu
        val stale = buildResult(input("2026-11-10T11:00", hr, listOf(sleep)), Request(LocalDate.parse("2026-11-10"), LocalDate.parse("2026-11-10"), azi = true), "x")
        assertEquals("rosu", stale.getString("verdict"))
    }

    @Test fun plimbareDetectata_inAfaraAntrenamentului() {
        val steps = (0 until 15).map { StepRec(at("2026-11-09T12:00").plusSeconds(it * 60L), at("2026-11-09T12:01").plusSeconds(it * 60L), if (it == 6) 20 else 100, true) }
        val inp = input("2026-11-10T08:00", hrEvery("2026-11-09T00:00", "2026-11-10T00:00"), steps = steps, lastSync = "2026-11-10T07:00")
        val c = computeDay(LocalDate.parse("2026-11-09"), inp, emptyList(), at("2026-11-10T07:00"))
        val w = c.json.getJSONArray("plimbari")
        assertEquals(1, w.length())
        assertEquals("12:00", w.getJSONObject(0).getString("start"))
        assertEquals(15, w.getJSONObject(0).getInt("min"))
    }
}
