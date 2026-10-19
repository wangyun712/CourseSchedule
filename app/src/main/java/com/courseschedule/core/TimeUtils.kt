package com.courseschedule.core

import java.time.LocalDate

/**
 * 日期与时间的「文本 ↔ 数字」互转。
 *
 * ## 为什么要单独抽一个类来干这件事
 *
 * 项目里时间有**三种表示形式**，它们之间必须能来回转换，而且转换规则必须唯一：
 *
 * | 表示形式 | 类型 | 例子 | 用在哪 |
 * |---|---|---|---|
 * | 分钟数（距 0 点） | `Int` | `480` | 数据库 `period_time.startMinute`、比较"现在是不是在上课" |
 * | 文本 | `String` | `"08:00"` | 界面上显示、用户输入 |
 * | epochDay | `Long` | `20738` | 数据库 `semester.startEpochDay`、周次计算 |
 *
 * 如果让每个界面自己写 `"${h}:${m}"` 拼字符串、自己 `split(":")` 解析，
 * 就会出现"设置页显示 `8:00`、课表页显示 `08:00`"这种不一致，
 * 更糟的是某处解析失败时静默返回 0，把用户写的 `8:00` 变成 `0:00`。
 * 统一在这里之后，格式只有一套，出错也只有一处要改。
 *
 * ## 为什么用 `object` 而不是 `class`
 *
 * `object` 是 Kotlin 的**单例声明**（对应 C++ 的静态类 / 全局函数集合）。
 * 因为这里全是无状态的纯函数，没有实例字段，没理由创建对象。
 * 调用方式是 `TimeUtils.minutesToText(480)`，不需要 `new`。
 *
 * ## 贯穿本文件的约定：解析失败一律返回 null，绝不抛异常
 *
 * 四个 `textToXxx` 函数在输入非法时**全部返回 `null`**，而不是抛
 * `NumberFormatException` 或 `DateTimeParseException`。
 * 原因是它们的输入直接来自用户手输（设置页的开学日期输入框）。
 * 用户打字打到一半时内容必然是"非法"的，那时候抛异常会让 app 直接崩——
 * 正确做法是返回 null，让调用方决定"是显示错误提示还是先按默认值处理"。
 *
 * 调用方拿到 null 之后必须处理，编译器会强制你处理（因为返回类型是 `Int?` 不是 `Int`），
 * 这就是 Kotlin 空安全在数据边界上的价值。用法见
 * [com.courseschedule.feature.settings.SettingsViewModel.save]。
 */
object TimeUtils {

    /**
     * 日期文本的合法格式：`2026-9-1` 或 `2026-09-01`。
     *
     * 用 `Regex` 先粗筛一遍再做真正的解析，好处是能拦掉 `LocalDate.parse`
     * 那些很啰嗦的异常信息、也能拦住 `"2026-13-45"` 这种格式对但日期不存在的输入
     * （交给 `LocalDate.parse` 兜底）。
     *
     * `"""..."""` 是 Kotlin 的**原始字符串**，里面的 `\d` 不会被当成转义字符，
     * 所以不用像 Java 那样写成 `"\\d{4}"`。写正则时用这个能省掉一半反斜杠。
     */
    private val DATE_PATTERN = Regex("""\d{4}-\d{1,2}-\d{1,2}""")

