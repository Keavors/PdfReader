package com.example.pdfreader

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.net.toUri
import androidx.core.os.BundleCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.util.FitPolicy
import com.shockwave.pdfium.PdfPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import com.github.barteksc.pdfviewer.exception.FileNotFoundException as PdfFileNotFoundException

/** Единственный тип файлов, который открывает приложение. */
private const val PDF_MIME_TYPE = "application/pdf"

/** Зазор между страницами в слитной ленте, dp: библиотека сама переводит его в пиксели. */
private const val PAGE_SPACING = 6

private const val STATE_URI = "uri"
private const val STATE_PAGE = "page"
private const val STATE_HIDDEN = "hidden"
private const val STATE_PASSWORD = "password"

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
    private lateinit var btnShare: View
    private lateinit var rvRecent: RecyclerView
    private lateinit var tvNoRecent: TextView

    private lateinit var recentFiles: RecentFilesStore
    private val recentAdapter = RecentFilesAdapter { openDocument(it.uri.toUri()) }

    private var scrollHandle: LockableScrollHandle? = null

    private var currentUri: Uri? = null
    private var currentName = ""
    private var currentPassword: String? = null
    private var currentPage = 0
    private var uiHidden = false

    /** Документ дошёл до конца загрузки — только такой стоит помнить. */
    private var documentLoaded = false

    /** Последний тап пришёлся на ссылку внутри документа, а не на пустое место. */
    private var linkTapped = false

    /** Имя файла у провайдера спрашиваем в фоне: он может отвечать медленно. */
    private var nameJob: Job? = null

    /** Размеры системных баров и выреза камеры — известны, даже когда бары спрятаны. */
    private var barInsets: Insets = Insets.NONE

    // Системный пикер файлов (SAF) — разрешения в манифесте не нужны вообще.
    private val openPdfLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                keepAccess(uri)
                openDocument(uri)
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
        btnShare = findViewById(R.id.btnShare)
        rvRecent = findViewById(R.id.rvRecent)
        tvNoRecent = findViewById(R.id.tvNoRecent)

        findViewById<View>(R.id.btnOpen).setOnClickListener { pickFile() }
        findViewById<View>(R.id.btnSettings).setOnClickListener { showSettingsDialog() }
        btnBack.setOnClickListener { closeDocument() }
        btnShare.setOnClickListener { shareCurrentFile() }

        rvRecent.layoutManager = LinearLayoutManager(this)
        rvRecent.adapter = recentAdapter

        onBackPressedDispatcher.addCallback(this, backCallback)
        listenToDialogs()

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

        restoreOrOpen(savedInstanceState)
        applyUiState()
    }

    /**
     * Что показать при старте.
     *
     * Интент со ссылкой на документ учитывается только на первом запуске активити:
     * иначе закрытый документ открывался бы заново при каждом её пересоздании —
     * достаточно повернуть экран, потому что сам интент никуда не девается.
     */
    private fun restoreOrOpen(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) {
            currentPage = savedInstanceState.getInt(STATE_PAGE, 0)
            uiHidden = savedInstanceState.getBoolean(STATE_HIDDEN, false)
            currentPassword = savedInstanceState.getString(STATE_PASSWORD)
            val restoredUri =
                BundleCompat.getParcelable(savedInstanceState, STATE_URI, Uri::class.java)
            if (restoredUri != null) {
                openPdf(restoredUri, currentPage, currentPassword)
            } else {
                showRecentFiles()
            }
            return
        }

        val uri = intentUri(intent)
        if (uri != null) {
            keepAccess(uri)
            openDocument(uri)
        } else {
            showRecentFiles()
        }
    }

    /** Открыли ещё один PDF, пока приложение уже запущено. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val uri = intentUri(intent) ?: return
        keepAccess(uri)
        openDocument(uri)
    }

    override fun onPause() {
        super.onPause()
        rememberPosition()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable(STATE_URI, currentUri)
        outState.putInt(STATE_PAGE, currentPage)
        outState.putBoolean(STATE_HIDDEN, uiHidden)
        outState.putString(STATE_PASSWORD, currentPassword)
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

    /**
     * Откроется ли этот файл в следующий раз.
     *
     * Мессенджеры и почта выдают доступ ровно на один сеанс. Такую ссылку можно
     * прочитать сейчас, но в списке недавних она будет только мозолить глаза —
     * после перезапуска по ней уже ничего не откроется.
     */
    private fun hasDurableAccess(uri: Uri): Boolean =
        uri.scheme != ContentResolver.SCHEME_CONTENT ||
            contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    private fun pickFile() = openPdfLauncher.launch(arrayOf(PDF_MIME_TYPE))

    /** Открыть документ с того места, где его закрыли в прошлый раз. */
    private fun openDocument(uri: Uri) = openPdf(uri, recentFiles.pageOf(uri), password = null)

    private fun openPdf(uri: Uri, page: Int, password: String?) {
        currentUri = uri
        currentPage = page
        currentPassword = password
        documentLoaded = false

        emptyHint.visibility = View.GONE
        pdfView.visibility = View.VISIBLE
        btnBack.visibility = View.VISIBLE
        btnShare.visibility = View.VISIBLE
        backCallback.isEnabled = true

        showName(fallbackName(uri))
        resolveNameAsync(uri)

        val settings = ReaderSettings.load(this)
        val fitPolicy = when {
            settings.singlePage -> FitPolicy.BOTH   // страница целиком помещается на экран
            settings.horizontal -> FitPolicy.HEIGHT // лента по горизонтали - вписываем по высоте
            else -> FitPolicy.WIDTH                 // лента по вертикали - вписываем по ширине
        }

        pdfView.fromUri(uri)
            .password(password)
            .defaultPage(page)
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
            .linkHandler(ReportingLinkHandler(pdfView) { linkTapped = true })
            .onPageChange { changed, _ -> currentPage = changed }
            .onLoad { rememberOpened() }
            .onTap { onDocumentTap(); true }                // одиночный тап = скрыть/показать всё
            .onError { handleOpenError(it) }
            .load()
    }

    /**
     * Тап по документу.
     *
     * Решение о панели откладываем на следующий кадр: ссылку под пальцем
     * библиотека проверяет уже после onTap, и раньше этого момента неизвестно,
     * был ли тап переходом по ссылке.
     */
    private fun onDocumentTap() {
        pdfView.post {
            if (!linkTapped) toggleUi()
            linkTapped = false
        }
    }

    /** Документ открылся — теперь его можно записать в недавние. */
    private fun rememberOpened() {
        documentLoaded = true
        val uri = currentUri ?: return
        if (hasDurableAccess(uri)) recentFiles.add(uri, currentName, currentPage)
    }

    private fun rememberPosition() {
        val uri = currentUri ?: return
        if (documentLoaded) recentFiles.updatePage(uri, currentPage)
    }

    /**
     * Что делать с не открывшимся документом.
     *
     * Из недавних файл вылетает только если он точно больше не откроется: просто
     * недоступный в эту минуту (облако офлайн, карта вынута) там остаётся.
     */
    private fun handleOpenError(error: Throwable) {
        if (error is PdfPasswordException) {
            askPassword(retry = currentPassword != null)
            return
        }
        val gone = isGone(error)
        Toast.makeText(this, errorMessage(gone), Toast.LENGTH_LONG).show()
        currentUri?.let { if (gone) recentFiles.remove(it) }
        closeDocument()
    }

    @StringRes
    private fun errorMessage(gone: Boolean): Int =
        if (gone) R.string.error_file_gone else R.string.error_open_failed

    /** Файла нет или доступ к нему отозван — повторять попытку бессмысленно. */
    private fun isGone(error: Throwable): Boolean {
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth++ < 8) {
            if (cause is SecurityException ||
                cause is FileNotFoundException ||
                cause is PdfFileNotFoundException
            ) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    private fun askPassword(retry: Boolean) {
        if (isFinishing || supportFragmentManager.isStateSaved) return
        if (supportFragmentManager.findFragmentByTag(PasswordDialogFragment.TAG) != null) return
        PasswordDialogFragment.newInstance(retry)
            .show(supportFragmentManager, PasswordDialogFragment.TAG)
    }

    private fun listenToDialogs() {
        supportFragmentManager.setFragmentResultListener(
            SettingsDialogFragment.RESULT_KEY,
            this,
        ) { _, _ ->
            // Перечитываем с новыми настройками, текущая страница сохраняется.
            currentUri?.let { openPdf(it, currentPage, currentPassword) }
        }
        supportFragmentManager.setFragmentResultListener(
            PasswordDialogFragment.RESULT_KEY,
            this,
        ) { _, result ->
            val password = result.getString(PasswordDialogFragment.EXTRA_PASSWORD)
            val uri = currentUri
            if (password.isNullOrEmpty() || uri == null) closeDocument()
            else openPdf(uri, currentPage, password)
        }
    }

    private fun closeDocument() {
        rememberPosition()
        // Интент со ссылкой на документ отработал: дальше он только мешает,
        // потому что заново открывал бы уже закрытый файл.
        if (intentUri(intent) != null) intent = Intent(Intent.ACTION_MAIN)
        currentUri = null
        currentName = ""
        currentPassword = null
        currentPage = 0
        documentLoaded = false
        nameJob?.cancel()
        scrollHandle = null
        backCallback.isEnabled = false
        pdfView.recycle()
        showRecentFiles()
    }

    private fun showName(name: String) {
        currentName = name
        titleView.text = name
    }

    /**
     * Уточняет имя файла у провайдера в фоне.
     *
     * Запрос к чужому провайдеру (особенно к облачному) может задуматься на сотни
     * миллисекунд, поэтому документ начинает грузиться сразу, а в заголовке до
     * ответа стоит имя, вытащенное из самой ссылки.
     */
    private fun resolveNameAsync(uri: Uri) {
        nameJob?.cancel()
        nameJob = lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) { queryDisplayName(uri) } ?: return@launch
            if (currentUri != uri) return@launch
            showName(name)
            if (documentLoaded) rememberOpened()
        }
    }

    private fun queryDisplayName(uri: Uri): String? =
        displayName(uri, arrayOf(OpenableColumns.DISPLAY_NAME))
            ?: displayName(uri, null) // провайдер может не понять проекцию

    private fun displayName(uri: Uri, projection: Array<String>?): String? = try {
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                cursor.getString(index)?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        }
    } catch (_: Exception) {
        // Имя не отдали - обойдёмся хвостом ссылки.
        null
    }

    /** Имя из самой ссылки: `.../document/primary:Books/книга.pdf` даёт `книга.pdf`. */
    private fun fallbackName(uri: Uri): String =
        uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: getString(R.string.default_document_name)

    /**
     * Отдаёт открытый файл системному меню "Поделиться".
     *
     * ClipData вместе с FLAG_GRANT_READ_URI_PERMISSION передаёт получателю
     * право прочитать файл: без этого приложение-получатель видит ссылку,
     * но открыть по ней ничего не может.
     */
    private fun shareCurrentFile() {
        val uri = currentUri ?: return
        val name = currentName.ifEmpty { getString(R.string.default_document_name) }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = PDF_MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, name)
            clipData = ClipData.newUri(contentResolver, name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(send, getString(R.string.cd_share)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.error_share_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleUi() {
        if (currentUri == null) return
        uiHidden = !uiHidden
        applyUiState()
    }

    private fun applyUiState() {
        val controller = WindowCompat.getInsetsController(window, rootView)
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
     *
     * У панели меняем только вертикальные отступы: боковые заданы в вёрстке
     * как start/end и в языках с письмом справа налево не должны переезжать.
     */
    private fun applyPaddings() {
        if (uiHidden) {
            rootView.setPadding(0, 0, 0, 0)
            topBar.setPaddingRelative(
                topBar.paddingStart,
                0,
                topBar.paddingEnd,
                topBar.paddingBottom,
            )
        } else {
            rootView.setPadding(barInsets.left, 0, barInsets.right, barInsets.bottom)
            topBar.setPaddingRelative(
                topBar.paddingStart,
                barInsets.top,
                topBar.paddingEnd,
                topBar.paddingBottom,
            )
        }
    }

    private fun showSettingsDialog() {
        if (supportFragmentManager.isStateSaved) return
        SettingsDialogFragment().show(supportFragmentManager, SettingsDialogFragment.TAG)
    }

    /** Экран без открытого документа: заголовок и список недавних файлов. */
    private fun showRecentFiles() {
        emptyHint.visibility = View.VISIBLE
        pdfView.visibility = View.GONE
        btnBack.visibility = View.GONE
        btnShare.visibility = View.GONE
        titleView.setText(R.string.app_name)
        uiHidden = false // к списку всегда возвращаемся с видимыми барами
        applyUiState()

        val files = recentFiles.load()
        recentAdapter.submitList(files)
        tvNoRecent.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
    }
}
