package com.courseschedule.core

import java.time.LocalDate

/**
 * 日期与时间的「文本 ↔ 数字」互转：分钟数（Int）、界面文本（"08:00"）、epochDay（Long）三种表示之间的唯一转换入口，
 * 避免各界面自行拼接/解析导致格式不一致，出错也只有这一处要改。
 * 约定：所有 `textToXxx` 解析失败一律返回 null、不抛异常——输入直接来自用户手输，打字到一半必然非法，
 * 抛出去就是崩溃；由调用方决定如何提示，用法见 `SettingsViewModel.save`。
 */
object TimeUtils {

    /** 日期文本的合法格式：`2026-9-1` 或 `2026-09-01`，先粗筛再交 `LocalDate.parse` 兜底解析。 */
    private val DATE_PATTERN = Regex("""\d{4}-\d{1,2}-\d{1,2}""")

    /**
     * 分钟数 → 界面文本。`480` → `"08:00"`，补零到两位使固定行高布局里文字宽度恒定、列表不抖。
     *
     * 超出 0..1439 的输入会被夹住而非报错——时间步进器可能点到边界外，夹住比崩溃友好。
     */
    fun minutesToText(minutes: Int): String {
        val m = minutes.coerceIn(0, 24 * 60)
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /**
     * 界面文本 → 分钟数。`"8:00"` 或 `"08:00"` 都 → `480`。
     *
     * 容错：接受前后空格、全角冒号 `：`（中文输入法易打出）、小时不补零；
     * 但不接受省略分钟（`"8"`）或带秒（`"8:00:00"`），多一种格式就多一种歧义。任何一步不合法都返回 `null`。
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
     * epochDay → 界面文本。`20738` → `"2026-10-12"`，直接用 `LocalDate.toString()` 的 ISO-8601 格式。
     * 是设置页"开学日期"输入框的显示来源，也是日期选择器的初始值。
     */
    fun epochDayToText(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).toString()

    /**
     * 界面文本 → epochDay。`"2026-10-12"` → `20738`；格式不对或日期不存在都返回 `null`。
     *
     * 两道防线：正则粗筛挡格式完全不对的输入（如 `"2026年10月"`），`LocalDate.parse` 兜底挡
     * "格式对但日期不存在"（如 `"2026-02-30"`，正则只管位数不管数值）。
     * 返回的是 epochDay 而非毫秒时间戳，混用会差 86400000 倍；与毫秒的互转见 `DatePickerDialogM3`。
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
