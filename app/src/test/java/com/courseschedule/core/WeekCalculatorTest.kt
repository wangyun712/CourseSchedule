package com.courseschedule.core

import com.courseschedule.data.entity.CourseSlot
import com.courseschedule.data.entity.WeekType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 周次计算是全 app 正确性的地基，这里把边界情况全部钉死。
 * 纯 JVM 测试，不需要模拟器，`gradlew test` 秒级出结果。
 */
class WeekCalculatorTest {

    /** 2026-09-07 是周一，作为第 1 周起点 */
    private val start = LocalDate.of(2026, 9, 7).toEpochDay()

    private fun day(year: Int, month: Int, dayOfMonth: Int): Long =
        LocalDate.of(year, month, dayOfMonth).toEpochDay()

    @Test
    fun `开学当天算第 1 周`() {
        assertEquals(1, WeekCalculator.weekOf(start, day(2026, 9, 7), 20))
    }

    @Test
    fun `第 1 周最后一天仍算第 1 周`() {
        assertEquals(1, WeekCalculator.weekOf(start, day(2026, 9, 13), 20))
    }

    @Test
    fun `第 8 天进入第 2 周`() {
        assertEquals(2, WeekCalculator.weekOf(start, day(2026, 9, 14), 20))
    }

    @Test
    fun `早于开学日期按第 1 周处理`() {
        assertEquals(1, WeekCalculator.weekOf(start, day(2026, 8, 20), 20))
    }

    @Test
    fun `超出总周数封顶在最后一周`() {
        assertEquals(20, WeekCalculator.weekOf(start, day(2027, 3, 1), 20))
    }

    @Test
    fun `手动覆盖优先于自动计算`() {
        val week = WeekCalculator.currentWeek(
            startEpochDay = start,
            totalWeeks = 20,
            weekOverride = 9,
            todayEpochDay = day(2026, 9, 8)
        )
        assertEquals(9, week)
    }

    @Test
    fun `没有覆盖时按日期自动算`() {
        val week = WeekCalculator.currentWeek(
            startEpochDay = start,
            totalWeeks = 20,
            weekOverride = null,
            todayEpochDay = day(2026, 9, 21)
        )
        assertEquals(3, week)
    }

    @Test
    fun `第 N 周的周一日期算得对`() {
        assertEquals(day(2026, 9, 7), WeekCalculator.weekStartEpochDay(start, 1))
        assertEquals(day(2026, 9, 14), WeekCalculator.weekStartEpochDay(start, 2))
        assertEquals(day(2026, 10, 12), WeekCalculator.weekStartEpochDay(start, 6))
    }

    @Test
    fun `星期几映射周一为 1 周日为 7`() {
        assertEquals(1, WeekCalculator.dayOfWeek(day(2026, 9, 7)))
        assertEquals(3, WeekCalculator.dayOfWeek(day(2026, 9, 9)))
        assertEquals(7, WeekCalculator.dayOfWeek(day(2026, 9, 13)))
    }

    @Test
    fun `任意日期都能归到所在周的周一`() {
        assertEquals(day(2026, 9, 7), WeekCalculator.mondayOf(day(2026, 9, 7)))
        assertEquals(day(2026, 9, 7), WeekCalculator.mondayOf(day(2026, 9, 10)))
        assertEquals(day(2026, 9, 7), WeekCalculator.mondayOf(day(2026, 9, 13)))
        assertEquals(day(2026, 9, 14), WeekCalculator.mondayOf(day(2026, 9, 14)))
    }

    @Test
    fun `奇偶周判定`() {
        assertEquals(WeekType.ODD, WeekCalculator.weekTypeOf(1))
        assertEquals(WeekType.EVEN, WeekCalculator.weekTypeOf(2))
        assertEquals(WeekType.ODD, WeekCalculator.weekTypeOf(19))
        assertEquals(WeekType.EVEN, WeekCalculator.weekTypeOf(20))
    }

    @Test
    fun `每周类型的时间段周周生效`() {
        val slot = slotOf(weekType = WeekType.ALL)
        assertTrue(WeekCalculator.isSlotActive(slot, 1))
        assertTrue(WeekCalculator.isSlotActive(slot, 6))
        assertTrue(WeekCalculator.isSlotActive(slot, 20))
    }

    @Test
    fun `单周课程只在奇数周生效`() {
        val slot = slotOf(weekType = WeekType.ODD)
        assertTrue(WeekCalculator.isSlotActive(slot, 1))
        assertFalse(WeekCalculator.isSlotActive(slot, 2))
        assertTrue(WeekCalculator.isSlotActive(slot, 3))
    }

    @Test
    fun `双周课程只在偶数周生效`() {
        val slot = slotOf(weekType = WeekType.EVEN)
        assertFalse(WeekCalculator.isSlotActive(slot, 1))
        assertTrue(WeekCalculator.isSlotActive(slot, 2))
        assertFalse(WeekCalculator.isSlotActive(slot, 5))
    }

    @Test
    fun `周次范围之外不生效`() {
        val slot = slotOf(startWeek = 3, endWeek = 8, weekType = WeekType.ALL)
        assertFalse(WeekCalculator.isSlotActive(slot, 2))
        assertTrue(WeekCalculator.isSlotActive(slot, 3))
        assertTrue(WeekCalculator.isSlotActive(slot, 8))
        assertFalse(WeekCalculator.isSlotActive(slot, 9))
    }

    @Test
    fun `单周加周次范围双重限制`() {
        val slot = slotOf(startWeek = 5, endWeek = 12, weekType = WeekType.ODD)
        assertFalse(WeekCalculator.isSlotActive(slot, 4))
        assertTrue(WeekCalculator.isSlotActive(slot, 5))
        assertFalse(WeekCalculator.isSlotActive(slot, 6))
        assertTrue(WeekCalculator.isSlotActive(slot, 11))
        assertFalse(WeekCalculator.isSlotActive(slot, 13))
    }

    @Test
    fun `节次覆盖判断`() {
        val slot = slotOf(startSection = 3, endSection = 5)
        assertFalse(WeekCalculator.coversSection(slot, 2))
        assertTrue(WeekCalculator.coversSection(slot, 3))
        assertTrue(WeekCalculator.coversSection(slot, 4))
        assertTrue(WeekCalculator.coversSection(slot, 5))
        assertFalse(WeekCalculator.coversSection(slot, 6))
    }

    private fun slotOf(
        startSection: Int = 1,
        endSection: Int = 2,
        startWeek: Int = 1,
        endWeek: Int = 20,
        weekType: WeekType = WeekType.ALL
    ) = CourseSlot(
        courseId = 1L,
        dayOfWeek = 1,
        startSection = startSection,
        endSection = endSection,
        startWeek = startWeek,
        endWeek = endWeek,
        weekType = weekType
    )
}
