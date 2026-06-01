package ceui.pixiv.ui.search.v3

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定搜索「R-18 限制」三档的客户端过滤语义（按真实 x_restrict，不碰 sanity_level）。
 * 这是修「全年龄和 R 混在一起」的核心判定，旧版靠关键字 hack 才会混。
 */
class R18ModeFilterTest {

    @Test
    fun all_keepsEverything() {
        // 全部档：任何 x_restrict（含 null/缺失）都保留 —— 默认档绝不误伤普通插画
        assertTrue(R18Mode.All.accepts(null))
        assertTrue(R18Mode.All.accepts(0))
        assertTrue(R18Mode.All.accepts(1))
        assertTrue(R18Mode.All.accepts(2))
    }

    @Test
    fun safeOnly_keepsOnlyAllAges() {
        // 仅安全：只留 x_restrict<=0；1=R-18、2=R-18G 都去掉。缺失当全年龄保留。
        assertTrue(R18Mode.SafeOnly.accepts(null))
        assertTrue(R18Mode.SafeOnly.accepts(0))
        assertFalse(R18Mode.SafeOnly.accepts(1))
        assertFalse(R18Mode.SafeOnly.accepts(2))
    }

    @Test
    fun r18Only_keepsOnlyR18() {
        // 仅 R-18：只留 x_restrict>0；全年龄(0)与缺失都去掉
        assertFalse(R18Mode.R18Only.accepts(null))
        assertFalse(R18Mode.R18Only.accepts(0))
        assertTrue(R18Mode.R18Only.accepts(1))
        assertTrue(R18Mode.R18Only.accepts(2))
    }

    @Test
    fun safeAndR18_arePartition_forKnownRating() {
        // 对有明确分级的作品，仅安全与仅 R-18 互补、不重叠（不会混在一起，也不会两边都漏）
        for (x in intArrayOf(0, 1, 2)) {
            assertTrue(R18Mode.SafeOnly.accepts(x) != R18Mode.R18Only.accepts(x))
        }
    }
}
