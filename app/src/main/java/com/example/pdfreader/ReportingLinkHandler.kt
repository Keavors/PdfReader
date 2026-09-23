package com.example.pdfreader

import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.link.DefaultLinkHandler
import com.github.barteksc.pdfviewer.link.LinkHandler
import com.github.barteksc.pdfviewer.model.LinkTapEvent

/**
 * Обычная обработка ссылок плюс сигнал наружу о том, что тап пришёлся на ссылку.
 *
 * Библиотека проверяет ссылку уже ПОСЛЕ вызова onTap, поэтому сам onTap отличить
 * тап по ссылке от тапа по пустому месту страницы не может — и без этого сигнала
 * переход по ссылке заодно прятал бы верхнюю панель.
 */
class ReportingLinkHandler(
    pdfView: PDFView,
    private val onLinkTapped: () -> Unit,
) : LinkHandler {

    private val delegate = DefaultLinkHandler(pdfView)

    override fun handleLinkEvent(event: LinkTapEvent) {
        onLinkTapped()
        delegate.handleLinkEvent(event)
    }
}
