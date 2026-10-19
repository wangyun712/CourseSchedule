package com.courseschedule.core

import com.courseschedule.data.entity.CourseSlot
import com.courseschedule.data.entity.WeekType
import java.time.LocalDate

/**
 * 周次计算的唯一来源。
 *
 * 整个 app 里「现在是第几周」「这节课这周上不上」这两个问题，只能由这里回答，
 * 其他任何地方都不许自己算。原因是这两个答案错了，课表就整体错位——
 * 而错位这种 bug 不会崩溃、不会报错，只会安静地显示错误内容，最难查。
 *
 * ## 为什么抽成一个 object 而不是写在 ViewModel 里
 *
 * 因为它要能在**没有 Android 环境的情况下跑测试**。写在这里之后，
 * `app/src/test/.../WeekCalculatorTest.kt` 可以直接用 JVM 跑 17 个用例，
 * 不需要装模拟器、不需要 Robolectric，点一下 1 秒出结果。
 * 如果这些逻辑写在 ViewModel 或 Composable 里，就必须上真机才能验，反馈慢十倍。
 *
 * ## 为什么每个函数都是"纯函数"
 *
 * 纯函数 = 同样的输入永远给同样的输出，且不读写任何外部状态。
 * 具体到这里的做法是两条纪律：
 *
 * 1. **不读系统时间**。需要"今天"的地方一律通过参数传进来（[currentWeek] 的
 *    `todayEpochDay` 参数给了默认值，但测试里可以传任意日期）。
 *    如果这里直接写 `LocalDate.now()` 写死在函数体里，测试就没法验
 *    "2026 年 3 月 1 日应该是第几周"。
 * 2. **不碰数据库、不碰 Context**。所以本文件只 import 了 `java.time` 和两个数据类，
 *    没有任何 `android.*` 或 `androidx.*`。这一点保证它可以在纯 JVM 上运行。
 *
 * ## 一个必须知道的约定：全程用 epochDay，不用毫秒时间戳
 *
 * `epochDay` 是"从 1970-01-01 起过了多少天"，一个 `Long`。
 * 用它而不是 `System.currentTimeMillis()` 的原因是：
 *
 * - 毫秒时间戳带时区含义，同一时刻在不同时区可能落在不同日期；
 * - 毫秒级运算容易在夏令时切换那天少一小时或多一小时，导致"周次跳变"这种诡异现象；
 * - `LocalDate.toEpochDay()` / `LocalDate.ofEpochDay()` 负责全部换算，
 *   时区和夏令时问题在进入本文件之前就已经被消掉了。
 *
 * 需要和界面上的毫秒值互转时，看 [com.courseschedule.ui.DatePickerDialogM3]，
 * 那里是唯一做这个转换的地方。
 */
object WeekCalculator {

    /**
     * 某个日期落在第几周（从 1 开始）。
     *
     * 算法就是最朴素的"过了几个整周再加 1"：`(目标日 - 开学日) / 7 + 1`，向下取整。
     *
     * @param startEpochDay 第 1 周周一的 epochDay。**必须传周一**，
     *   如果传的是周三，后面所有日期都会错位两天。建学期时用 [mondayOf] 对齐过。
     * @param dateEpochDay 要计算的日期。
     * @param totalWeeks 学期总周数，用于封顶。
     *
     * @return 周次，结果一定落在 `1..totalWeeks` 之内。
     *   开学日期之前的日期返回 `1`，超出总周数的返回 `totalWeeks`。
     *
     * ## 为什么用 Math.floorDiv 而不是 Kotlin 的除号 `/`
     *
     * 这是本文件最容易写错的一行。用户在 3 月建了一个 9 月才开学的学期时，
     * `dateEpochDay - startEpochDay` 是**负数**，此时两种除法结果不同：
     *
     * ```
     * -3 / 7         == 0     // Kotlin / Java 的除法向零取整
     * floorDiv(-3, 7) == -1   // 数学意义上的向下取整
     * ```
     *
     * 用 `/` 的话，差值 -1 ~ -6 会得 0，`+1` 之后看起来还是第 1 周，不会暴露问题；
     * 但差值一到 -7 就除以 7 得 -1，`+1` 变成第 0 周，直接越界。
     * 换成 `floorDiv` 后负数区间也单调递减，再被 `coerceIn` 夹住，结果就稳了。
     *
     * ## 为什么最后要 coerceIn 而不是直接返回
     *
     * 因为界面上的"上一周 / 下一周"按钮、以及用户手填的开学日期都可能越界。
     * 与其在每个调用点都判一次边界，不如在这里统一兜住，
     * 让调用者可以放心地假设"拿到的周次一定是合法的"。
     *
     * 使用示例（开学第 1 周周一 = epochDay 20738，即 2026-10-12）：
     * ```
     * weekOf(20738, 20738, 20)   // 1，开学当天
     * weekOf(20738, 20744, 20)   // 1，第 7 天仍属第 1 周
     * weekOf(20738, 20745, 20)   // 2，第 8 天进入第 2 周
     * weekOf(20738, 20000, 20)   // 1，早于开学日期被夹到 1
     * weekOf(20738, 21738, 20)   // 20，超出总周数被夹到 20
     * ```
     */
    fun weekOf(startEpochDay: Long, dateEpochDay: Long, totalWeeks: Int): Int {
        val raw = Math.floorDiv(dateEpochDay - startEpochDay, 7L) + 1L
        return raw.coerceIn(1L, totalWeeks.toLong()).toInt()
    }

