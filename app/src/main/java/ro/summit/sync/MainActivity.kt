package ro.summit.sync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private lateinit var out: TextView
    private var report = ""
    private var pending: ImportReq? = null

    private val askPerms = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { g ->
        val p = pending
        if (p != null) runImport(p) else { out.text = "Permisiuni acordate: ${g.size}/${ALL_PERMS.size}\n"; diag() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.setOnApplyWindowInsetsListener { v, ins ->
            val b = ins.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(b.left + 24, b.top + 16, b.right + 24, b.bottom + 16); ins
        }
        fun btn(label: String, f: () -> Unit) = Button(this).apply { text = label; isAllCaps = false; setOnClickListener { f() } }
        root.addView(TextView(this).apply { text = "Summit Sync ${BuildConfigInfo.version(this@MainActivity)}"; textSize = 20f; setTypeface(null, Typeface.BOLD) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(btn("Diagnostic") { pending = null; diag() })
        row.addView(btn("Ultimul import") { report = runCatching { File(filesDir, "ultim.json").readText() }.getOrDefault("Niciun import încă."); out.text = report })
        row.addView(btn("Copiază") { copy() })
        row.addView(btn("Trimite") { share() })
        root.addView(row)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(btn("Pași: instantaneu") { snap() })
        row2.addView(btn("Pași: raport") { report = stepReport(this); out.text = report })
        row2.addView(btn("Pași: șterge") { stepReset(this); out.text = "Instantaneele au fost șterse." })
        root.addView(row2)
        out = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true) }
        root.addView(ScrollView(this).apply { addView(out) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) { out.text = "Health Connect nu e disponibil pe acest telefon."; return }
        if (!handle(intent)) out.text = "Deschide Summit Sync din Summit Building (butonul de pe Azi), sau apasă Diagnostic."
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handle(intent) }

    private fun handle(i: Intent?): Boolean {
        val u = i?.data ?: return false
        if (u.scheme != "summitsync" || u.host != "import") return false
        val r = parseImport({ u.getQueryParameter(it) }, LocalDate.now())
        if (r == null) { out.text = "Cerere de import invalidă (adresă de întoarcere necunoscută sau zile greșite)."; return true }
        pending = r
        lifecycleScope.launch {
            val g = HealthConnectClient.getOrCreate(this@MainActivity).permissionController.getGrantedPermissions()
            if (!g.containsAll(NEEDED)) askPerms.launch(ALL_PERMS) else runImport(r)
        }
        return true
    }

    private fun runImport(r: ImportReq) {
        pending = null
        out.text = "Citesc din Health Connect…"
        lifecycleScope.launch {
            val json = try {
                buildResult(loadInput(this@MainActivity, r.req), r.req, r.cod).toString()
            } catch (e: Exception) {
                org.json.JSONObject().put("v", 1).put("cod", r.cod).put("verdict", "rosu")
                    .put("verificari", org.json.JSONObject().put("V0", "Eroare la citire: ${e.javaClass.simpleName} ${e.message}"))
                    .put("intrebari", org.json.JSONArray()).toString()
            }
            runCatching { File(filesDir, "ultim.json").writeText(json) }
            out.text = "Citesc… ✓"
            val back = Intent(Intent.ACTION_VIEW, Uri.parse(r.ret + "#hc=" + r.cod + "." + pack(json))).addCategory(Intent.CATEGORY_BROWSABLE)
            startActivity(back)
            finish()
        }
    }

    private fun diag() {
        out.text = "Citesc…\n"
        lifecycleScope.launch {
            val g = HealthConnectClient.getOrCreate(this@MainActivity).permissionController.getGrantedPermissions()
            if (!g.containsAll(NEEDED)) { askPerms.launch(ALL_PERMS); return@launch }
            report = try { runDiagnostic(this@MainActivity) } catch (e: Exception) { "EROARE: ${e.javaClass.simpleName}: ${e.message}\n${e.stackTrace.take(6).joinToString("\n")}" }
            out.text = report
        }
    }

    private fun snap() {
        out.text = "Citesc pașii…"
        lifecycleScope.launch {
            out.text = try { stepSnapshot(this@MainActivity) } catch (e: Exception) { "EROARE: ${e.javaClass.simpleName}: ${e.message}" }
            report = stepReport(this@MainActivity)
        }
    }

    private fun copy() {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Summit Sync", report))
        Toast.makeText(this, "Copiat", Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, report) }, "Trimite"))
    }
}
