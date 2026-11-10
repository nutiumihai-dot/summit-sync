package ro.summit.sync

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

/** Pagina de confidențialitate cerută de Health Connect. */
class RationaleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            textSize = 16f
            setPadding(48, 160, 48, 48)
            text = """
                Summit Sync — confidențialitate

                Summit Sync citește din Health Connect pașii, somnul, pulsul, pulsul de repaus, variabilitatea pulsului, antrenamentele, distanța și datele înrudite ale ultimelor 14 zile.

                Aplicația nu are acces la internet și nu trimite nimic nicăieri. Calculează pe telefon un rezumat pe zi și îl predă aplicației Summit Building, doar când o deschizi tu de acolo. Summit Building îl salvează în repo-ul tău privat, la „Trimite notițele”.

                Mostrele brute de puls nu sunt păstrate și nu pleacă de pe telefon; pleacă doar valorile rezumate.

                Poți retrage oricând permisiunile din Setări → Health Connect.
            """.trimIndent()
        }
        setContentView(ScrollView(this).apply { addView(tv) })
    }
}
