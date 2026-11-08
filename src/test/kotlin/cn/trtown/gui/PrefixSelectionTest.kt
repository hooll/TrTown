package cn.trtown.gui

import cn.trtown.model.TownRole
import cn.trtown.service.PrefixRules
import cn.trtown.util.PrefixStyleEditor
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PrefixSelectionTest {

    private val rules = PrefixRules(
        enabled = true,
        maxLength = 16,
        allowColor = true,
        displayFormat = "&8[%s] &r",
        fallbackToName = true,
        denyChars = listOf("%"),
    )

    private fun session(text: String): PrefixEditSession {
        val chars = PrefixStyleEditor.decode(text, true)!!
        return PrefixEditSession(
            uuid = UUID.randomUUID(),
            townId = 1,
            townName = "青云镇",
            openedPrefix = null,
            role = TownRole.MAYOR,
            rules = rules,
            configGeneration = 0,
            shellColor = "&f",
            initialChars = chars,
            unreadable = false,
        ).also { it.selectAll() }
    }

    @Test
    fun `第一次点字是单字选区并记锚点`() {
        val session = session("青云小镇")
        session.clickCharacter(1)
        assertEquals(1..1, session.selection())
        assertEquals(1, session.anchor)
    }

    @Test
    fun `第二次点字与锚点组成闭区间并清锚点`() {
        val session = session("青云小镇")
        session.clickCharacter(1)
        session.clickCharacter(3)
        assertEquals(1..3, session.selection())
        assertNull(session.anchor)
    }

    @Test
    fun `反向选择归一`() {
        val session = session("青云小镇")
        session.clickCharacter(3)
        session.clickCharacter(1)
        assertEquals(1..3, session.selection())
    }

    @Test
    fun `全选清锚点`() {
        val session = session("青云小镇")
        session.clickCharacter(2)
        session.selectAll()
        assertEquals(0..3, session.selection())
        assertNull(session.anchor)
    }

    @Test
    fun `emoji 作为一个字参与选择`() {
        val session = session("😀青")
        assertEquals(2, session.chars.size)
        session.clickCharacter(0)
        session.clickCharacter(1)
        assertEquals(0..1, session.selection())
    }

    @Test
    fun `未改动草稿保存等同原值`() {
        val session = session("青云")
        assertNull(session.previewRaw)
        assertEquals(false, session.draftChanged)
    }

    @Test
    fun `应用样式后标记改动`() {
        val session = session("青云")
        val range = session.selection()!!
        session.applyChars(PrefixStyleEditor.withColor(session.chars, range, "&a")!!)
        assertEquals(true, session.draftChanged)
        assertEquals(2, session.chars.size)
    }
}
