package com.example.pdfreader // ← ЗАМЕНИ на свой package (первая строка твоего сгенерированного MainActivity)
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.os.BundleCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.scroll.DefaultScrollHandle
import com.github.barteksc.pdfviewer.util.FitPolicy
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "reader_prefs"
        private const val KEY_HORIZONTAL = "swipe_horizontal" // false = вертикально
        private const val KEY_SINGLE = "single_page"          // false = слитно, все подряд
        private const val KEY_RECENT = "recent_files"         // JSON список
    }

    private lateinit var rootView: View
    private lateinit var pdfView: PDFView
    private lateinit var topBar: View
    private lateinit var titleView: TextView
    private lateinit var emptyHint: View
    private lateinit var btnBack: View
    private lateinit var rvRecent: RecyclerView
    private lateinit var tvNoRecent: TextView

    private var scrollHandle: LockableScrollHandle? = null
    private var recentAdapter: RecentFilesAdapter? = null

    private var currentUri: Uri? = null
    private var currentPage = 0
    private var uiHidden = false

    /** Размеры системных баров + выреза камеры. IgnoringVisibility — чтобы знать их даже когда бары спрятаны. */
    private var barInsets: Insets = Insets.NONE

    // Системный пикер файлов (SAF) — разрешения в манифесте не нужны вообще
    private val openPdfLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) {
                    // постоянное разрешение не дали — для текущей сессии и так хватит
                }
                currentPage = 0
                openPdf(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false) // рисуем от края до края
        setContentView(R.layout.activity_main)

        rootView = findViewById(R.id.root)
        pdfView = findViewById(R.id.pdfView)
        topBar = findViewById(R.id.topBar)
        titleView = findViewById(R.id.titleView)
        emptyHint = findViewById(R.id.emptyHint)
        btnBack = findViewById(R.id.btnBack)
        rvRecent = findViewById(R.id.rvRecent)
        tvNoRecent = findViewById(R.id.tvNoRecent)

        findViewById<View>(R.id.btnOpen).setOnClickListener { pickFile() }
        findViewById<View>(R.id.btnSettings).setOnClickListener { showSettingsDialog() }
        btnBack.setOnClickListener { closeDocument() }

        rvRecent.layoutManager = LinearLayoutManager(this)
        recentAdapter = RecentFilesAdapter(emptyList()) { recent ->
            currentPage = 0 // иначе новый файл откроется на странице предыдущего
            openPdf(recent.uri)
        }
        rvRecent.adapter = recentAdapter

        // ЕДИНСТВЕННЫЙ слушатель инсетов — на корневом layout.
        // systemBars + displayCutout: вырез камеры — это ОТДЕЛЬНЫЙ тип, в systemBars его нет.
        // В альбомной ориентации навигационная панель уезжает на боковую грань (left/right).
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            barInsets = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            applyPaddings()
            insets
        }

        if (savedInstanceState != null) {
            // Восстановление после поворота экрана
            currentUri = BundleCompat.getParcelable(savedInstanceState, "uri", Uri::class.java)
            currentPage = savedInstanceState.getInt("page", 0)
            uiHidden = savedInstanceState.getBoolean("hidden", false)
        } else if (intent?.action == Intent.ACTION_VIEW) {
            // Открыли PDF из проводника через "Открыть с помощью"
            currentUri = intent.data
        }

        currentUri?.let { openPdf(it) } ?: loadRecentFiles()
        applyUiState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable("uri", currentUri)
        outState.putInt("page", currentPage)
        outState.putBoolean("hidden", uiHidden)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyUiState() // после сворачивания/диалогов снова прячем бары, если надо
    }

    private fun pickFile() = openPdfLauncher.launch(arrayOf("application/pdf"))

    private fun openPdf(uri: Uri) {
        currentUri = uri
        emptyHint.visibility = View.GONE
        pdfView.visibility = View.VISIBLE
        btnBack.visibility = View.VISIBLE
        titleView.text = displayName(uri) // полное имя файла в панели слева
        saveRecentFile(uri)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val horizontal = prefs.getBoolean(KEY_HORIZONTAL, false)
        val singlePage = prefs.getBoolean(KEY_SINGLE, false)

        val fitPolicy = when {
            singlePage -> FitPolicy.BOTH    // страница целиком помещается на экран
            horizontal -> FitPolicy.HEIGHT  // лента по горизонтали — вписываем по высоте
            else -> FitPolicy.WIDTH         // лента по вертикали — вписываем по ширине
        }

        pdfView.fromUri(uri)
            .defaultPage(currentPage)
            .swipeHorizontal(horizontal)
            .pageSnap(singlePage)                     // прилипание к странице
            .autoSpacing(singlePage)                  // в постраничном — по одной на экран
            .pageFling(singlePage)                    // свайп = ровно одна страница
            .spacing(if (singlePage) 0 else 6)        // зазор между страницами в ленте
            .pageFitPolicy(fitPolicy)
            .fitEachPage(true)
            .scrollHandle(LockableScrollHandle(this).also {
                it.locked = uiHidden
                scrollHandle = it
            })
            .enableAntialiasing(true)
            .enableAnnotationRendering(true)
            .onPageChange { page, _ -> currentPage = page }
            .onTap { toggleUi(); true }               // одиночный тап = скрыть/показать всё
            .onError {
                Toast.makeText(this, "Не удалось открыть файл", Toast.LENGTH_LONG).show()
                currentUri?.let { broken -> removeRecentFile(broken) } // битую запись — вон из списка
                currentUri = null
                scrollHandle = null
                loadRecentFiles()
            }
            .load()
    }

    private fun closeDocument() {
        currentUri = null
        scrollHandle = null
        pdfView.recycle()
        loadRecentFiles()
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    c.getString(idx)?.let { return it }
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "Документ.pdf"
    }

    private fun toggleUi() {
        if (currentUri == null) return
        uiHidden = !uiHidden
        applyUiState()
    }

    private fun applyUiState() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (uiHidden) {
            // Прячем ВСЁ: панель приложения, ползунок, статус-бар, кнопки навигации
            topBar.visibility = View.GONE
            scrollHandle?.locked = true   // и запрещаем ему вылезать при прокрутке
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            topBar.visibility = View.VISIBLE
            scrollHandle?.locked = false
            scrollHandle?.show()
            scrollHandle?.hideDelayed()   // появился вместе с UI и сам растает через секунду
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        applyPaddings()
    }

    /**
     * Чистый режим: паддинги 0, документ занимает физически весь экран.
     * UI виден: контент отодвинут от навигации/выреза по бокам и снизу,
     * а верхняя панель сама берёт отступ статус-бара (её фон уходит под него).
     */
    private fun applyPaddings() {
        if (uiHidden) {
            rootView.setPadding(0, 0, 0, 0)
            topBar.setPadding(topBar.paddingLeft, 0, topBar.paddingRight, topBar.paddingBottom)
        } else {
            rootView.setPadding(barInsets.left, 0, barInsets.right, barInsets.bottom)
            topBar.setPadding(topBar.paddingLeft, barInsets.top, topBar.paddingRight, topBar.paddingBottom)
        }
    }

    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val rgDirection = view.findViewById<RadioGroup>(R.id.rgDirection)
        val rgMode = view.findViewById<RadioGroup>(R.id.rgMode)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        rgDirection.check(
            if (prefs.getBoolean(KEY_HORIZONTAL, false)) R.id.rbHorizontal else R.id.rbVertical
        )
        rgMode.check(
            if (prefs.getBoolean(KEY_SINGLE, false)) R.id.rbSingle else R.id.rbContinuous
        )

        MaterialAlertDialogBuilder(this)
            .setTitle("Режим просмотра")
            .setView(view)
            .setPositiveButton("Применить") { _, _ ->
                prefs.edit()
                    .putBoolean(KEY_HORIZONTAL, rgDirection.checkedRadioButtonId == R.id.rbHorizontal)
                    .putBoolean(KEY_SINGLE, rgMode.checkedRadioButtonId == R.id.rbSingle)
                    .apply()
                // Перезагружаем с новыми настройками, текущая страница сохранится
                currentUri?.let { openPdf(it) }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---------- Недавние файлы ----------

    private data class RecentFile(val uri: Uri, val name: String)

    private fun saveRecentFile(uri: Uri) {
        val name = displayName(uri)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val recentJson = prefs.getString(KEY_RECENT, "[]")
        try {
            val array = JSONArray(recentJson)
            val newList = mutableListOf<JSONObject>()
            newList.add(JSONObject().put("uri", uri.toString()).put("name", name))

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (obj.getString("uri") != uri.toString()) {
                    newList.add(obj)
                }
                if (newList.size >= 15) break
            }

            val newArray = JSONArray()
            newList.forEach { newArray.put(it) }
            prefs.edit().putString(KEY_RECENT, newArray.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeRecentFile(uri: Uri) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        try {
            val array = JSONArray(prefs.getString(KEY_RECENT, "[]"))
            val out = JSONArray()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (obj.getString("uri") != uri.toString()) out.put(obj)
            }
            prefs.edit().putString(KEY_RECENT, out.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private fun loadRecentFiles() {
        if (currentUri != null) return
        emptyHint.visibility = View.VISIBLE
        pdfView.visibility = View.GONE
        btnBack.visibility = View.GONE
        titleView.text = "PDF Reader"
        uiHidden = false // показываем бары при возврате к списку
        applyUiState()

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val recentJson = prefs.getString(KEY_RECENT, "[]")
        val list = mutableListOf<RecentFile>()
        try {
            val array = JSONArray(recentJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(RecentFile(Uri.parse(obj.getString("uri")), obj.getString("name")))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        recentAdapter?.update(list)
        tvNoRecent.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private inner class RecentFilesAdapter(
        private var items: List<RecentFile>,
        private val onClick: (RecentFile) -> Unit
    ) : RecyclerView.Adapter<RecentFilesAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvFileName)
            val tvPath: TextView = view.findViewById(R.id.tvFilePath)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_recent_file, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tvName.text = item.name
            holder.tvPath.text = item.uri.toString()
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = items.size

        fun update(newItems: List<RecentFile>) {
            items = newItems
            notifyDataSetChanged()
        }
    }
}

/**
 * DefaultScrollHandle внутри setScroll() САМ вызывает show() при каждой прокрутке —
 * поэтому обычный hide() не работает: ползунок тут же вылезает обратно.
 * Флаг locked блокирует показ намертво, пока мы в чистом режиме.
 */
class LockableScrollHandle(context: Context) : DefaultScrollHandle(context) {

    var locked = false
        set(value) {
            field = value
            if (value) super.hide()
        }

    override fun show() {
        if (!locked) super.show()
    }
}