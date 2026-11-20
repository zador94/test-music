package com.rehearsal.app.work

/**
 * Copy policy for stem split. Progress lives in [com.rehearsal.app.ui.processing.ProcessingScreen].
 * The window chrome / FGS banner must not dump worker debug lines
 * («Чанк 3 из 40», «телефон греется») at the top of the screen.
 */
object SplitChrome {
    const val NOTIFICATION_TITLE = "Репетиция"
    const val NOTIFICATION_TEXT = "Разделение дорожек"

    /** Status line in the window top overlay — always empty. */
    fun windowStatusLine(): String = ""

    fun progressCaption(percent: Int): String =
        if (percent in 1..99) "$percent %" else "Разделение…"

    fun isDebugDump(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("чанк") ||
            t.contains("греет") ||
            t.contains("workmanager") ||
            t.contains("ht-demucs:") ||
            t.contains("7.8") ||
            t.contains("onnx") ||
            t.contains("скачиваю")
    }
}