    /**
     * 当前是第几周。这是给界面用的入口，比 [weekOf] 多一层"手动覆盖"逻辑。
     *
     * "手动覆盖"（[com.courseschedule.data.entity.Semester.weekOverride]）是为
     * 假期调课、补课准备的：那几周学校的实际周次和"开学日期 + 7 天"算出来的对不上。
     * 与其让用户去改开学日期（会连带把整学期的周次全改错），不如让他直接指定"本周算第几周"。
     *
     * @param weekOverride 非 null 时**完全以它为准**，此时 `startEpochDay` 和
     *   `todayEpochDay` 都不参与计算。传 null 表示按开学日期自动算。
     *   设置页的开关关掉时，落库的就是 null。
     * @param todayEpochDay "今天"，默认取系统当前日期。
     *   它是**参数而不是直接调 `LocalDate.now()`**，就是为了让测试能模拟任意一天。
     *
     * @return 周次（1-based）。
     *
     * 注意：override 的值**不在这里做范围校验**。因为 [com.courseschedule.data.entity.Semester]
     * 落库前已经在设置页用 `coerceIn(1, totalWeeks)` 夹过了（见
     * [com.courseschedule.feature.settings.SettingsViewModel.save]）。
     * 如果你在别处直接写 `weekOverride` 字段，得自己保证它在 1..totalWeeks 之间，
     * 否则课表会显示成"当前是第 99 周"，周视图算出来的日期也会飘到几年后。
     */
    fun currentWeek(
        startEpochDay: Long,
        totalWeeks: Int,
        weekOverride: Int? = null,
        todayEpochDay: Long = LocalDate.now().toEpochDay()
    ): Int = weekOverride ?: weekOf(startEpochDay, todayEpochDay, totalWeeks)

    /**
     * 第 [week] 周的周一是哪天。
     *
     * 用途：把"第 N 周"翻译成表头要显示的 7 个具体日期。
     * [com.courseschedule.feature.schedule.ScheduleViewModel] 用它算出
     * `weekStartEpochDay`，然后表头的第 d 列日期就是 `weekStartEpochDay + (d - 1)`。
     *
     * 参数 [startEpochDay] 是第 1 周的周一，所以第 N 周的周一就是往后推 `(N-1)` 个 7 天。
     *
     * **不做边界检查**：传 `week = 0` 会得到开学前一周的周一，传 `week = 25`
     * 会得到第 25 周的周一（如果学期只有 20 周，这就是不存在的日期）。
     * 调用方要保证 [week] 已在合法范围内——界面上翻页时已经用 `coerceIn` 夹过。
     */
    fun weekStartEpochDay(startEpochDay: Long, week: Int): Long =
        startEpochDay + (week - 1) * 7L

    /**
     * 星期几：`1 = 周一` … `7 = 周日`（ISO-8601 标准，与周视图从左到右的列顺序一致）。
     *
     * 直接委托给 `java.time` 的 `DayOfWeek.value`，不自己算，避免重造轮子出错。
     *
     * **注意周日是 7 不是 0，也不是 1。**
     * 如果你从别的地方（比如老的 `java.util.Calendar`，那里周日 = 1）拿到星期值，
     * 必须先转换再传进来，不能混用——混用会静默地把整张课表错开一天，
     * 而且因为"周末两天都空着"，很多时候看不出来，等用户发现时已经用了很久。
     */
    fun dayOfWeek(epochDay: Long): Int = LocalDate.ofEpochDay(epochDay).dayOfWeek.value

