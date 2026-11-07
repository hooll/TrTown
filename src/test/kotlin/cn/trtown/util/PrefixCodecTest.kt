package cn.trtown.util

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrefixCodecTest {

    @Test
    fun `传统颜色与格式规范化`() {
        val result = PrefixCodec.lex("§bA&lB&rC")
        assertNull(result.issue)
        assertEquals("&bA&lB&rC", PrefixCodec.sourceOf(result.tokens))
        assertEquals("ABC", PrefixCodec.plainOf(result.tokens))
        assertEquals(3, PrefixCodec.visibleLength(result.tokens))
    }

    @Test
    fun `HEX 两种写法统一为大写规范串`() {
        val short = PrefixCodec.lex("&#ff00aa")
        assertEquals("&#FF00AA", PrefixCodec.sourceOf(short.tokens))
        val traditional = PrefixCodec.lex("&x&f&f&0&0&a&a")
        assertEquals("&#FF00AA", PrefixCodec.sourceOf(traditional.tokens))
        assertEquals("§x§F§F§0§0§A§A", PrefixCodec.sectionOf(traditional.tokens))
    }

    @Test
    fun `残缺颜色指令报错且不算正文`() {
        assertTrue(PrefixCodec.lex("abc&").issue == PrefixIssue.INVALID_COLOR)
        assertTrue(PrefixCodec.lex("&#12").issue == PrefixIssue.INVALID_COLOR)
        assertTrue(PrefixCodec.lex("&#GGGGGG").issue == PrefixIssue.INVALID_COLOR)
        assertTrue(PrefixCodec.lex("&x&F&F").issue == PrefixIssue.INVALID_COLOR)
    }

    @Test
    fun `普通与号按文字保留`() {
        val result = PrefixCodec.lex("A&z&Q")
        assertNull(result.issue)
        assertEquals("A&z&Q", PrefixCodec.plainOf(result.tokens))
        assertEquals(5, PrefixCodec.visibleLength(result.tokens))
    }

    @Test
    fun `可见字数按 code point 且颜色不计数`() {
        val result = PrefixCodec.lex("&a😀&l镇")
        assertNull(result.issue)
        assertEquals(2, PrefixCodec.visibleLength(result.tokens))
        assertEquals("😀镇", PrefixCodec.plainOf(result.tokens))
    }

    @Test
    fun `孤立代理项报错`() {
        assertTrue(PrefixCodec.lex("a\uD83Db").issue == PrefixIssue.INVALID_CHARACTER)
    }

    @Test
    fun `raw 硬上限在解析前拒绝`() {
        val raw = "a".repeat(PrefixCodec.MAX_RAW_UNITS + 1)
        assertEquals(PrefixIssue.RAW_TOO_LONG, PrefixCodec.lex(raw).issue)
    }

    @Test
    fun `去色只保留文字`() {
        assertEquals("A镇", PrefixCodec.stripColors("&aA&#FF0000镇&l"))
        assertEquals("A&z", PrefixCodec.stripColors("&aA&z"))
    }

    @Test
    fun `HEX 规范化允许一个前导井号`() {
        assertEquals("FF00AA", PrefixCodec.normalizeHex("#ff00aa"))
        assertEquals("FF00AA", PrefixCodec.normalizeHex("FF00AA"))
        assertNull(PrefixCodec.normalizeHex("##FF00AA"))
        assertNull(PrefixCodec.normalizeHex("FF00A"))
    }

    @Test
    fun `百分号无条件拒绝`() {
        assertEquals("%", PrefixCodec.deniedChar("a%b", emptyList()))
        assertEquals("[", PrefixCodec.deniedChar("a[b", listOf("%", "[")))
    }
}
