package com.courseschedule.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.courseschedule.ui.theme.CoursePalette

/**
 * # 跨页面复用的小控件
 *
 * 这些组件被课表页、加课页、设置页共用。抽出来的理由：
 * - 视觉一致：改了这里，三个页面同时变，不会出现"加课页的按钮圆角是 8dp、
 *   设置页是 12dp"这种漂移
 * - 少写代码：`NumberStepper` 在设置页用了 3 次，写 3 遍容易抄错
 *
 * ## 这些组件的共同风格：**无状态**
 *
 * 每个组件都不持有任何自己的数据，需要什么通过参数传进来，
 * 用户操作通过回调抛出去（`onValueChange` / `onSelect`）。
 *
 * 对比"有状态组件"（比如自己 `remember` 一个选中项）：无状态的好处是
 * 状态只有一个来源（ViewModel），不可能出现"界面显示 A、数据里存的是 B"。
 * 这跟 Qt 的 `QSpinBox` 自带 value 是相反的思路，值得留意。
 */

/** 小标题。用于设置页的"学期""作息时间"这类分区标题 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        // style 一次带走字号 + 行高 + 字距，比零散设 fontSize 更规范。
        // 但下面又单独覆盖了 color 和 fontWeight——这是允许的：
        // style 提供基线，具体属性再按需微调（后写的优先级更高）。
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Medium
    )
}

/**
 * 数字步进器（−  值  ＋）。
 *
 * 为什么不用下拉菜单或输入框：手机上一只手能点，而且**天然不会输入非法值**
 * （节次 1–15、周次 1–30 都是小范围整数）。输入框要额外处理
 * "用户删空了""用户输入了 999"这类情况，得不偿失。
 *
 * @param value       当前值。调用方传的是状态，不是组件自己存的。
 * @param range       允许范围。这里既是边界判断依据，也顺便告诉调用方约束在哪。
 * @param onValueChange 值变化回调。组件内部只负责"调它"，不负责改数据。
 * @param suffix      后缀，比如 "周" → 显示 "8周"。
 */
@Composable
fun NumberStepper(
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    suffix: String = ""
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        // spacedBy 只作用在"相邻元素之间"，不会在最外面留空——
        // 比给每个元素加 padding 更精确。
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        TextButton(
            // 边界处理写在 onClick 里，而不是把按钮 disabled 掉。
            // 两种做法的取舍：disabled 视觉上更明确（按钮变灰），
            // 但这需要把颜色也手动处理一遍；这里选了"点了没反应"，
            // 因为到达边界本来就是少见操作，不值得为它增加视觉复杂度。
            onClick = { if (value > range.first) onValueChange(value - 1) },
            // TextButton 默认内边距很大（Material 的最小点击区是 48dp），
            // 三个并排会撑得很宽。这里压到 8dp 横向、0 纵向。
            // ⚠️ 代价：实际点击区小于 48dp 的无障碍建议值。
            // 因为这几个按钮在设置页里不被频繁点，所以接受了这个取舍。
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
        ) { Text("−", fontSize = 18.sp) }

        Text(
            // 字符串模板拼后缀，比 Column 摆两个 Text 省一层布局
            text = "$value$suffix",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )

        TextButton(
            onClick = { if (value < range.last) onValueChange(value + 1) },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
        ) { Text("＋", fontSize = 18.sp) }
    }
}

/**
 * 单选标签组（横向排开的一串胶囊）。
 *
 * 替代下拉菜单：选项都看得见，少一次点击。选项少（2–7 个）时明显更好用；
 * 选项多的话还是得用下拉，否则一行排不下。
 *
 * @param options  候选项。用泛型 T 而不是 String，是为了让调用方
 *                 直接传 `WeekType.entries` / `(1..7).toList()` 这类业务对象，
 *                 不用先转成字符串数组——转换会丢掉类型信息。
 * @param selected 当前选中项。用 `==` 比较（第 94 行），
 *                 所以 T 是 data class 或枚举时能正确判断。
 * @param labelOf  "怎么显示"。**抽成参数而不是用 `option.toString()`**，
 *                 因为枚举的 toString 得到的是 `WeekType.ODD`，
 *                 界面要的是"单周"。同理，星期几要显示"周一"而不是 `1`。
 */
