package com.courseschedule.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 主题配色。M3 的 colorScheme 是成对的语义槽位（primary/onPrimary、background/onBackground 等），
 * 界面只写 `MaterialTheme.colorScheme.xxx`，不写死颜色，深浅模式切换时所有控件自动跟着变。
 * 两套方案都把用到的槽位显式配齐——lightColorScheme() 未传的槽位会用 M3 默认紫色系，
 * 只改部分槽位会出现"蓝按钮 + 紫开关"的混搭。
 * 不用 Android 12+ 动态取色：课程色块是固定色板，主题色随壁纸变可能与之撞色。
 */

/** 浅色方案：冷蓝主色 + 绿色次色，背景用暖灰白而非纯白（大屏上纯白刺眼）。 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF185FA5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB5D4F4),
    onPrimaryContainer = Color(0xFF0C447C),
    secondary = Color(0xFF0F6E56),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF7F7F5),
    onBackground = Color(0xFF2C2C2A),
    surface = Color(0xFFFDFDFB),
    onSurface = Color(0xFF2C2C2A),
    surfaceVariant = Color(0xFFE9E9E4),
    onSurfaceVariant = Color(0xFF5F5E5A)
)

/**
 * 深色方案：不是把浅色取反，而是重新选色（主色变浅、onXxx 变深、背景近黑但非纯黑）。
 * 一句话规律：深色模式下"色"变亮、"底"变暗。
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF85B7EB),
    onPrimary = Color(0xFF042C53),
    primaryContainer = Color(0xFF185FA5),
    onPrimaryContainer = Color(0xFFE6F1FB),
    secondary = Color(0xFF5DCAA5),
    onSecondary = Color(0xFF04342C),
    background = Color(0xFF1B1B19),
    onBackground = Color(0xFFEDEDE8),
    surface = Color(0xFF212120),
    onSurface = Color(0xFFEDEDE8),
    surfaceVariant = Color(0xFF2F2F2C),
    onSurfaceVariant = Color(0xFFB4B2A9)
)

/**
 * 主题入口，在 MainActivity 里包住整棵 UI 树。
 *
 * @param darkTheme 默认跟随系统深色开关；留成可传参数是为了让 @Preview 能强制指定。
 */
@Composable
fun CourseScheduleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        // typography / shapes 均用 M3 默认值，不显式传入
        content = content
    )
}

/**
 * 课程块配色。关键设计：颜色由课程自己的 `colorIndex`（存库字段）决定，
 * 而不是按插入顺序取色——否则删掉中间一门课，后面所有课的颜色都会前移，
 * 用户会以为课变了；存库后颜色跟着课走，删课不影响其他课的颜色。
 */
object CoursePalette {
    /** 课程块色板：中等明度、两两色相拉开，深浅背景下都看得清，相邻色号的色块不糊在一起。 */
    val colors = listOf(
        Color(0xFF66CCFF),  // 蓝
        Color(0xFFEE82EE),  // 粉
        Color(0xFFEE0000),  // 红
        Color(0xFFD85A30),  // 橙
        Color(0xFF639922),  // 绿
        Color(0xFF7F77DD),  // 紫
        Color(0xFFBA7517),  // 黄
        Color(0xFFD4537E),  // 粉
        Color(0xFF1D9E75),  // 青
        Color(0xFF888780)   // 灰
    )

    /**
     * 按下标取色，越界时绕回。Kotlin 的 % 对负数返回负数（-1 % 8 == -1），
     * 简单取模会数组越界，故用双重取模 ((index % size) + size) % size 把负数落回 [0, size)。
     * 正常数据走不到负数分支，这是"日后缩减色板、老数据下标越界"时的防崩溃保险。
     */
    fun colorOf(index: Int): Color = colors[((index % colors.size) + colors.size) % colors.size]
}
