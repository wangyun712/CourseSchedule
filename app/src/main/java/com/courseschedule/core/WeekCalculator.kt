package com.courseschedule.core

import com.courseschedule.data.entity.CourseSlot
import com.courseschedule.data.entity.WeekType
import java.time.LocalDate

/**
 * 周次计算的唯一来源：「现在是第几周」「这节课这周上不上」只由这里回答，算错会整表静默错位且无报错、最难查。
 * 全部纯函数：不读系统时间（"今天"由参数传入）、不碰数据库和 Context，因此能在纯 JVM 上直接单测。
 * 全程用 epochDay（距 1970-01-01 的天数）而非毫秒时间戳：毫秒带时区含义，夏令时切换那天会差一小时导致周次跳变，
 * 时区/夏令时在进入本文件前已由 `LocalDate` 的 epochDay 换算消掉。与界面毫秒值的互转只发生在 `DatePickerDialogM3`。
 */
object WeekCalculator {

    /**
     * 某个日期落在第几周（1-based）：`(date - start) / 7 + 1`。
     *
     * @param startEpochDay 第 1 周周一的 epochDay，**必须传周一**（建学期时已用 [mondayOf] 对齐），否则整体错位。
     * @return 结果一定落在 `1..totalWeeks`：早于开学夹到 1、超出总周数夹到 totalWeeks，调用方无需再判边界。
     *
     * 必须用 `Math.floorDiv` 而非 `/`：开学前差值为负时 `/` 向零取整，差值 -1~-6 表面仍是第 1 周、
     * 到 -7 却突然变第 0 周越界；`floorDiv` 让负数区间也单调，再被 coerceIn 夹住结果才稳定。
     * 例：`weekOf(20738, 20745, 20) == 2`（第 8 天进入第 2 周）。
     */
    fun weekOf(startEpochDay: Long, dateEpochDay: Long, totalWeeks: Int): Int {
        val raw = Math.floorDiv(dateEpochDay - startEpochDay, 7L) + 1L
        return raw.coerceIn(1L, totalWeeks.toLong()).toInt()
    }

    /**
     * 当前是第几周，界面入口：在 [weekOf] 基础上支持"手动覆盖"（假期调课时实际周次与开学日期算的对不上）。
     *
     * `weekOverride` 非 null 时完全以它为准，开学日期不参与计算；override 不在这里做范围校验，
     * 落库前已在设置页用 coerceIn 夹过（见 `SettingsViewModel.save`），别处直接写该字段需自行保证在 1..totalWeeks。
     * `todayEpochDay` 由参数传入而非内部调 `LocalDate.now()`，为让测试能模拟任意一天。
     */
    fun currentWeek(
        startEpochDay: Long,
        totalWeeks: Int,
        weekOverride: Int? = null,
        todayEpochDay: Long = LocalDate.now().toEpochDay()
    ): Int = weekOverride ?: weekOf(startEpochDay, todayEpochDay, totalWeeks)

    /**
     * 第 [week] 周的周一：`startEpochDay + (week - 1) * 7`。用于把"第 N 周"翻译成表头要显示的 7 个日期
     * （见 `ScheduleViewModel` 的 `weekStartEpochDay`）。不做边界检查，调用方须保证 week 合法
     * （界面上翻页时已用 coerceIn 夹过）。
     */
    fun weekStartEpochDay(startEpochDay: Long, week: Int): Long =
        startEpochDay + (week - 1) * 7L

    /**
     * 星期几：`1 = 周一` … `7 = 周日`（ISO-8601，与周视图从左到右的列序一致）。委托 `DayOfWeek.value`，不自己算。
     * 注意周日是 7 不是 0/1：与 `java.util.Calendar`（周日 = 1）等来源的星期值混用，会静默把整张课表错开一天。
     */
    fun dayOfWeek(epochDay: Long): Int = LocalDate.ofEpochDay(epochDay).dayOfWeek.value

    /**
     * 把任意一天对齐到它所在周的周一。建学期时必须经此函数（见 `ScheduleRepository.ensureSemester`），
     * 保证 `startEpochDay` 永远是周一——否则今天是周三时，整学期的周次会全部偏两天。
     */
    fun mondayOf(epochDay: Long): Long = epochDay - (dayOfWeek(epochDay) - 1)

    /**
     * 第 [week] 周是单周还是双周。**第 1 周固定算单周**（国内课表通行约定）：1→ODD、2→EVEN、3→ODD……
     * 只返回 ODD/EVEN，永远不会返回 ALL——ALL 是课程时间段的属性（每周都上），不是某一周的属性，别看混。
     */
    fun weekTypeOf(week: Int): WeekType = if (week % 2 == 0) WeekType.EVEN else WeekType.ODD

    /**
     * 课程时间段在第 [week] 周是否生效，周视图"画不画课程块"的唯一判据。
     * 须同时满足：week 在 `startWeek..endWeek` 内，且单双周属性与该周奇偶匹配（ALL 视为永远匹配）。
     * 只判周次不判星期几——"是不是周三的课"由调用方按 `slot.dayOfWeek` 分列时自行比对（见
     * `ScheduleViewModel.activeCourses`）。保持单时间段粒度，UI 层才能自由组合、也才能单测。
     */
    fun isSlotActive(slot: CourseSlot, week: Int): Boolean {
        if (week < slot.startWeek || week > slot.endWeek) return false
        return when (slot.weekType) {
            WeekType.ALL -> true
            else -> slot.weekType == weekTypeOf(week)
        }
    }

    /**
     * 时间段是否覆盖第 [section] 节。目前无业务调用（上课提醒方向在 v1.1 已取消），作为工具函数保留，
     * 与 [isSlotActive] 配合使用：先判周次、再判节次。项目刻意不做冲突检测——学生自己清楚课表，
     * 强行拦截反而挡住"同一时段两门课"的真实需求。
     */
    fun coversSection(slot: CourseSlot, section: Int): Boolean =
        section in slot.startSection..slot.endSection
}
