package net.aprlflwrs.aunthillary

import net.aprlflwrs.aunthillary.core.Params
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class ParamsTest {
    @Test
    fun `values start at the default, clamp to the range, and bump the version`() {
        val params = Params()
        val p = params.declare("a.b", "A B", "A", min = 0.0, max = 1.0, step = 0.1, default = 0.5)
        assertEquals(0.5, p.value)

        val before = params.version
        params.set("a.b", 7.0)
        assertEquals(1.0, p.value)
        assertNotEquals(before, params.version)

        assertFalse(params.set("missing", 1.0))
        params.resetToDefaults()
        assertEquals(0.5, p.value)
    }

    @Test
    fun `a key can only be declared once`() {
        val params = Params()
        params.declare("a", "A", "G", 0.0, 1.0, 0.1, 0.5)
        assertFailsWith<IllegalArgumentException> { params.declare("a", "A", "G", 0.0, 1.0, 0.1, 0.5) }
    }

    @Test
    fun `specs keep declaration order`() {
        val params = Params()
        params.declare("z", "Z", "G", 0.0, 1.0, 0.1, 0.5)
        params.declare("a", "A", "G", 0.0, 1.0, 0.1, 0.5)
        assertEquals(listOf("z", "a"), params.specs().map { it.key })
    }
}