    /**
     * 把任意一天对齐到它所在那一周的周一。
     *
     * 用途：用户第一次打开 app 时，默认学期的开学日期不能随便取"今天"，
     * 否则今天是周三的话，整学期的周次会全部偏两天。所以建学期时走这个函数
     * （见 [com.courseschedule.data.ScheduleRepository.ensureSemester]），
     * 保证 `startEpochDay` 永远是个周一。
     *
     * 算法是把"星期几减 1"再往回退：周一减 0 天、周三减 2 天、周日减 6 天。
     *
     * 使用示例：
     * ```
     * // 2026-10-14 是周三，epochDay = 20740
     * mondayOf(20740)  // 20738 → 2026-10-12，周一
     * ```
     */
    fun mondayOf(epochDay: Long): Long = epochDay - (dayOfWeek(epochDay) - 1)

    /**
     * 第 [week] 周是单周还是双周。**第 1 周固定算单周**（国内课表的通行约定）。
     *
     * 传 1 得 [WeekType.ODD]，传 2 得 [WeekType.EVEN]，传 3 又得 ODD，依此类推。
     *
     * 注意它只返回 ODD 或 EVEN，**永远不会返回 [WeekType.ALL]**——
     * ALL 是"课程时间段的属性"（这门课每周都上），不是"某一周的属性"。
     * 这两个概念在 [WeekType] 里共用一个枚举，但语义场景不同，别看混。
     */
    fun weekTypeOf(week: Int): WeekType = if (week % 2 == 0) WeekType.EVEN else WeekType.ODD

    /**
     * 判断某个课程时间段在第 [week] 周是否生效。
     *
     * 这是周视图"这一格画不画课程块"的唯一判据，也是整个课表最核心的一个判断。
     * 两个条件都得满足：
     *
     * 1. [week] 落在时间段的 `startWeek..endWeek` 之间；
     * 2. 时间段的单双周属性和这一周的奇偶性匹配（[WeekType.ALL] 视为永远匹配）。
     *
     * 使用示例（一个"周三 1-2 节、单周、第 1-20 周"的时间段）：
     * ```
     * isSlotActive(slot, 1)   // true  —— 第 1 周是单周，上
     * isSlotActive(slot, 2)   // false —— 第 2 周是双周，不上
     * isSlotActive(slot, 3)   // true
     * isSlotActive(slot, 21)  // false —— 超出 endWeek
     * ```
     *
     * 注意：本函数**只判断周次，不判断星期几**。
     * "这个时间段是不是周三的"由调用方自己比对 `slot.dayOfWeek`——
     * 因为周视图本来就按 `dayOfWeek` 把课程分好组了，再在这里判一次纯属重复。
     * 用法见 [com.courseschedule.feature.schedule.ScheduleViewModel] 里
     * `activeCourses` 那段：先 `flatMap` 展开成一个个时间段，
     * 再用本函数筛掉这周不上的，最后才按 `dayOfWeek` 分列。
     *
     * **不要**把它优化成"传入课程列表一次算完"——保持单时间段粒度，
     * 才能让 UI 层按任何维度自由组合、也才能单测。
     */
    fun isSlotActive(slot: CourseSlot, week: Int): Boolean {
        if (week < slot.startWeek || week > slot.endWeek) return false
        return when (slot.weekType) {
            WeekType.ALL -> true
            else -> slot.weekType == weekTypeOf(week)
        }
    }

    /**
     * 这个时间段是否覆盖了第 [section] 节。
     *
     * 目前**没有被业务代码调用**，是为 P2 的"上课提醒"预留的：
     * 提醒要在"某节课开始前 10 分钟"触发，那时需要判断
     * "第 3 节是不是被某个时间段占着"。
     *
     * 只有 `section in startSection..endSection` 一个判断，
     * 因为周次判断已经被 [isSlotActive] 做过了，两个要配合用：
     * ```
     * if (WeekCalculator.isSlotActive(slot, week) &&
     *     WeekCalculator.coversSection(slot, 3)) {
     *     // 这一周的第 3 节有课
     * }
     * ```
     *
     * 也可以用来判断"两门课有没有撞在同一格"：对同一天的两个 slot，
     * 如果任一方的起止节次区间和另一方有交集，就是冲突。
     * 现在项目里**不做冲突检测**——学生自己知道课表长什么样，
     * 强行拦截反而会挡住"同一时段两门课（比如必修+选修）"的真实需求。
     */
    fun coversSection(slot: CourseSlot, section: Int): Boolean =
        section in slot.startSection..slot.endSection
}
