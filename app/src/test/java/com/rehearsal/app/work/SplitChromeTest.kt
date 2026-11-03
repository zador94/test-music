package com.rehearsal.app.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitChromeTest {
    @Test
    fun windowTopHasNoProcessingDump() {
        assertEquals("", SplitChrome.windowStatusLine())
        assertFalse(SplitChrome.isDebugDump(SplitChrome.windowStatusLine()))
        assertFalse(SplitChrome.isDebugDump(SplitChrome.NOTIFICATION_TEXT))
        assertFalse(SplitChrome.isDebugDump(SplitChrome.NOTIFICATION_TITLE))
    }

    @Test
    fun workerDebugLinesAreDumpsNotWindowCopy() {
        assertTrue(SplitChrome.isDebugDump("Чанк 3 из 40 (по 7.8 с)"))
        assertTrue(SplitChrome.isDebugDump("HT-Demucs: скачивание модели / разделение"))
        assertTrue(SplitChrome.isDebugDump("В очереди WorkManager"))
        assertTrue(SplitChrome.isDebugDump("Минуты, телефон греется."))
        assertFalse(SplitChrome.isDebugDump(SplitChrome.progressCaption(40)))
        assertEquals("40 %", SplitChrome.progressCaption(40))
        assertEquals("Разделение…", SplitChrome.progressCaption(0))
    }
}
