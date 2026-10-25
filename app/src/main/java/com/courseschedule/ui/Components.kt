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
 * 跨页面复用的小控件，被课表页、加课页、设置页共用，保证三页视觉一致。
 * 组件共同风格是无状态：状态由父级持有、用户操作通过回调上抛，
 * 状态单一来源，不会出现界面显示与数据不一致。
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
 * 数字步进器（−  值  ＋）。步进输入天然不会产生非法值，适合节次、周次这类小范围整数。
 * `value` 是调用方持有的状态，组件只上抛 `onValueChange`，不自己改数据。
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
            // 边界处理写在 onClick（越界点了没反应）而不是 disable 按钮：
            // 禁用得额外处理变灰样式，而到边界是少见操作，不值得增加视觉复杂度。
            onClick = { if (value > range.first) onValueChange(value - 1) },
            // TextButton 默认内边距很大，压小才能三个并排；
            // 代价是点击区小于 48dp 的无障碍建议值，设置页不常点，接受此取舍。
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
 * 单选标签组（横向一串胶囊），选项 2–7 个时比下拉菜单少一次点击。
 * 泛型 T + `labelOf`：同一组件既能选星期（"周一"）也能选单双周（"单周"），
 * 调用方直接传 `WeekType.entries` 等业务对象，不用先转字符串丢掉类型。
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
                    // clip 必须在 background 之前，否则背景是直角矩形，圆角白设
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
                    // 前景色必须跟着背景换：选中深底配 onPrimary，否则深底深字看不清。
                    // Material 的 "onXxx" 就是"放在 Xxx 上的颜色"。
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 课程颜色选择：一排彩色圆点。
 * 选中态用三重信号表达（更大 30dp、有描边、位置固定），不只靠颜色，色弱、强光下也可辨。
 * 传下标不传 `Color`：数据库存的是 `colorIndex`，颜色表以后可能换，下标更稳定。
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
 * 一行「标签 + 内容」。`content` 是插槽（`@Composable () -> Unit`），
 * 调用方能塞任意内容（步进器、开关等），而不是只收一个字符串。
 */
@Composable
fun LabeledRow(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        // fillMaxWidth 必须在 padding 之前：先撑满再内缩；反序会以父容器宽为基准，溢出 4dp
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
 * 卡片外壳。用主题色压 alpha（`surfaceVariant.copy(alpha = 0.5f)`）而不是写死灰色，深浅模式都成立。
 * alpha 作用在颜色上，不是 `Modifier.alpha`——后者会把卡内文字一起变半透明。
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
 * 纵向留白。参数是 `Int`（dp 数值）而非 `Dp`，刻意简化：
 * 留白全是拍板的整数，调用处 `VerticalSpacer(16)` 比 `16.dp` 短。
 */
@Composable
fun VerticalSpacer(height: Int) {
    Box(Modifier.size(width = 0.dp, height = height.dp))
}
