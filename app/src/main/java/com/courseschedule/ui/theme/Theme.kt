package com.courseschedule.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * # 主题：配色方案
 *
 * ## Material 3 的色彩模型（理解这个才能看懂下面在配什么）
 *
 * M3 不用"背景色/文字色"这种平铺的命名，而是**成对**的语义色：
 *
 * - `primary` / `onPrimary`
 *   `primary` 是主色调（按钮底色、选中态）。`onPrimary` 是"放在 primary 上面
 *   的文字颜色"。**这两个必须成对配**，否则就是深底深字。
 *
 * - `primaryContainer` / `onPrimaryContainer`
 *   更浅的容器色，用于大面积填充（比如选中的卡片）。
 *   跟 primary 的关系类似"浅色版本"，用在不同层级的强调上。
 *
 * - `background` / `onBackground`   整个页面的底
 * - `surface` / `onSurface`         卡片、弹窗这类"浮起来的表面"
 * - `surfaceVariant` / `onSurfaceVariant`  次级表面（分隔线、未选中的标签）
 *
 * 界面代码里永远只写 `MaterialTheme.colorScheme.xxx`，**从不写死颜色**。
 * 这样切到深色模式时，所有控件自动跟着变——这就是分两套方案的价值。
 *
 * ## ⚠️ 只给了部分字段会怎样
 *
 * `lightColorScheme()` 是个普通函数，没传的参数会用**M3 的默认值**——
 * 而那个默认值是 M3 的基础紫色系（#6750A4 那一套）。
 * 所以如果你只改了 `primary`，界面会出现"蓝按钮 + 紫开关"的混搭。
 *
 * 本项目把实际用到的槽位都显式配了，避免这种意外。
 * 加新组件时如果颜色看着不对劲，先回来检查是不是碰到了没配的槽位。
 *
 * ## 为什么不用动态取色（Android 12+ 的壁纸取色）
 *
 * 系统提供了 `dynamicLightColorScheme(context)`，能从用户壁纸里生成配色，
 * 看着很酷。但我们不用，原因：
 * - 课程色块的颜色（见下面的 CoursePalette）是**固定 8 色**，
 *   如果主题色跟着壁纸变，色块和主题可能撞色（比如壁纸是橙色，
 *   主题变橙，橙色课程块就糊在背景里了）
 * - 我们要的是一套能预期的、稳定的视觉，不同手机上长得一样
 */

/**
 * 浅色方案。
 *
 * 值都是手工挑的：偏冷蓝做主色（`0xFF185FA5`），绿色做次色
 * （`0xFF0F6E56`），背景是带一点点暖调的灰白（`0xFFF7F7F5`）而不是纯白——
 * 纯白在大屏上太刺眼。
 */
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
 * 深色方案。
 *
 * ⚠️ 注意这不是把上面的颜色"取反"——深色模式需要**重新选色**：
 * - 主色要变浅（`0xFF85B7EB` 而不是深蓝），因为深色背景上深蓝几乎看不见
 * - `onPrimary` 要变深（`0xFF042C53`），因为现在底色是浅的
 * - `background` 不是纯黑（`0xFF1B1B19`）：纯黑配白字对比过强、看久了累，
 *   而且 OLED 屏上纯黑区域的边缘会有拖影
 *
 * 一句话规律：**深色模式下，"色"变亮，"底色"变暗**。
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
 * 主题入口。在 MainActivity 里包住整棵树。
 *
 * @param darkTheme 默认值 `isSystemInDarkTheme()` 会读系统设置里的"深色主题"开关，
 *                  也就是说用户跟着系统走，我们不需要自己做开关。
 *                  这个参数留成可传的，是为了让 @Preview 能强制指定
 *                  （预览面板不会跟随系统主题）。
 * @param content   尾随 lambda，写 `CourseScheduleTheme { ... }` 时括号里的内容。
 *                  `@Composable () -> Unit` 是 Compose 里"一段界面"的标准类型。
 */
@Composable
fun CourseScheduleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        // 三行 if 决定用哪套。这个值往下通过 CompositionLocal 传递，
        // 所以树里任何位置都能读到，不需要一层层当参数传。
        colorScheme = if (darkTheme) DarkColors else LightColors,
        // typography / shapes 都用 M3 默认值，没传。
        content = content
    )
}

/**
 * # 课程块配色
 *
 * ## 关键设计：颜色由课程自己的 `colorIndex` 决定，跟插入顺序无关
 *
 * 反过来做（按"第几门课"取色）会出问题：删掉第 2 门课之后，
 * 第 3、4 门课的颜色会全部往前挪一位——用户会以为课变了。
 *
 * `colorIndex` 是存在 `Course` 表里的一个普通字段（0–7），
 * 用户在加课页可以自己点圆点改。存下来之后颜色就固定了。
 *
 * ## 为什么用 `object` 而不是普通 `class` 或顶层函数
 *
 * - 不需要实例（全是常量 + 无状态函数），`object` 天然单例
 * - 放在 `ui.theme` 包里跟主题在一起，因为它本质是"配色表"
 * - 用 `object` 而不是顶层 `val colors`：调用处 `CoursePalette.colors`
 *   比一个裸的 `courseColors` 更有上下文，也不容易跟别处重名
 */
object CoursePalette {
    /**
     * 8 个颜色。选的都是在深色/浅色背景上都能看清的中等明度，
     * 且两两之间色相拉得比较开（蓝/橙/绿/紫/黄/粉/青/灰），
     * 相邻色号的课程块不会糊在一起。
     */
    val colors = listOf(
        Color(0xFF378ADD),  // 蓝
        Color(0xFFD85A30),  // 橙
        Color(0xFF639922),  // 绿
        Color(0xFF7F77DD),  // 紫
        Color(0xFFBA7517),  // 黄
        Color(0xFFD4537E),  // 粉
        Color(0xFF1D9E75),  // 青
        Color(0xFF888780)   // 灰
    )

    /**
     * 按下标取色，越界时绕回。
     *
     * ⚠️ 为什么是**双重取模**而不是简单的 `colors[index % colors.size]`：
     * Kotlin 的 `%` 对负数返回负数。如果 index 是 -1，
     * `-1 % 8 == -1`，`colors[-1]` 直接抛 `IndexOutOfBoundsException`。
     *
     * `((index % size) + size) % size` 是让负数也能落到 [0, size) 的通用写法：
     * `((-1 % 8) + 8) % 8 == 7`，正确落到最后一个颜色。
     *
     * 现实里 `colorIndex` 是 0–7 的正整数，这个分支永远走不到。
     * 但色板以后可能从 8 个减到 6 个，老数据里的 7 就会越界——
     * 这一行是防止那时候崩溃的保险。
     */
    fun colorOf(index: Int): Color = colors[((index % colors.size) + colors.size) % colors.size]
}
