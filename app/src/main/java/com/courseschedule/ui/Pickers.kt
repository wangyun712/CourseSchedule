package com.courseschedule.ui

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable

/**
 * Material 3 的日期 / 时间选择弹窗。
 * `@OptIn(ExperimentalMaterial3Api::class)`：M3 部分 API 标记为实验性，签名可能在版本升级时变动，
 * Kotlin 要求显式声明接受该风险才能使用；DatePicker / TimePicker 实际已经很稳定。
 */

/** 一天的毫秒数。写成 `86_400_000L` 而不是 `86400000L`：下划线是 Kotlin 的数字分隔符，一眼能数清位数 */
private const val MILLIS_PER_DAY = 86_400_000L

/**
 * 日期选择弹窗。收 `epochDay`、吐 `epochDay`，全 app 只在这一处碰毫秒——
 * 天数之间加减没有时区、夏令时偏移，比毫秒安全。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerDialogM3(
    initialEpochDay: Long,
    onDismiss: () -> Unit,
    onPicked: (Long) -> Unit
) {
    val state = rememberDatePickerState(
        // DatePicker 的初始值只认毫秒，乘回去；M3 内部按 UTC 零点处理（不带你手机的时区），
        // 与下面 floorDiv 的换算正好互逆，不会有时区偏移。
        initialSelectedDateMillis = initialEpochDay * MILLIS_PER_DAY
    )

    // DatePickerDialog 跟 DatePicker 的关系：前者是"带确定/取消按钮的壳"，
    // 后者是"里面那个日历"。两者分开是为了让日历能单独用（比如嵌在页面里）。
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                // selectedDateMillis 可空（用户没动过日历就是 null），必须 ?.let
                state.selectedDateMillis?.let { millis ->
                    // 毫秒 → 天数：floorDiv 向负无穷取整，1970 年前的负毫秒也算得对
                    // （`/` 向零取整，-1 天会算成 0）；与上面"天 × 毫秒/天"互逆。
                    onPicked(Math.floorDiv(millis, MILLIS_PER_DAY))
                }
                // 手动调 onDismiss：点了确定不会自动关闭，关闭时机由我们控制（便于关闭前做校验）
                onDismiss()
            }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    ) {
        // 最后的尾随 lambda 是"弹窗里放什么内容"的插槽
        DatePicker(state = state)
    }
}

/**
 * 时间选择弹窗。M3 没有 TimePickerDialog，用 `AlertDialog` 包住 `TimePicker`。
 * 收 / 吐 `Int` 分钟数（0–1439），与数据层 `PeriodTime` 的存储表示一致，边界各转换一次。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialogM3(
    initialMinutes: Int,
    onDismiss: () -> Unit,
    onPicked: (Int) -> Unit
) {
    val state = rememberTimePickerState(
        // 分钟数拆成小时 + 分钟；coerceIn 防御兜底：非法值会让 TimePicker 崩溃，两行换掉崩溃可能
        initialHour = (initialMinutes / 60).coerceIn(0, 23),
        initialMinute = (initialMinutes % 60).coerceIn(0, 59),
        // 24 小时制，中文用户的默认习惯；做国际版时应跟随 Locale
        is24Hour = true
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择时间") },
        // text 插槽本质是"内容区"，塞任何 Composable 都行，不必是 Text
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = {
                // 小时和分钟拼回分钟数，跟上面拆开是互逆操作
                onPicked(state.hour * 60 + state.minute)
                onDismiss()
            }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
