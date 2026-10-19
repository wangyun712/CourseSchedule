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
 * # Material 3 的日期 / 时间选择弹窗
 *
 * ## ⚠️ `@OptIn(ExperimentalMaterial3Api::class)` 是什么意思
 *
 * Material 3 还在演进，一部分组件的 API 被标记为"实验性"——
 * 意思是**下一个小版本可能改签名，升级依赖时你的代码有可能会编译失败**。
 * Kotlin 默认不允许使用这种 API，必须显式写 `@OptIn(...)` 表示"我知道风险，我自己担"。
 *
 * 这不是"这个组件不能用"。DatePicker / TimePicker 从 M3 1.1 起就很稳定了，
 * 只是官方还没摘掉实验标记。写 `@OptIn` 的成本只是一个注解，
 * 不写就得自己拿 `LazyColumn` 画一个日期滚动选择器，明显不划算。
 *
 * 升级 Material 依赖时，第一件事就是看这几个文件的编译错误。
 */

/** 一天的毫秒数。写成 `86_400_000L` 而不是 `86400000L`：下划线是 Kotlin 的数字分隔符，一眼能数清位数 */
private const val MILLIS_PER_DAY = 86_400_000L

/**
 * 日期选择弹窗。
 *
 * ## 这个函数的契约
 *
 * 收一个 `epochDay`（1970-01-01 起的天数），吐一个 `epochDay`。
 * 全 app 只在这一处碰"毫秒"，进出的其他地方全是 epochDay——
 * 因为天数之间做加法减法不会有夏令时、时区带来的偏移，比毫秒安全得多。
 *
 * @param initialEpochDay 打开时默认选中的日期（本 app 传的是学期开始日期）
 * @param onDismiss 取消 / 点外面关闭
 * @param onPicked   点了"确定"。参数是选中的 epochDay
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerDialogM3(
    initialEpochDay: Long,
    onDismiss: () -> Unit,
    onPicked: (Long) -> Unit
) {
    val state = rememberDatePickerState(
        // DatePicker 的初始值只认毫秒，所以要乘回去。
        // ⚠️ 它内部把日期当成 **UTC 零点**处理（不带你手机的时区），
        // 所以这里乘出来的毫秒也是"UTC 那天的零点"，
        // 跟下面 `floorDiv` 回来的算法正好互逆，不会有时区偏移。
        initialSelectedDateMillis = initialEpochDay * MILLIS_PER_DAY
    )

    // DatePickerDialog 跟 DatePicker 的关系：前者是"带确定/取消按钮的壳"，
    // 后者是"里面那个日历"。两者分开是为了让日历能单独用（比如嵌在页面里）。
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                // ⚠️ 返回值是可空的：用户没动过日历、或者反选之后就是 null。
                // 所以必须用 `?.let` 包一层，不能直接拿。
                state.selectedDateMillis?.let { millis ->
                    // 毫秒 → 天数。为什么必须用 Math.floorDiv 而不是 `/`：
                    // Kotlin/Java 的整数除法是**向零取整**，负数会算错。
                    // 1970 年之前的日期 millis 是负的，`-1 / 86400000` 得 0，
                    // 而正确答案是 -1（那一天属于 1969-12-31）。
                    // floorDiv 是向负无穷取整，负数也正确。
                    //
                    // 本 app 里学期日期不可能是 1969 年，所以这个 bug 永远不会触发。
                    // 但"知道为什么这么写"和"碰巧没踩坑"是两回事。
                    onPicked(Math.floorDiv(millis, MILLIS_PER_DAY))
                }
                // 手动调 onDismiss：DatePickerDialog 点了确定**不会自动关闭**，
                // 关闭与否由我们决定（这样就能在关闭前做校验、弹二次确认等）。
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
 * 时间选择弹窗。
 *
 * ## 为什么这里用 AlertDialog 包，而上面用 DatePickerDialog
 *
 * M3 只提供了 `DatePickerDialog` 这个现成的壳，**没有 TimePickerDialog**。
 * `TimePicker` 就是一个光秃秃的钟面 + 数字输入，得自己找容器包起来，
 * AlertDialog 是最自然的选择。这不是偷懒，是官方 API 的现状。
 *
 * ## 时间用什么表示
 *
 * 收 `Int` 分钟数（0–1439），吐 `Int` 分钟数。不用 `LocalTime`
 * 是因为数据层存的就是"从零点起的第几分钟"（节次作息就是这么存的，
 * 见 `PeriodTime`）。在边界上转换一次，中间全都用 Int，最简单。
 *
 * @param initialMinutes 打开时默认选中的时间，0 到 1439 之间
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialogM3(
    initialMinutes: Int,
    onDismiss: () -> Unit,
    onPicked: (Int) -> Unit
) {
    val state = rememberTimePickerState(
        // 分钟数拆成"小时 + 分钟"给钟面。coerceIn 是防御性的兜底：
        // 这个函数理论上只会收到合法值，但如果哪天调用方传了 -1 或 9999，
        // 这里不夹住的话 TimePicker 会直接崩（内部按小时数取模数组）。
        // 花两行代码换掉一个崩溃可能，很划算。
        initialHour = (initialMinutes / 60).coerceIn(0, 23),
        initialMinute = (initialMinutes % 60).coerceIn(0, 59),
        // 24 小时制。中文用户默认习惯，也省得再处理"上午/下午"。
        // 如果哪天要做国际版，这里应该跟着 Locale 走。
        is24Hour = true
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择时间") },
        // 钟面不是文字，但 AlertDialog 的 text 插槽本质是"内容区"，
        // 塞任何 Composable 都行，不必是 Text。
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
