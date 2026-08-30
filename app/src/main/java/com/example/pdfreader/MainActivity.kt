package com.example.pdfreader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.net.toUri
import androidx.core.os.BundleCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.util.FitPolicy
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Единственный тип файлов, который открывает приложение. */
private const val PDF_MIME_TYPE = "application/pdf"

/** Зазор между страницами в слитной ленте, px. */
private const val PAGE_SPACING = 6

private const val STATE_URI = "uri"
private const val STATE_PAGE = "page"
private const val STATE_HIDDEN = "hidden"

/**
 * Единственный экран приложения: список недавних файлов, пока документ не выбран,
 * и просмотрщик, когда выбран.
 *
 * Одиночный тап по документу убирает с экрана всё, кроме самого файла
 * (панель, ползунок, системные бары), повторный — возвращает.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var rootView: View
    private lateinit var pdfView: PDFView
    private lateinit var topBar: View
    private lateinit var titleView: TextView
    private lateinit var emptyHint: View
    private lateinit var btnBack: View
    private lateinit var rvRecent: RecyclerView
    private lateinit var tvNoRecent: TextView

    private lateinit var recentFiles: RecentFilesStore
    private val recentAdapter = RecentFilesAdapter { openPdf(it.uri.toUri(), fromStart = true) }

    private var scrollHandle: LockableScrollHandle? = null

    private var currentUri: Uri? = null
    private var currentPage = 0
    private var uiHidden = false

    /** Размеры системных баров и выреза камеры — известны, даже когда бары спрятаны. */
    private var barInsets: Insets = Insets.NONE

    // Системный пикер файлов (SAF) — разрешения в манифесте не нужны вообще.
    private val openPdfLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                keepAccess(uri)
                openPdf(uri, fromStart = true)
            }
        }

    /** Системная кнопка "назад" сначала закрывает документ и только потом выходит. */
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeDocument()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false) // рисуем от края до края
        setContentView(R.layout.activity_main)

        recentFiles = RecentFilesStore(this)

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
        rvRecent.adapter = recentAdapter

        onBackPressedDispatcher.addCallback(this, backCallback)

        // ЕДИНСТВЕННЫЙ слушатель инсетов — на корневом layout.
        // systemBars + displayCutout: вырез камеры это ОТДЕЛЬНЫЙ тип, в systemBars его нет.
        // В альбомной ориентации навигационная панель уезжает на боковую грань.
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            barInsets = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            applyPaddings()
            insets
        }

        val restoredUri = savedInstanceState?.let {
            currentPage = it.getInt(STATE_PAGE, 0)
            uiHidden = it.getBoolean(STATE_HIDDEN, false)
            BundleCompat.getParcelable(it, STATE_URI, Uri::class.java)
        }

        val uri = restoredUri ?: intentUri(intent)?.also { keepAccess(it) }
        if (uri != null) openPdf(uri, fromStart = false) else showRecentFiles()
        applyUiState()
    }

    /** Открыли ещё один PDF, пока приложение уже запущено. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val uri = intentUri(intent) ?: return
        keepAccess(uri)
        openPdf(uri, fromStart = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable(STATE_URI, currentUri)
        outState.putInt(STATE_PAGE, currentPage)
        outState.putBoolean(STATE_HIDDEN, uiHidden)
    }

    override fun onDestroy() {
        super.onDestroy()
        pdfView.recycle() // иначе документ висит в нативной памяти до сборки мусора
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // После диалога или сворачивания системные бары возвращаются сами - прячем снова.
        if (hasFocus && uiHidden) applyUiState()
    }

    private fun intentUri(intent: Intent?): Uri? =
        if (intent?.action == Intent.ACTION_VIEW) intent.data else null

    /**
     * Просит постоянный доступ к файлу, чтобы его можно было открыть из списка
     * недавних и после перезапуска. Проводник такое разрешение даёт не всегда.
     */
    private fun keepAccess(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Постоянного доступа не дали - на текущий сеанс прав и так хватит.
        }
    }

    private fun pickFile() = openPdfLauncher.launch(arrayOf(PDF_MIME_TYPE))

    /** @param fromStart открыть с первой страницы, а не с той, где остановились. */
    private fun openPdf(uri: Uri, fromStart: Boolean) {
        if (fromStart) currentPage = 0
        currentUri = uri
        emptyHint.visibility = View.GONE
        pdfView.visibility = View.VISIBLE
        btnBack.visibility = View.VISIBLE
        backCallback.isEnabled = true

        val name = displayName(uri)
        titleView.text = name
        recentFiles.add(uri, name)

        val settings = ReaderSettings.load(this)
        val fitPolicy = when {
            settings.singlePage -> FitPolicy.BOTH   // страница целиком помещается на экран
            settings.horizontal -> FitPolicy.HEIGHT // лента по горизонтали - вписываем по высоте
            else -> FitPolicy.WIDTH                 // лента по вертикали - вписываем по ширине
        }

        pdfView.fromUri(uri)
            .defaultPage(currentPage)
            .swipeHorizontal(settings.horizontal)
            .pageSnap(settings.singlePage)                  // прилипание к странице
            .autoSpacing(settings.singlePage)               // в постраничном - по одной на экран
            .pageFling(settings.singlePage)                 // свайп = ровно одна страница
            .spacing(if (settings.singlePage) 0 else PAGE_SPACING)
            .pageFitPolicy(fitPolicy)
            .fitEachPage(true)
            .scrollHandle(LockableScrollHandle(this).also {
                it.locked = uiHidden
                scrollHandle = it
            })
            .enableAntialiasing(true)
            .enableAnnotationRendering(true)
            .onPageChange { page, _ -> currentPage = page }
            .onTap { toggleUi(); true }                     // одиночный тап = скрыть/показать всё
            .onError {
                Toast.makeText(this, R.string.error_open_failed, Toast.LENGTH_LONG).show()
                currentUri?.let { broken -> recentFiles.remove(broken) }
                closeDocument()
            }
            .load()
    }

    private fun closeDocument() {
        currentUri = null
        currentPage = 0
        scrollHandle = null
        backCallback.isEnabled = false
        pdfView.recycle()
        showRecentFiles()
    }

    /** Имя файла для заголовка и списка недавних. */
    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    cursor.getString(index)?.let { return it }
                }
            }
        } catch (_: Exception) {
            // Имя не отдали - обойдёмся хвостом ссылки.
        }
        return uri.lastPathSegment ?: getString(R.string.default_document_name)
    }

    private fun toggleUi() {
        if (currentUri == null) return
        uiHidden = !uiHidden
        applyUiState()
    }

    private fun applyUiState() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (uiHidden) {
            // Прячем ВСЁ: панель приложения, ползунок, статус-бар, кнопки навигации.
            topBar.visibility = View.GONE
            scrollHandle?.locked = true
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            topBar.visibility = View.VISIBLE
            scrollHandle?.locked = false
            scrollHandle?.show()
            scrollHandle?.hideDelayed() // появился вместе с UI и сам растает через секунду
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        applyPaddings()
    }

    /**
     * Режим чтения: паддинги 0, документ занимает физически весь экран.
     * UI виден: контент отодвинут от навигации и выреза, а верхняя панель
     * сама берёт отступ статус-бара - её фон уходит под него.
     */
    private fun applyPaddings() {
        if (uiHidden) {
            rootView.setPadding(0, 0, 0, 0)
            topBar.setPadding(topBar.paddingLeft, 0, topBar.paddingRight, topBar.paddingBottom)
        } else {
            rootView.setPadding(barInsets.left, 0, barInsets.right, barInsets.bottom)
            topBar.setPadding(
                topBar.paddingLeft,
                barInsets.top,
                topBar.paddingRight,
                topBar.paddingBottom,
            )
        }
    }

    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val directions = view.findViewById<RadioGroup>(R.id.rgDirection)
        val modes = view.findViewById<RadioGroup>(R.id.rgMode)

        val settings = ReaderSettings.load(this)
        directions.check(if (settings.horizontal) R.id.rbHorizontal else R.id.rbVertical)
        modes.check(if (settings.singlePage) R.id.rbSingle else R.id.rbContinuous)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_title)
            .setView(view)
            .setPositiveButton(R.string.action_apply) { _, _ ->
                ReaderSettings(
                    horizontal = directions.checkedRadioButtonId == R.id.rbHorizontal,
                    singlePage = modes.checkedRadioButtonId == R.id.rbSingle,
                ).save(this)
                // Перечитываем с новыми настройками, текущая страница сохраняется.
                currentUri?.let { openPdf(it, fromStart = false) }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** Экран без открытого документа: заголовок и список недавних файлов. */
    private fun showRecentFiles() {
        emptyHint.visibility = View.VISIBLE
        pdfView.visibility = View.GONE
        btnBack.visibility = View.GONE
        titleView.setText(R.string.app_name)
        uiHidden = false // к списку всегда возвращаемся с видимыми барами
        applyUiState()

        val files = recentFiles.load()
        recentAdapter.submitList(files)
        tvNoRecent.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
    }
}
