package com.example.pdfreader

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.graphics.Insets
import androidx.core.net.toUri
import androidx.core.os.BundleCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.pdfreader.databinding.ActivityMainBinding
import com.github.barteksc.pdfviewer.util.FitPolicy
import com.shockwave.pdfium.PdfPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.time.ZoneId
import java.util.Date

/** Единственный тип файлов, который открывает приложение. */
private const val PDF_MIME_TYPE = "application/pdf"

/** Зазор между страницами в слитной ленте, dp: библиотека сама переводит его в пиксели. */
private const val PAGE_SPACING = 6

private const val STATE_URI = "uri"
private const val STATE_PAGE = "page"
private const val STATE_HIDDEN = "hidden"
private const val STATE_PASSWORD = "password"

private const val REQUEST_REMOVE_RECENT = "remove_recent"
private const val REQUEST_CLEAR_RECENT = "clear_recent"

/** На сколько причин вглубь разбираем исключение, чтобы не уйти в петлю. */
private const val MAX_CAUSE_DEPTH = 8

/**
 * Единственный экран приложения: список недавних файлов, пока документ не выбран,
 * и просмотрщик, когда выбран.
 *
 * Одиночный тап по документу убирает с экрана всё, кроме самого файла
 * (панель, ползунок, системные бары), повторный — возвращает.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private lateinit var recentFiles: RecentFilesStore
    private val recentAdapter = RecentFilesAdapter(
        onClick = ::openRecentFile,
        onLongClick = ::confirmRemoveRecent,
    )

    private var scrollHandle: LockableScrollHandle? = null

    private var currentUri: Uri? = null
    private var currentName = ""

    /** Размер открытого файла в байтах; 0, пока провайдер не ответил. */
    private var currentSize = 0L
    private var currentPassword: String? = null
    private var currentPage = 0
    private var pageCount = 0
    private var uiHidden = false

    /** Документ дошёл до конца загрузки — только такой стоит помнить. */
    private var documentLoaded = false

    /** Последний тап пришёлся на ссылку внутри документа, а не на пустое место. */
    private var linkTapped = false

    /** Об ошибке отрисовки говорим один раз на документ, а не на каждую страницу. */
    private var pageErrorReported = false

    /** Имя файла у провайдера спрашиваем в фоне: он может отвечать медленно. */
    private var nameJob: Job? = null

    /** Имя и размер файла уже известны — без них нельзя перенести его к себе. */
    private var metadataResolved = false

    /** Перенос документа в своё хранилище. */
    private var importJob: Job? = null

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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        recentFiles = RecentFilesStore(this)

        binding.btnOpen.setOnClickListener { pickFile() }
        binding.btnSettings.setOnClickListener { showSettingsDialog() }
        binding.btnBack.setOnClickListener { closeDocument() }
        binding.btnShare.setOnClickListener { shareCurrentFile() }
        binding.btnOutline.setOnClickListener { showOutline() }
        binding.pageIndicator.setOnClickListener { showJumpToPage() }
        binding.btnClearRecent.setOnClickListener { confirmClearRecent() }

        binding.rvRecent.layoutManager = LinearLayoutManager(this)
        binding.rvRecent.adapter = recentAdapter

        onBackPressedDispatcher.addCallback(this, backCallback)
        listenToDialogs()

        // ЕДИНСТВЕННЫЙ слушатель инсетов — на корневом layout.
        // systemBars + displayCutout: вырез камеры это ОТДЕЛЬНЫЙ тип, в systemBars его нет.
        // В альбомной ориентации навигационная панель уезжает на боковую грань.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            barInsets = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            applyPaddings()
            insets
        }

        restoreOrOpen(savedInstanceState)
        applyUiState()

        // Копии, на которые уже ничто не ссылается: остаются, если приложение
        // убили посреди переноса файла.
        lifecycleScope.launch(Dispatchers.IO) { recentFiles.deleteOrphanCopies() }
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
        binding.pdfView.recycle() // иначе документ висит в нативной памяти до сборки мусора
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // После диалога или сворачивания системные бары возвращаются сами - прячем снова.
        if (hasFocus && uiHidden) applyUiState()
    }

    /** Ссылка на документ из интента: и «открыть», и «поделиться с нами». */
    private fun intentUri(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND ->
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        else -> null
    }

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
        uri.scheme != ContentResolver.SCHEME_CONTENT || uri.toString() in durableUris()

    private fun durableUris(): Set<String> =
        contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .mapTo(HashSet()) { it.uri.toString() }

    private fun pickFile() = openPdfLauncher.launch(arrayOf(PDF_MIME_TYPE))

    /** Открыть документ с того места, где его закрыли в прошлый раз. */
    private fun openDocument(uri: Uri) = openPdf(uri, recentFiles.pageOf(uri), password = null)

    private fun openPdf(uri: Uri, page: Int, password: String?) {
        currentUri = uri
        currentPage = page
        currentPassword = password
        currentSize = 0
        pageCount = 0
        documentLoaded = false
        metadataResolved = false
        pageErrorReported = false
        importJob?.cancel()

        val settings = ReaderSettings.load(this)
        showReaderChrome(settings)
        showName(fallbackName(uri))
        resolveMetadataAsync(uri)

        val fitPolicy = when {
            settings.singlePage -> FitPolicy.BOTH   // страница целиком помещается на экран
            settings.horizontal -> FitPolicy.HEIGHT // лента по горизонтали - вписываем по высоте
            else -> FitPolicy.WIDTH                 // лента по вертикали - вписываем по ширине
        }

        binding.pdfView.fromUri(uri)
            .password(password)
            .defaultPage(page)
            .swipeHorizontal(settings.horizontal)
            .pageSnap(settings.singlePage)                  // прилипание к странице
            .autoSpacing(settings.singlePage)               // в постраничном - по одной на экран
            .pageFling(settings.singlePage)                 // свайп = ровно одна страница
            .spacing(if (settings.singlePage) 0 else PAGE_SPACING)
            .pageFitPolicy(fitPolicy)
            .fitEachPage(true)
            .nightMode(settings.nightMode)
            .scrollHandle(LockableScrollHandle(this).also {
                it.locked = uiHidden
                scrollHandle = it
            })
            .enableAntialiasing(true)
            .enableAnnotationRendering(true)
            .linkHandler(ReportingLinkHandler(binding.pdfView) { linkTapped = true })
            .onPageChange { changed, _ ->
                currentPage = changed
                updatePageIndicator()
            }
            .onLoad { pages -> onDocumentLoaded(pages) }
            .onTap { onDocumentTap(); true }                // одиночный тап = скрыть/показать всё
            .onPageError { failed, _ -> reportPageError(failed) }
            .onError { handleOpenError(it) }
            .load()
    }

    /** Панель и окно в режиме чтения. */
    private fun showReaderChrome(settings: ReaderSettings) {
        binding.emptyHint.isVisible = false
        binding.pdfView.isVisible = true
        binding.btnBack.isVisible = true
        binding.btnShare.isVisible = true
        binding.btnOpen.isVisible = false // с открытым документом панели и так тесно
        backCallback.isEnabled = true
        applyReadingPreferences(settings, reading = true)
    }

    /**
     * Настройки, которые касаются не самого документа, а окна вокруг него.
     * За списком недавних они не нужны, поэтому снимаются при закрытии файла.
     */
    private fun applyReadingPreferences(settings: ReaderSettings, reading: Boolean) {
        if (reading && settings.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        requestedOrientation = if (reading && settings.lockOrientation) {
            ActivityInfo.SCREEN_ORIENTATION_LOCKED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    /**
     * Тап по документу.
     *
     * Решение о панели откладываем на следующий кадр: ссылку под пальцем
     * библиотека проверяет уже после onTap, и раньше этого момента неизвестно,
     * был ли тап переходом по ссылке.
     */
    private fun onDocumentTap() {
        binding.pdfView.post {
            if (!linkTapped) toggleUi()
            linkTapped = false
        }
    }

    /** Документ открылся — теперь его можно записать в недавние. */
    private fun onDocumentLoaded(pages: Int) {
        documentLoaded = true
        pageCount = pages
        updatePageIndicator()
        binding.btnOutline.isVisible = binding.pdfView.tableOfContents.isNotEmpty()
        rememberOpened()
    }

    /**
     * Записывает открытый документ в недавние — со всем, что о нём известно.
     *
     * Если доступ к файлу выдан на один сеанс (так делают мессенджеры и почта),
     * документ сначала переносится в хранилище приложения, и список ссылается
     * уже на копию — иначе запись в нём была бы нерабочей.
     */
    private fun rememberOpened() {
        val uri = currentUri ?: return
        if (!documentLoaded) return
        if (hasDurableAccess(uri)) {
            recentFiles.add(
                uri = uri,
                name = currentName,
                page = currentPage,
                size = currentSize,
                openedAt = System.currentTimeMillis(),
            )
            return
        }
        // Имя и размер задают имя копии, без них переносить рано.
        if (metadataResolved) importCurrentDocument(uri)
    }

    /**
     * Переносит открытый документ к себе, пока его ещё читают.
     *
     * После переноса приложение работает уже с копией: её можно открыть
     * когда угодно, отдать в "Поделиться" и запомнить в ней страницу.
     */
    private fun importCurrentDocument(source: Uri) {
        if (importJob?.isActive == true) return
        val name = currentName
        val size = currentSize
        importJob = lifecycleScope.launch {
            val copy = withContext(Dispatchers.IO) {
                ImportedDocuments(this@MainActivity).copy(source, name, size)
            } ?: return@launch
            if (currentUri != source) return@launch
            currentUri = copy
            recentFiles.add(
                uri = copy,
                name = name,
                page = currentPage,
                size = size,
                openedAt = System.currentTimeMillis(),
            )
        }
    }

    private fun updatePageIndicator() {
        binding.pageIndicator.isVisible = pageCount > 0
        if (pageCount > 0) {
            binding.pageIndicator.text =
                getString(R.string.page_indicator, currentPage + 1, pageCount)
        }
    }

    private fun reportPageError(page: Int) {
        if (pageErrorReported) return
        pageErrorReported = true
        Toast.makeText(
            this,
            getString(R.string.error_page_failed, page + 1),
            Toast.LENGTH_SHORT,
        ).show()
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

    /**
     * Файла нет или доступ к нему отозван — повторять попытку бессмысленно.
     *
     * Смотрим и на причины: открытие идёт через ContentResolver, и настоящая
     * ошибка нередко завёрнута в чужое исключение.
     */
    private fun isGone(error: Throwable): Boolean {
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth++ < MAX_CAUSE_DEPTH) {
            if (cause is SecurityException || cause is FileNotFoundException) return true
            cause = cause.cause
        }
        return false
    }

    private fun askPassword(retry: Boolean) {
        showDialog(PasswordDialogFragment.TAG) { PasswordDialogFragment.newInstance(retry) }
    }

    private fun showSettingsDialog() {
        showDialog(SettingsDialogFragment.TAG) { SettingsDialogFragment() }
    }

    private fun showOutline() {
        val outline = OutlineDialogFragment.newInstance(binding.pdfView.tableOfContents) ?: return
        showDialog(OutlineDialogFragment.TAG) { outline }
    }

    private fun showJumpToPage() {
        if (pageCount <= 0) return
        showDialog(JumpToPageDialogFragment.TAG) {
            JumpToPageDialogFragment.newInstance(currentPage, pageCount)
        }
    }

    private fun confirmRemoveRecent(item: RecentFileItem) {
        showDialog(ConfirmDialogFragment.TAG) {
            ConfirmDialogFragment.newInstance(
                requestKey = REQUEST_REMOVE_RECENT,
                title = R.string.recent_remove_title,
                positive = R.string.action_remove,
                message = item.file.name,
                payload = item.file.uri,
            )
        }
    }

    private fun confirmClearRecent() {
        showDialog(ConfirmDialogFragment.TAG) {
            ConfirmDialogFragment.newInstance(
                requestKey = REQUEST_CLEAR_RECENT,
                title = R.string.recent_clear_title,
                positive = R.string.action_clear,
            )
        }
    }

    /** Показывает диалог, если это ещё уместно и такой уже не висит на экране. */
    private fun showDialog(tag: String, create: () -> androidx.fragment.app.DialogFragment) {
        if (isFinishing || supportFragmentManager.isStateSaved) return
        if (supportFragmentManager.findFragmentByTag(tag) != null) return
        create().show(supportFragmentManager, tag)
    }

    private fun listenToDialogs() {
        onDialogResult(SettingsDialogFragment.RESULT_KEY) {
            // Перечитываем с новыми настройками, текущая страница сохраняется.
            val uri = currentUri
            if (uri != null) openPdf(uri, currentPage, currentPassword)
            else applyReadingPreferences(ReaderSettings.load(this), reading = false)
        }
        onDialogResult(PasswordDialogFragment.RESULT_KEY) { result ->
            val password = result.getString(PasswordDialogFragment.EXTRA_PASSWORD)
            val uri = currentUri
            if (password.isNullOrEmpty() || uri == null) closeDocument()
            else openPdf(uri, currentPage, password)
        }
        onDialogResult(OutlineDialogFragment.RESULT_KEY) { result ->
            binding.pdfView.jumpTo(result.getInt(OutlineDialogFragment.EXTRA_PAGE), true)
        }
        onDialogResult(JumpToPageDialogFragment.RESULT_KEY) { result ->
            binding.pdfView.jumpTo(result.getInt(JumpToPageDialogFragment.EXTRA_PAGE), true)
        }
        onDialogResult(REQUEST_REMOVE_RECENT) { result ->
            result.getString(ConfirmDialogFragment.EXTRA_PAYLOAD)?.let {
                recentFiles.remove(it.toUri())
            }
            showRecentFiles()
        }
        onDialogResult(REQUEST_CLEAR_RECENT) {
            recentFiles.clear()
            showRecentFiles()
        }
    }

    private fun onDialogResult(key: String, handle: (Bundle) -> Unit) {
        supportFragmentManager.setFragmentResultListener(key, this) { _, result -> handle(result) }
    }

    private fun closeDocument() {
        rememberPosition()
        // Интент со ссылкой на документ отработал: дальше он только мешает,
        // потому что заново открывал бы уже закрытый файл.
        if (intentUri(intent) != null) intent = Intent(Intent.ACTION_MAIN)
        currentUri = null
        currentName = ""
        currentSize = 0
        currentPassword = null
        currentPage = 0
        pageCount = 0
        documentLoaded = false
        nameJob?.cancel()
        scrollHandle = null
        backCallback.isEnabled = false
        binding.pdfView.recycle()
        showRecentFiles()
    }

    private fun showName(name: String) {
        currentName = name
        binding.titleView.text = name
    }

    /** Имя и размер файла, как их видит провайдер. */
    private class DocumentMeta(val name: String?, val size: Long)

    /**
     * Уточняет имя и размер файла у провайдера в фоне.
     *
     * Запрос к чужому провайдеру (особенно к облачному) может задуматься на сотни
     * миллисекунд, поэтому документ начинает грузиться сразу, а в заголовке до
     * ответа стоит имя, вытащенное из самой ссылки. Размер нужен списку недавних:
     * по нему вместе с именем один и тот же файл узнаётся под разными ссылками.
     */
    private fun resolveMetadataAsync(uri: Uri) {
        nameJob?.cancel()
        nameJob = lifecycleScope.launch {
            val meta = withContext(Dispatchers.IO) { queryMetadata(uri) }
            if (currentUri != uri) return@launch
            meta.name?.let { showName(it) }
            currentSize = meta.size
            metadataResolved = true
            rememberOpened()
        }
    }

    private fun queryMetadata(uri: Uri): DocumentMeta {
        // У своей копии всё спрашиваем прямо у файла: провайдера для схемы
        // file нет, и запрос к ContentResolver по ней вернёт пустоту.
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val file = uri.path?.let(::File)
            return DocumentMeta(name = file?.name, size = file?.length() ?: 0)
        }
        return metadata(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            ?: metadata(uri, null) // провайдер может не понять проекцию
            ?: DocumentMeta(name = null, size = 0)
    }

    private fun metadata(uri: Uri, projection: Array<String>?): DocumentMeta? = try {
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            DocumentMeta(
                name = nameIndex.takeIf { it >= 0 }
                    ?.let { cursor.getString(it) }
                    ?.takeIf { it.isNotBlank() },
                size = sizeIndex.takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { cursor.getLong(it).coerceAtLeast(0) }
                    ?: 0,
            )
        }
    } catch (_: Exception) {
        // Ничего не отдали - обойдёмся хвостом ссылки.
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
        val uri = currentUri?.let(::shareableUri) ?: return
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

    /**
     * Ссылка, которую не стыдно отдать наружу.
     *
     * Свою копию нельзя передавать как file:// — система такое запрещает.
     * Для неё выдаём ссылку через провайдера, чужие ссылки идут как есть.
     */
    private fun shareableUri(uri: Uri): Uri? {
        if (uri.scheme != ContentResolver.SCHEME_FILE) return uri
        val file = uri.path?.let(::File) ?: return null
        return try {
            FileProvider.getUriForFile(this, "$packageName.files", file)
        } catch (_: IllegalArgumentException) {
            Toast.makeText(this, R.string.error_share_failed, Toast.LENGTH_LONG).show()
            null
        }
    }

    private fun toggleUi() {
        if (currentUri == null) return
        uiHidden = !uiHidden
        applyUiState()
    }

    private fun applyUiState() {
        val controller = WindowCompat.getInsetsController(window, binding.root)
        if (uiHidden) {
            // Прячем ВСЁ: панель приложения, ползунок, статус-бар, кнопки навигации.
            binding.topBar.isVisible = false
            scrollHandle?.locked = true
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            binding.topBar.isVisible = true
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
        val topBar = binding.topBar
        if (uiHidden) {
            binding.root.setPadding(0, 0, 0, 0)
            topBar.setPaddingRelative(topBar.paddingStart, 0, topBar.paddingEnd, topBar.paddingBottom)
        } else {
            binding.root.setPadding(barInsets.left, 0, barInsets.right, barInsets.bottom)
            topBar.setPaddingRelative(
                topBar.paddingStart,
                barInsets.top,
                topBar.paddingEnd,
                topBar.paddingBottom,
            )
        }
    }

    /** Откроется ли запись из списка: у чужого файла спрашиваем права, у своей копии — есть ли она. */
    private fun isStillAvailable(uri: String, durable: Set<String>): Boolean = when {
        uri.startsWith("${ContentResolver.SCHEME_CONTENT}:") -> uri in durable
        uri.startsWith("${ContentResolver.SCHEME_FILE}:") ->
            uri.toUri().path?.let { File(it).isFile } == true
        else -> true
    }

    private fun openRecentFile(item: RecentFileItem) {
        if (item.available) {
            openDocument(item.file.uri.toUri())
            return
        }
        Toast.makeText(this, R.string.error_file_gone, Toast.LENGTH_LONG).show()
        recentFiles.remove(item.file.uri.toUri())
        showRecentFiles()
    }

    /**
     * Когда файл открывали в прошлый раз.
     *
     * Ближние дни называем словами: «сегодня, 14:32» читается быстрее даты.
     * Время и дата форматируются по настройкам системы — включая выбор между
     * 12- и 24-часовым форматом.
     */
    private fun formatOpenedAt(openedAt: Long, now: Long, zone: ZoneId): String =
        when (openedAtBucket(openedAt, now, zone)) {
            OpenedAtBucket.UNKNOWN -> ""
            OpenedAtBucket.TODAY -> getString(R.string.opened_today, timeText(openedAt))
            OpenedAtBucket.YESTERDAY -> getString(R.string.opened_yesterday, timeText(openedAt))
            OpenedAtBucket.EARLIER -> DateUtils.formatDateTime(
                this,
                openedAt,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or
                    DateUtils.FORMAT_SHOW_TIME,
            )
        }

    private fun timeText(millis: Long): String =
        DateFormat.getTimeFormat(this).format(Date(millis))

    /** Экран без открытого документа: заголовок и список недавних файлов. */
    private fun showRecentFiles() {
        binding.emptyHint.isVisible = true
        binding.pdfView.isVisible = false
        binding.btnBack.isVisible = false
        binding.btnShare.isVisible = false
        binding.btnOutline.isVisible = false
        binding.pageIndicator.isVisible = false
        binding.btnOpen.isVisible = true
        binding.titleView.setText(R.string.app_name)
        uiHidden = false // к списку всегда возвращаемся с видимыми барами
        applyUiState()
        applyReadingPreferences(ReaderSettings.load(this), reading = false)

        val durable = durableUris()
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val items = recentFiles.load().map { file ->
            RecentFileItem(
                file = file,
                folder = readablePath(file.uri),
                openedAt = formatOpenedAt(file.openedAt, now, zone),
                available = isStillAvailable(file.uri, durable),
            )
        }
        recentAdapter.submitList(items)
        binding.tvNoRecent.isVisible = items.isEmpty()
        binding.btnClearRecent.isVisible = items.isNotEmpty()
    }
}