@Composable
fun <T> ChoiceChips(
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    // Modifier 链的顺序在这里很关键，见下方 ColorDots 的同类说明：
                    // clip 必须在 background 之前，否则背景是直角矩形，圆角白设。
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        // 选中用主色填充，未选中用浅灰。这是视觉层级的表达：
                        // 选中项"最重"，一眼能扫到。
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable { onSelect(option) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = labelOf(option),
                    fontSize = 13.sp,
                    // ⚠️ 前景色必须跟着背景色换。
                    // 选中时背景是深蓝（primary），文字就得用 onPrimary（白）；
                    // 不换的话深底深字，什么都看不见。
                    // "onXxx" 在 Material 里的含义就是"放在 Xxx 上的颜色"。
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 课程颜色选择：一排彩色圆点。
 *
 * ## 选中态用"三重信号"表达，不只靠颜色
 *
 * 选中的圆点同时是：**更大（30dp vs 26dp）+ 有描边 + 位置固定**。
 * 如果只用"颜色深浅"区分，色弱用户和强光下的屏幕都会失效。
 * 无障碍不是额外工作，就是这样顺手多做一点。
 *
 * @param selectedIndex 选中的下标。用下标而不是 Color 对象，
 *                      因为存进数据库的是下标（`colorIndex`），
 *                      颜色表以后可能换，下标更稳定。
 * @param onSelect 抛出去的是**下标**，不是颜色——数据层只认下标。
 */
@Composable
fun ColorDots(selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CoursePalette.colors.forEachIndexed { index, color ->
            Box(
                modifier = Modifier
                    // 尺寸变化会让整行的高度跟着变吗？不会——Row 的高度取最大值，
                    // 未选中的圆点只是"在里面居中"，行高恒定，不会跳动。
                    .size(if (index == selectedIndex) 30.dp else 26.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(
                        // 未选中时 border 宽度给 0.dp 而不是"不加 border"。
                        // 效果一样，但保持 Modifier 链结构一致，读起来更清楚——
                        // 一眼能看到"两种状态差别只在三个参数"。
                        width = if (index == selectedIndex) 2.dp else 0.dp,
                        // 描边用 onSurface（主题的前景色）而不是固定白色：
                        // 深色模式下白色描边还行，浅色模式下白色描边就看不见了。
                        // 跟着主题走才能两种模式都成立。
                        color = if (index == selectedIndex) MaterialTheme.colorScheme.onSurface
                        else Color.Transparent,
                        shape = CircleShape
                    )
                    .clickable { onSelect(index) }
            )
        }
    }
}

/**
 * 一行「标签 + 内容」，用于课程详情和设置页。
 *
 * 这是 Compose 的**插槽（slot）模式**：`content: @Composable () -> Unit`
 * 让调用方把任意界面塞进来，而不是只接受一个字符串。
 *
 * 对比一下如果写成 `LabeledRow(label: String, value: String)`：
 * 就只能放文字了，想在右边放个步进器或开关都做不到，只能再写一个新组件。
 * Compose 官方组件（Scaffold、Card、Button）全都用的这个套路。
 */
@Composable
fun LabeledRow(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        // fillMaxWidth 必须在 padding 之前：先撑满父容器宽度，
        // 再往里缩 4dp。反过来先 padding 再 fillMaxWidth，宽度会变成
        // "父容器宽度"从而溢出 4dp。
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp)
        )
        // 外面套一层 Box 再 fillMaxWidth，是为了让 content 占据"标签之外的
        // 全部剩余宽度"。直接用 fillMaxWidth 的话，因为 Row 是按顺序量测的，
        // 内容可能被标签挤到溢出屏幕。
        Box(Modifier.fillMaxWidth()) { content() }
    }
}

/** 说明性小字（灰色、12sp）。用于"开启后第 N 周完全由你说了算"这类解释文案 */
@Composable
fun HintText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** 占位 / 空态提示（居中、留大边距）。没有课程、没有便签时的兜底文案 */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        // contentAlignment 是"子元素在 Box 里怎么对齐"。
        // 别和 Modifier.align 搞混：那个是子元素自己申明位置，
        // 这个是父容器给所有子元素定的默认位置。
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 卡片外壳。
 *
 * `surfaceVariant.copy(alpha = 0.5f)`：用主题色但压到半透明。
 * 为什么不直接给个固定的浅灰 `Color(0xFFEEEEEE)`：那样深色模式下就是
 * 一块刺眼的白斑。`copy(alpha=)` 的好处是底色跟着主题走，
 * 半透明让它在深色/浅色下都能自然融进背景。
 *
 * ⚠️ 注意 alpha 是作用在颜色上的，**不是 Modifier.alpha**。
 * 后者会把整张卡片（包括里面的文字）都变半透明，文字会发灰看不清。
 */
@Composable
fun CardShell(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            // clip 在 background 之前，圆角才能生效（同 ChoiceChips 的说明）
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(12.dp)
    ) { content() }
}

/**
 * 纵向留白。
 *
 * ⚠️ 参数是 `Int`（dp 的数值）而不是 `Dp`，这是刻意的简化：
 * 调用处写 `VerticalSpacer(16)` 比 `VerticalSpacer(16.dp)` 少几个字符，
 * 而这些留白全是拍脑袋定的整数，不需要 dp 那套类型安全。
 *
 * 更地道的写法其实是 `Spacer(Modifier.height(16.dp))`，用官方 API。
 * 这里自己包一层只是为了让调用点更短。
 *
 * 宽度给 0.dp（而不是不写 width）：`Modifier.size` 需要两个维度，
 * 显式写 0 表示"不占横向空间"。
 */
@Composable
fun VerticalSpacer(height: Int) {
    Box(Modifier.size(width = 0.dp, height = height.dp))
}
