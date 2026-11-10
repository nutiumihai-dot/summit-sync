package ro.summit.sync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
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

class MainActivity : ComponentActivity() {
    private lateinit var out: TextView
    private var report = ""

    private val askPerms = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { g ->
        out.text = "Permisiuni acordate: ${g.size}/${ALL_PERMS.size}\n"
        diag()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.setOnApplyWindowInsetsListener { v, ins ->
            val b = ins.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(b.left + 24, b.top + 16, b.right + 24, b.bottom + 16); ins
        }
        fun btn(label: String, f: () -> Unit) = Button(this).apply { text = label; isAllCaps = false; setOnClickListener { f() } }
        root.addView(TextView(this).apply { text = "Summit Sync · diagnostic"; textSize = 20f; setTypeface(null, Typeface.BOLD) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(btn("Permisiuni") { askPerms.launch(ALL_PERMS) })
        row.addView(btn("Rulează") { diag() })
        row.addView(btn("Copiază") { copy() })
        row.addView(btn("Trimite") { share() })
        root.addView(row)
        out = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true) }
        root.addView(ScrollView(this).apply { addView(out) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
            out.text = "Health Connect nu e disponibil pe acest telefon."
            return
        }
        lifecycleScope.launch {
            val g = HealthConnectClient.getOrCreate(this@MainActivity).permissionController.getGrantedPermissions()
            if (!g.containsAll(ALL_PERMS)) askPerms.launch(ALL_PERMS) else diag()
        }
    }

    private fun diag() {
        out.append("Citesc…\n")
        lifecycleScope.launch {
            report = try { runDiagnostic(this@MainActivity) } catch (e: Exception) { "EROARE: ${e.javaClass.simpleName}: ${e.message}\n${e.stackTrace.take(6).joinToString("\n")}" }
            out.text = report
        }
    }

    private fun copy() {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Summit Sync", report))
        Toast.makeText(this, "Raport copiat", Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, report) }, "Trimite raportul"))
    }
}
