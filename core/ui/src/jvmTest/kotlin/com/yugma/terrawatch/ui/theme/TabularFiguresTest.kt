package com.yugma.terrawatch.ui.theme

import androidx.compose.ui.text.TextStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Task 10 (item f): pins [TextStyle.tabularFigures]'s own contract — the one line of real logic
 * behind an otherwise-Compose-only feature, and cheap enough to test directly (no composition
 * needed, `TextStyle.copy` is plain data-class code).
 */
class TabularFiguresTest {
    @Test
    fun `tabularFigures sets the tnum feature on a style that had none`() {
        assertNull(TextStyle.Default.fontFeatureSettings)
        assertEquals("tnum", TextStyle.Default.tabularFigures().fontFeatureSettings)
    }

    @Test
    fun `tabularFigures preserves the rest of the style untouched`() {
        val base = TextStyle.Default.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        val result = base.tabularFigures()
        assertEquals(androidx.compose.ui.text.font.FontWeight.Bold, result.fontWeight)
        assertEquals("tnum", result.fontFeatureSettings)
    }
}
