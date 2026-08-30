package com.example.pdfreader

import android.content.Context
import com.github.barteksc.pdfviewer.scroll.DefaultScrollHandle

/**
 * Ползунок прокрутки, который умеет молчать.
 *
 * DefaultScrollHandle внутри setScroll() сам вызывает show() при каждой прокрутке,
 * поэтому обычный hide() не работает: ползунок тут же вылезает обратно.
 * Флаг [locked] блокирует показ намертво — это нужно в режиме чтения,
 * когда с экрана убрано всё, кроме самого документа.
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
