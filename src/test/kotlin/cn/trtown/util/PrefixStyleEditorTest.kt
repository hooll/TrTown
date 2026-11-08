package cn.trtown.util

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrefixStyleEditorTest {

    private fun chars(source: String) = assertNotNull(PrefixStyleEditor.decode(source, allowColor = true))

    @Test
    fun `解码颜色与格式并在摘除格式时重发颜色`() {
        val decoded = chars("&c&la&cb")
        assertEquals("&c", decoded[0].color)
        assertTrue('l' in decoded[0].formats)
        assertEquals("&c", decoded[1].color)
        assertEquals("&c&la&cb", PrefixStyleEditor.render(decoded, allowColor = true))
    }

    @Test
    fun `颜色变化会重置旧格式再补回目标格式`() {
        val decoded = chars("&c&la&a&lb")
        assertEquals("&c&la&a&lb", PrefixStyleEditor.render(decoded, allowColor = true))
    }

    @Test
    fun `默认色用 r 重置`() {
        val decoded = chars("&c&la")
        val changed = decoded.map { it.copy(formats = emptySet()) }
        assertEquals("&ca", PrefixStyleEditor.render(changed, allowColor = true))
    }

    @Test
    fun `格式开关按选区整体状态切换`() {
        val decoded = chars("abc")
        val on = PrefixStyleEditor.toggleFormat(decoded, 0..1, 'l')
        assertEquals(setOf('l'), on[0].formats)
        assertEquals(setOf('l'), on[1].formats)
        assertEquals(emptySet(), on[2].formats)
        val off = PrefixStyleEditor.toggleFormat(on, 0..1, 'l')
        assertEquals(emptySet(), off[0].formats)
        val mixed = PrefixStyleEditor.formatState(on, 0..2, 'l')
        assertEquals(PrefixStyleEditor.FormatState.MIXED, mixed)
    }

    @Test
    fun `渐变端点与中间值准确且保留格式`() {
        val decoded = chars("&lABC")
        val gradient = assertNotNull(PrefixStyleEditor.withGradient(decoded, 0..2, "FF0000", "0000FF"))
        assertEquals("&#FF0000", gradient[0].color)
        assertEquals("&#800080", gradient[1].color)
        assertEquals("&#0000FF", gradient[2].color)
        assertTrue(gradient.all { 'l' in it.formats })
    }

    @Test
    fun `单字渐变取起点`() {
        val decoded = chars("A")
        val gradient = assertNotNull(PrefixStyleEditor.withGradient(decoded, 0..0, "55FFFF", "5555FF"))
        assertEquals("&#55FFFF", gradient[0].color)
    }

    @Test
    fun `清除只影响选区`() {
        val decoded = chars("&c&lA&aB&lC")
        val cleared = PrefixStyleEditor.cleared(decoded, 1..1)
        assertEquals("&c", cleared[0].color)
        assertEquals("&f", cleared[1].color)
        assertEquals(emptySet(), cleared[1].formats)
        assertEquals("&a", cleared[2].color)
        assertTrue('l' in cleared[2].formats)
    }

    @Test
    fun `显式白色与未指定颜色必须区分`() {
        val inherit = chars("AB")
        assertNull(inherit[0].color)
        assertEquals("AB", PrefixStyleEditor.render(inherit, allowColor = true))

        val white = chars("&fAB")
        assertEquals("&f", white[0].color)
        assertEquals("&f", white[1].color)
        assertEquals("&fAB", PrefixStyleEditor.render(white, allowColor = true))

        // 白色色板在非白外壳里也必须是显式 &f，而不是省略颜色码
        val fromRed = chars("&cAB")
        val painted = PrefixStyleEditor.withColor(fromRed, 0..1, "&f")!!
        assertEquals("&fAB", PrefixStyleEditor.render(painted, allowColor = true))
    }

    @Test
    fun `局部上色会把范围外未指定颜色落成外壳色`() {
        val decoded = chars("ABC")
        val painted = PrefixStyleEditor.withColor(decoded, 1..1, "&c", "&b")!!
        assertEquals("&bA&cB&bC", PrefixStyleEditor.render(painted, allowColor = true))
    }

    @Test
    fun `清除样式回到外壳色`() {
        val decoded = chars("&c&lA&aB")
        val cleared = PrefixStyleEditor.cleared(decoded, 0..0, "&b")
        assertEquals("&bA&aB", PrefixStyleEditor.render(cleared, allowColor = true))
    }

    @Test
    fun `重置码按显式白色保留`() {
        val decoded = chars("&rAB")
        assertEquals("&f", decoded[0].color)
        assertEquals("&f", decoded[1].color)
        assertEquals("&fAB", PrefixStyleEditor.render(decoded, allowColor = true))
    }

    @Test
    fun `emoji 按单个 code point 解码`() {
        val decoded = chars("😀A")
        assertEquals(2, decoded.size)
        assertEquals("😀", decoded[0].value)
    }

    @Test
    fun `allow-color 关闭时解码与序列化都去色`() {
        val decoded = assertNotNull(PrefixStyleEditor.decode("&c&lA", allowColor = false))
        assertEquals("A", PrefixStyleEditor.render(decoded, allowColor = false))
        assertNull(decoded[0].color)
        assertTrue(decoded[0].formats.isEmpty())
    }

    @Test
    fun `选区反向与越界归一`() {
        assertEquals(1..3, PrefixStyleEditor.normalizeRange(3, 1, 5))
        assertEquals(0..4, PrefixStyleEditor.normalizeRange(-3, 99, 5))
        assertNull(PrefixStyleEditor.normalizeRange(0, 0, 0))
    }

    @Test
    fun `传统色可转 RGB`() {
        assertEquals("FF5555", PrefixStyleEditor.rgbOf("&c"))
        assertEquals("55FFFF", PrefixStyleEditor.rgbOf("&#55ffff"))
        assertEquals("55FFFF", PrefixStyleEditor.rgbOf("55FFFF"))
        assertNull(PrefixStyleEditor.rgbOf("&z"))
    }

    @Test
    fun `渐变插值通道四舍五入`() {
        assertEquals("800080", PrefixStyleEditor.gradientHex("FF0000", "0000FF", 1, 3))
        assertEquals("FF0000", PrefixStyleEditor.gradientHex("FF0000", "0000FF", 0, 3))
        assertEquals("0000FF", PrefixStyleEditor.gradientHex("FF0000", "0000FF", 2, 3))
    }
}