    /**
     * 分钟数 → 界面文本。`480` → `"08:00"`。
     *
     * @param minutes 距 0 点的分钟数。超出 0..1439 的值会**被夹住**而不是报错——
     *   因为用户可能不小心把时间步进器点到边界外，夹住比崩溃友好。
     *
     * @return 固定两位的 `HH:mm`。**一定不会返回 null**，永远有值可显示。
     *
     * 为什么用 `"%02d:%02d".format(...)` 而不是字符串拼接：
     * 拼接会得到 `"8:0"`，而行高固定的布局里字符串长度变化会导致文字左右跳动。
     * 补零后宽度恒定，列表滚动时不会抖。
     *
     * 注意 `"%02d"` 是**Java 的格式化语法**，不是 Kotlin 特有的，
     * 和 C 的 `printf` 完全一样（对应 C++ 的 `std::format("{:02d}:{:02d}")`）。
     */
    fun minutesToText(minutes: Int): String {
        val m = minutes.coerceIn(0, 24 * 60)
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /**
     * 界面文本 → 分钟数。`"8:00"` 或 `"08:00"` 都 → `480`。
     *
     * @return 解析成功返回 `0..1439`；**任何一步不合法都返回 `null`**。
     *
     * 容错设计（都是为了让用户少按几次正确的键）：
     *
     * - 前后空格会被 `trim()` 掉；
     * - 全角冒号 `：` 和半角冒号 `:` 都接受（中文输入法下很容易打出全角的）；
     * - 小时数允许不补零（`"8:00"` 和 `"08:00"` 等价）。
     *
     * 但**不接受省略分钟**（`"8"` 不行）、**不接受秒**（`"8:00:00"` 不行），
     * 因为多一种格式就多一种歧义，宁可让用户改一个字符。
     *
     * `?: return null` 这个写法（Elvis 运算符）读作：
     * "如果左边是 null，就执行右边"——所以 `toIntOrNull()` 失败会直接退出函数。
     * 对比 C++ 里用 `std::stoi` 得包一层 try-catch，
     * 或者用 `strtol` 还得检查 `errno` 和 `endptr`。
     */
    fun textToMinutes(text: String): Int? {
        val parts = text.trim().split(":", "：")
        if (parts.size != 2) return null
        val hour = parts[0].trim().toIntOrNull() ?: return null
        val minute = parts[1].trim().toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    /**
     * epochDay → 界面文本。`20738` → `"2026-10-12"`。
     *
     * 直接委托 `LocalDate.toString()`——它输出的就是 ISO-8601 的 `yyyy-MM-dd`，
     * 正好是我们要的格式，不需要自己拼。**一定不会返回 null。**
     *
     * 这是设置页"开学日期"输入框的显示来源，也是它旁边"选择日期"按钮
     * 打开日期选择器时的初始值。
     */
    fun epochDayToText(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).toString()

    /**
     * 界面文本 → epochDay。`"2026-10-12"` → `20738`。
     *
     * @return 解析成功返回 epochDay；**格式不对或日期不存在都返回 `null`**。
     *
     * 两道防线，缺一不可：
     *
     * 1. 先用 [DATE_PATTERN] 粗筛。这一步挡掉的是 `"明天"`、`"2026年10月"` 这类
     *    格式完全不对的输入，避免让 `LocalDate.parse` 抛出信息量很差的异常。
     * 2. 再交给 `LocalDate.parse` 真解析。这一步挡掉的是**格式对但日期不存在**的输入，
     *    比如 `"2026-02-30"`、`"2026-13-01"`、`"2026-00-05"`。
     *    正则拦不住这些，因为它只管位数不管数值。
     *
     * 那个 `try / catch` 是必要的：`LocalDate.parse` 在日期不存在时抛
     * `DateTimeParseException`，而这里是用户输入边界，抛出去就是崩溃。
     * `catch (e: Exception)` 捕获得比较宽，是因为异常类型在不同 JDK 版本上
     * 有过变化，这里的目的只是"别让它炸"，不需要区分具体类型。
     *
     * 注意返回的是 epochDay，**不是毫秒时间戳**。两者差 86400000 倍，
     * 混用会让日期飘到几千年后。转换逻辑见 [com.courseschedule.ui.DatePickerDialogM3]。
     */
    fun textToEpochDay(text: String): Long? {
        val trimmed = text.trim()
        if (!DATE_PATTERN.matches(trimmed)) return null
        return try {
            LocalDate.parse(trimmed).toEpochDay()
        } catch (e: Exception) {
            null
        }
    }
}
