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
        assertFalse(r3.getJSONObject("zile").has("2026-11-05"))   // zi fără date: nu se scrie
        val r4 = buildResult(full, Request(LocalDate.parse("2026-11-04"), LocalDate.parse("2026-11-10"), tinta = LocalDate.parse("2026-11-05")), "k7Q2")
        assertEquals("galben", r4.getString("verdict"))
        assertTrue(r4.getJSONObject("verificari").getString("V4").contains("fara_date"))
        // modul azi: purtată toată dimineața, sincronizată acum 5 min → verde
        val azi = buildResult(input("2026-11-10T09:05", hr, listOf(sleep)), Request(LocalDate.parse("2026-11-10"), LocalDate.parse("2026-11-10"), azi = true), "x")
        assertEquals("verde", azi.getString("verdict"))
        // modul azi, Bluetooth oprit de 2 ore (Mi Fitness n-a mai scris) → roșu
        val stale = buildResult(input("2026-11-10T11:00", hr, listOf(sleep)), Request(LocalDate.parse("2026-11-10"), LocalDate.parse("2026-11-10"), azi = true), "x")
        assertEquals("rosu", stale.getString("verdict"))
    }

    @Test fun farăPuls_cuSpO2_importăCuAvertisment() {
        // ca pe 11–12 nov: pulsul continuu se oprește pe 11 la 10:30, SpO2 și somnul continuă
        val hr = hrEvery("2026-11-10T07:00", "2026-11-11T10:30")
        val spo2 = mutableListOf<Instant>(); var t = at("2026-11-10T07:00")
        while (t < at("2026-11-12T10:41")) { spo2 += t; t = t.plusSeconds(600) }
        val sleep = Sleep(at("2026-11-11T23:30"), at("2026-11-12T08:30"))
        val steps = listOf(StepRec(at("2026-11-11T17:00"), at("2026-11-11T17:30"), 900, band = true))
        val inp = Input(z, at("2026-11-12T10:45"), true, true, spo2.last(), hr = hr, steps = steps, sleeps = listOf(sleep),
            exercises = emptyList(), resting = emptyList(), spo2 = spo2)
        val r = buildResult(inp, Request(LocalDate.parse("2026-11-10"), LocalDate.parse("2026-11-12")), "k7q2")
        assertEquals("galben", r.getString("verdict"))
        assertEquals(1, r.getJSONArray("avertismente").length())
        assertTrue(r.getJSONObject("verificari").getString("V5").startsWith("fără puls"))
        assertEquals("ok", r.getJSONObject("verificari").getString("V2"))
        val d11 = r.getJSONObject("zile").getJSONObject("2026-11-11")
        assertEquals("complet", d11.getString("status"))          // purtată după SpO2
        assertEquals("puls", d11.getJSONArray("lipsa").getString(0))
        assertEquals(900, d11.getJSONObject("pasi").getLong("bratara"))
        val d10 = r.getJSONObject("zile").getJSONObject("2026-11-10")
        assertFalse(d10.has("lipsa"))
        assertEquals("ok", r.getJSONObject("zile").getJSONObject("2026-11-12").getJSONObject("somn").getString("status"))
        // cu puls complet → fără avertisment
        val ok = buildResult(input("2026-11-10T09:30", hrEvery("2026-11-09T07:00", "2026-11-10T09:00")), Request(LocalDate.parse("2026-11-09"), LocalDate.parse("2026-11-10")), "x")
        assertEquals(0, ok.getJSONArray("avertismente").length())
        assertEquals("ok", ok.getJSONObject("verificari").getString("V5"))
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

    @Test fun somnFaraFazeTreaz_caPe11Nov() {
        val s = Sleep(at("2026-11-11T00:24"), at("2026-11-11T09:13"), listOf(
            st("2026-11-11T00:24", "2026-11-11T03:00", 4), st("2026-11-11T03:00", "2026-11-11T05:34", 5),
            st("2026-11-11T05:34", "2026-11-11T07:51", 6), st("2026-11-11T07:51", "2026-11-11T09:13", 4)))
        val j = sleepSummary(s, z)
        assertEquals(529, j.getInt("min"))
        assertFalse(j.has("snoozeMin"))      // necunoscut, nu 0
        assertEquals(0, j.getInt("treziri"))
        assertTrue(j.getInt("scor") in 1..100)
    }

    @Test fun alarme_primaSnoozeuriOprita_faraAlarmeleDeZi() {
        val tm = at("2026-11-13T07:30")
        val ev = listOf(
            AlarmEv(at("2026-11-11T22:00"), at("2026-11-12T07:30")),
            AlarmEv(at("2026-11-12T07:30").plusSeconds(4), tm),            // a sunat, ceasul a mutat-o pe mâine
            AlarmEv(at("2026-11-12T07:31"), at("2026-11-12T07:41")),       // snooze
            AlarmEv(at("2026-11-12T07:41").plusSeconds(3), tm),
            AlarmEv(at("2026-11-12T07:42"), at("2026-11-12T07:52")),       // snooze
            AlarmEv(at("2026-11-12T07:52").plusSeconds(10), tm),           // oprită
            AlarmEv(at("2026-11-12T13:00"), at("2026-11-12T14:00")),       // alarmă de zi
            AlarmEv(at("2026-11-12T14:00").plusSeconds(5), tm),
        )
        val night = Sleep(at("2026-11-11T23:50"), at("2026-11-12T08:10"))
        val inp = input("2026-11-12T09:00", hrEvery("2026-11-11T20:00", "2026-11-12T09:00", stepMin = 10), listOf(night)).copy(alarms = ev)
        val s = computeDay(LocalDate.parse("2026-11-12"), inp, mergeSleeps(inp.sleeps), at("2026-11-12T08:55")).json.getJSONObject("somn")
        assertEquals("07:30", s.getString("alarma"))
        assertEquals(3, s.getInt("alarmeSunat"))
        assertEquals("07:52", s.getString("alarmaOprita"))
        assertEquals(40, s.getInt("snoozeMin"))
        // alarmă ștearsă înainte să sune: nu contează
        assertNull(nightAlarms(listOf(AlarmEv(at("2026-11-12T06:00"), at("2026-11-12T07:30")), AlarmEv(at("2026-11-12T06:10"), tm)), night.start, night.end))
    }

    // ---- detaliu (0.13)

    @Test fun detaliu_pulsPe5Minute() {
        val d0 = at("2026-11-09T00:00")
        val hr = listOf(Hr(at("2026-11-09T07:00"), 60), Hr(at("2026-11-09T07:02"), 64), Hr(at("2026-11-09T07:04").plusSeconds(59), 71),
            Hr(at("2026-11-09T07:05"), 90), Hr(at("2026-11-09T23:59").plusSeconds(30), 55))
        val p = pulse5(hr, d0)
        assertEquals(3, p.length())
        assertEquals("[420,60,65,71]", p.getJSONArray(0).toString())   // medie 65 = round(195/3)
        assertEquals("[425,90,90,90]", p.getJSONArray(1).toString())
        assertEquals(1435, p.getJSONArray(2).getInt(0))
        // înainte de 00:00 → minute negative, rotunjite în jos
        assertEquals(-1, minFrom(d0, at("2026-11-08T23:59").plusSeconds(30)))
        assertEquals(-5, pulse5(listOf(Hr(at("2026-11-08T23:57"), 50)), d0).getJSONArray(0).getInt(0))
    }

    @Test fun detaliu_fazeleSomnului() {
        assertEquals(listOf("p", "r", "u", "t", "t", "t", "s", "s"), listOf(5, 6, 4, 1, 3, 7, 2, 0).map { stageCode(it) })
        val d0 = at("2026-11-10T00:00")
        val s = Sleep(at("2026-11-09T23:40"), at("2026-11-10T07:00"), listOf(
            st("2026-11-09T23:40", "2026-11-10T00:10", 4),
            st("2026-11-10T00:10", "2026-11-10T01:00", 4),    // același cod, alăturat → unit
            st("2026-11-10T01:00", "2026-11-10T02:30", 5),
            st("2026-11-10T02:30", "2026-11-10T02:40", 7),
            st("2026-11-10T02:40", "2026-11-10T07:00", 6)))
        assertEquals("[[-20,60,\"u\"],[60,150,\"p\"],[150,160,\"t\"],[160,420,\"r\"]]", sleepSegments(listOf(s), d0).toString())
        // fără faze: un singur segment „s”; somnul de zi: mai multe sesiuni în aceeași listă
        val naps = listOf(Sleep(at("2026-11-10T15:00"), at("2026-11-10T15:40")), Sleep(at("2026-11-10T13:00"), at("2026-11-10T13:20")))
        assertEquals("[[780,800,\"s\"],[900,940,\"s\"]]", sleepSegments(naps, d0).toString())
    }

    @Test fun detaliu_pasiPe30Min_sumaEgalaCuTotalul() {
        // pe ore: brățara în ora 10, telefonul în ora 18; pe 30 min se împarte după cât a căzut în fiecare jumătate
        val hourVal = LongArray(24).also { it[10] = 1000; it[18] = 2000 }
        val useBand = BooleanArray(24) { it != 18 }
        val hb = LongArray(48).also { it[20] = 333; it[21] = 668 }   // rotunjire pe 30 min: 1001 ≠ 1000
        val hp = LongArray(48).also { it[36] = 1500; it[37] = 500; it[20] = 450; it[21] = 450 }
        val b = halfHourSteps(hourVal, useBand, hb, hp)
        assertEquals(48, b.size)
        assertEquals(333, b[20]); assertEquals(667, b[21])
        assertEquals(1500, b[36]); assertEquals(500, b[37])
        assertEquals(3000, b.sum())
        // aceeași înregistrare de o oră, împărțită pe două jumătăți
        val (bb, _) = stepBins(listOf(StepRec(at("2026-11-09T10:00"), at("2026-11-09T11:00"), 1000, true)), at("2026-11-09T00:00"), at("2026-11-10T00:00"), 30)
        assertEquals(500, bb[20]); assertEquals(500, bb[21])
    }

    @Test fun detaliu_inRezultat() {
        // ziua parțială: brățara 7–15, telefonul seara; plus antrenament cu puls și SpO2
        val hr = hrEvery("2026-11-09T07:00", "2026-11-09T15:00", stepMin = 1)
        val sleep = Sleep(at("2026-11-08T23:30"), at("2026-11-09T07:00"), listOf(st("2026-11-08T23:30", "2026-11-09T03:00", 4), st("2026-11-09T03:00", "2026-11-09T07:00", 5)))
        val steps = listOf(
            StepRec(at("2026-11-09T10:00"), at("2026-11-09T11:00"), 1001, band = true),
            StepRec(at("2026-11-09T10:00"), at("2026-11-09T11:00"), 900, band = false),
            StepRec(at("2026-11-09T18:15"), at("2026-11-09T18:45"), 2000, band = false),
            StepRec(at("2026-11-09T14:30"), at("2026-11-09T14:59"), 944, band = true),
        )
        val ex = listOf(Exercise(at("2026-11-09T12:00"), at("2026-11-09T12:10"), 70, null, null), Exercise(at("2026-11-09T20:00"), at("2026-11-09T20:30"), 79, null, null))
        val spo2 = listOf(Spo2(at("2026-11-09T03:10"), 96), Spo2(at("2026-11-09T03:20"), 94))
        val inp = input("2026-11-10T08:00", hr, listOf(sleep), steps, lastSync = "2026-11-10T07:50").copy(exercises = ex, spo2Pct = spo2)
        val r = buildResult(inp, Request(LocalDate.parse("2026-11-08"), LocalDate.parse("2026-11-09"), scos = true), "k7q2")
        val zi = r.getJSONObject("zile").getJSONObject("2026-11-09")
        val det = r.getJSONObject("detaliu").getJSONObject("2026-11-09")
        assertEquals(r.getJSONObject("zile").length(), r.getJSONObject("detaliu").length())
        val pasi = det.getJSONArray("pasi")
        assertEquals(48, pasi.length())
        assertEquals(zi.getJSONObject("pasi").getLong("total"), (0 until 48).sumOf { pasi.getLong(it) })
        assertEquals(944, pasi.getLong(29))
        assertEquals(1001, pasi.getLong(20) + pasi.getLong(21))      // ora 10: brățara (purtată), nu telefonul
        assertEquals(1000, pasi.getLong(36)); assertEquals(1000, pasi.getLong(37))   // telefonul 18:15–18:45, jumătate în fiecare bloc
        assertEquals(96, det.getJSONArray("puls").length())          // 8 h × 12 găleți
        assertEquals("[[190,96],[200,94]]", det.getJSONArray("spo2").toString())
        assertEquals("[[-30,180,\"u\"],[180,420,\"p\"]]", det.getJSONArray("somn").toString())
        assertFalse(det.has("somnZi"))
        val antr = det.getJSONArray("antr")
        assertEquals(zi.getJSONArray("antrenamente").length(), antr.length())
        assertEquals(11, antr.getJSONArray(0).length())               // 12:00..12:10 inclusiv, ca pulsMediu
        assertEquals("[720,70]", antr.getJSONArray(0).getJSONArray(0).toString())
        assertEquals(0, antr.getJSONArray(1).length())               // fără puls
        // fără SpO2 permis → fără cheie
        val r2 = buildResult(inp.copy(spo2Pct = emptyList()), Request(LocalDate.parse("2026-11-09"), LocalDate.parse("2026-11-09"), scos = true), "k7q2")
        assertFalse(r2.getJSONObject("detaliu").getJSONObject("2026-11-09").has("spo2"))
    }

    @Test fun detaliu_marimea14Zile() {
        // cel mai rău caz realist: puls la fiecare minut 14 zile, SpO2 la 10 min, noapte cu faze la 20 min, pași pe 30 min
        val rnd = java.util.Random(7)
        val from = LocalDate.parse("2026-10-29"); val to = LocalDate.parse("2026-11-11")
        val hr = mutableListOf<Hr>(); val spo2 = mutableListOf<Spo2>(); val sleeps = mutableListOf<Sleep>(); val steps = mutableListOf<StepRec>()
        var t = at("2026-10-28T20:00")
        while (t < at("2026-11-12T08:00")) {
            hr += Hr(t, 55 + rnd.nextInt(60)); if (rnd.nextInt(10) == 0) spo2 += Spo2(t, 92 + rnd.nextInt(7))
            t = t.plusSeconds(60)
        }
        var d = from
        while (!d.isAfter(to)) {
            val s0 = d.atStartOfDay(z).toInstant().minusSeconds(3600); val stages = (0 until 24).map { k -> Stage(s0.plusSeconds(k * 1200L), s0.plusSeconds((k + 1) * 1200L), listOf(4, 5, 6, 1)[rnd.nextInt(4)]) }
            sleeps += Sleep(s0, s0.plusSeconds(8 * 3600), stages)
            for (k in 14 until 46) steps += StepRec(d.atStartOfDay(z).toInstant().plusSeconds(k * 1800L), d.atStartOfDay(z).toInstant().plusSeconds(k * 1800L + 1799), rnd.nextInt(1500).toLong(), true)
            d = d.plusDays(1)
        }
        val inp = Input(z, at("2026-11-12T08:30"), true, true, hr.last().t, hr = hr, steps = steps, sleeps = sleeps, exercises = emptyList(), resting = emptyList(),
            spo2 = spo2.map { it.t }, spo2Pct = spo2)
        val r = buildResult(inp, Request(from, to), "k7q2")
        val det = r.getJSONObject("detaliu").toString()
        fun packed(s: String): Int {
            val bo = java.io.ByteArrayOutputStream()
            java.util.zip.DeflaterOutputStream(bo, java.util.zip.Deflater(9, true)).use { it.write(s.toByteArray()) }
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bo.toByteArray()).length
        }
        val all = packed(r.toString()); val onlyDet = packed(det)
        println("MARIME 14 zile: rezultat ${r.toString().length} B JSON → $all B base64; detaliu ${det.length} B JSON → $onlyDet B base64 (${onlyDet / 14} B/zi)")
        assertEquals(14, r.getJSONObject("detaliu").length())
        assertTrue("prea mare: $all", all < 200_000)
    }
}
