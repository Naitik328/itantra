package com.sih.itantra.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageTest {

    @Test
    fun `wire codes are unique`() {
        val codes = Language.entries.map { it.code }
        assertEquals("every language needs a distinct lang byte", codes.size, codes.toSet().size)
    }

    @Test
    fun `codes fit in the single lang byte`() {
        assertTrue(Language.entries.all { it.code in 0..255 })
    }

    @Test
    fun `fromCode round-trips every language`() {
        for (language in Language.entries) {
            assertSame(language, Language.fromCode(language.code))
        }
    }

    @Test
    fun `default is Hindi, the one bundled voice`() {
        assertSame(Language.HINDI, Language.DEFAULT)
        assertEquals(0, Language.HINDI.code)
    }

    @Test
    fun `unknown code falls back to default rather than dropping the frame`() {
        assertSame(Language.DEFAULT, Language.fromCode(200))
        assertSame(Language.DEFAULT, Language.fromCode(-1))
    }
}
