package com.courseschedule.feature.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.courseschedule.core.TimeUtils
import com.courseschedule.ui.CardShell
import com.courseschedule.ui.EmptyHint
import com.courseschedule.ui.HintText
import com.courseschedule.ui.SectionTitle
import com.courseschedule.ui.appViewModel
import com.courseschedule.ui.theme.CoursePalette
import java.time.LocalDate

/**
 * 周视图的三个尺寸常量。
 *
 * 声明在**文件顶层**且是 `private`，意思是"只在这个文件里可见"。
 * 为什么不放进某个 `object` 里：因为它们只被下面的 Composable 用，
 * 加一层容器只是多写几个字符，没有额外好处。
 * 为什么不写死在用到的地方：这三个值之间有**联动关系**——
 * 课程块的 `offset` 和 `height` 都是 [CELL_HEIGHT] 的整数倍，
 * 改一处而漏改另一处，课块就会错位。集中在这里，改一个数就全对齐了。
 *
 * `50.dp` 里的 `.dp` 是 Kotlin 的**扩展属性**（和标准库的
 * `.coerceIn()` 同类）。`Int` / `Float` / `Double` 上都有它，
 * 用来把裸数字变成"设备无关像素"类型。
 *
 * ## dp 是什么，为什么不用 px
 *
 * `dp` = density-independent pixel（密度无关像素）。
 * 屏幕上 1dp 在不同手机上对应不同数量的物理像素：
 * 高密度屏幕（1080p 手机）上 1dp ≈ 3 个物理像素，低密度上 ≈ 1 个。
 *
 * 好处是**同一个布局在各种手机上看起来一样大**。
 * 如果这里用 `px`，在高分屏上整个课表会缩成一团。
 * 唯一例外是 [View] 里那些 0.5dp 的细线——那是刻意要细，所以也不该用 px。
 *
 * 顺便记住换算：`1.sp` 是字体专用的单位，它还会跟着用户的
 * "系统字体大小"设置缩放，所以**字体一律用 sp，尺寸一律用 dp**。
 */
private val CELL_HEIGHT = 50.dp
private val LABEL_WIDTH = 46.dp
private val HEADER_HEIGHT = 44.dp

/**
 * 课表主界面：周次栏 + 今日课程卡片 + 星期表头 + 7×N 课格。
 *
 * ## Compose 的 Composable 到底是什么
 *
 * `@Composable` **不是一个普通函数**。Kotlin 编译器会为它插桩
 * （改写成带隐藏参数的形式），让它能：
 * - 被 Compose 运行时记录进一棵"组合树"；
 * - 在数据变化时被**重新执行**（这个过程叫**重组 / recomposition**）。
 *
 * 所以心智模型要换掉：这不是"创建一次就完事的界面构造函数"，
 * 而是**一份可以被反复执行的描述**。
 * 每次 `state` 变化，整个函数体会重跑一遍，产出新的界面描述，
 * Compose 对比新旧描述，只更新真正变了的部分。
 *
 * 这就是为什么下面看不到任何 `setText()` 之类的"修改"操作——
 * 你不改界面，你描述界面在某个状态下应该长什么样。
 *
 * ## 三个回调参数为什么用 lambda 而不是直接传 ViewModel
 *
 * `onOpenSettings` / `onAddCourse` / `onEditCourse` 都是 `() -> Unit` 之类的
 * 函数类型。让**上层**（[com.courseschedule.MainActivity]）决定"点了之后去哪"，
 * 本组件只负责"报出用户想干什么"。
 *
 * 这么做的好处是这几个都不能被打破的：
 * 1. **可预览**：Android Studio 的 `@Preview` 里没有 NavController，
 *    但可以传空 lambda 进去看界面；
 * 2. **可复用**：同一个课表组件放进平板分栏布局时，点一下不需要跳页面；
 * 3. **不越界**：本组件完全不知道"设置页"这个东西存在，
 *    也不会 import 任何导航相关的类。
 *
 * @param onOpenSettings 点右上角"设置"时触发。
 * @param onAddCourse 点课格空白处时触发，参数是"星期几 + 从第几节开始"，
 *   用来预填加课页。注意当前实现里永远传 `section = 1`，
 *   也就是只看日期不看具体点了哪一格——因为要精确到节次，
 *   得自己算点击坐标落在哪一行，收益不大（用户进到加课页还是要手动调节次）。
 * @param onEditCourse 点已有的课程块时触发，参数是课程 id。
 */
@Composable
fun ScheduleScreen(
    onOpenSettings: () -> Unit,
    onAddCourse: (dayOfWeek: Int, section: Int) -> Unit,
    onEditCourse: (courseId: Long) -> Unit
) {
    /**
     * 取 ViewModel。
     *
     * [appViewModel] 是本项目自己的封装（见 [com.courseschedule.ui.VmFactory]），
     * 它内部做了两件事：从 Application 里取 Repository，然后建 ViewModel。
     * 用 `viewModel { }` 而不是 `remember { ScheduleViewModel(...) }` 的原因是：
     * **ViewModel 必须在界面重建时存活**。用 `remember` 的话，转屏之后就被重建了，
     * 用户翻到第 8 周的状态会丢——那就白用 ViewModel 了。
     *
     * 注意下面那行的调用形式：`{ ScheduleViewModel(it) }` 里的 `it` 就是 Repository。
     */
    val viewModel = appViewModel { ScheduleViewModel(it) }

    /**
     * 订阅状态流，并转成 Compose 能感知的 `State`。
     *
     * ## `by` 是什么
     *
     * `val state by ...` 是 Kotlin 的**委托属性**。这里的 `State<T>` 实现了
     * `getValue` 操作符，所以 `state` 可以直接当成 [ScheduleUiState] 用，
     * 不用写 `state.value.semester`。
     *
     * 但**读值这个动作本身是有魔法的**：Compose 运行时能察觉到"这个
     * `@Composable` 读了哪个 State"，于是把它登记成"订阅者"。
     * 下次这个 State 变了，只有读过它的那些组件会被重新执行。
     * 这就是 Compose 做到"局部刷新"的机制——不需要你手写"哪里要更新"。
     *
     * ## 为什么用 `collectAsStateWithLifecycle` 而不是 `collectAsState`
     *
     * 后者在**界面退到后台时仍然继续收集**数据流。对这个 app 来说，
     * 退到后台还在跑数据库监听、还在重组，纯属浪费电。
     *
     * `collectAsStateWithLifecycle` 会跟随界面的生命周期自动暂停/恢复收集。
     * 名字里的 `WithLifecycle` 就是这个意思。**新代码一律用这个**，
     * 只有老项目里才会见到裸的 `collectAsState`。
     */
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    /**
     * 还没有学期 → 显示转圈，然后 `return` 提前退出。
     *
     * ## 这里为什么可以直接 `return`
     *
     * 因为 `@Composable` 函数允许提前返回。重建时每次都会重新判断，
     * 一旦 `semester` 有值了就会往下走到真正的界面。
     *
     * 注意判的是 `state.semester == null` 而不是 `state.loading`——
     * 两者语义不同：`loading` 在拿到学期后就变 false 了，
     * 而 `semester` 一旦有值就永远是值。用 `semester` 判断更不容易出错。
     */
    if (state.semester == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    /**
     * 整体纵向排列：周次栏 → 今日卡片 → 表头 → 可滚动的课格。
     *
     * `Modifier.fillMaxSize()` 让 Column 占满整个可用区域。
     *
     * 注意课格外面那个 `Box` 用了 **`.weight(1f)`**：意思是"把剩余空间全拿走"。
     * 上面三块是按内容高度自适应，剩下的都给课格区。
     * 这个 `weight` 是必须的——没有它，课格区高度就是它内容的自然高度
     * （12 节 × 50dp = 600dp），小屏手机上会超出屏幕，且外面不会出现滚动。
     *
     * `1f` 是浮点数 `1.0`。多块用不同权重可以按比例分配剩余空间。
     */
    Column(Modifier.fillMaxSize()) {
        WeekBar(
            state = state,
            onPrevious = viewModel::previousWeek,
            onNext = viewModel::nextWeek,
            onBackToCurrent = viewModel::goToCurrentWeek,
            onOpenSettings = onOpenSettings
        )
        TodayCard(state = state, onEditCourse = onEditCourse)
        WeekHeader(state)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            WeekGrid(state = state, onAddCourse = onAddCourse, onEditCourse = onEditCourse)
        }
    }
}

/**
 * 顶部周次栏：第几周、学期信息、翻页按钮、设置入口。
 *
 * ## `private fun` 加 `@Composable` 是什么意思
 *
 * 它仍然是一个 Compose 组件，只是**只在本文件内可见**。
 * 判断一个组件要不要公开，看有没有第二个文件需要它：
 * 这个组件只服务周视图，公开出去反而增加被误用的机会。
 *
 * ## 参数为什么传 `state` 而不是只传需要的几个字段（比如 `week`）
 *
 * 权衡过：这个组件要用 `state` 里的 `semester` / `week` / `totalWeeks` /
 * `isCurrentWeek` 四项。一个一个传参数会变成 4 个独立参数，
 * 签名很长，而且以后加一个字段就要改签名。
 *
 * 传整个 `state` 的代价是"这个组件理论上能碰到所有字段"，
 * 好处是**改动成本低**。在这种"和 state 一对一"的展示型组件里，
 * 传整个 state 是更实际的选择。从实践看，这类组件几乎不会复用，
 * 所以"耦合紧"这个缺点影响很小。
 *
 * 真正需要复用的是 [com.courseschedule.ui.Components] 里那些
 * 通用组件（`SectionTitle` / `NumberStepper` 等），那些用细粒度参数。
 *
 * @param onPrevious 点"上一周"。
 * @param onNext 点"下一周"。
 * @param onBackToCurrent 点"回到本周"。注意它只在非本周时才显示。
 * @param onOpenSettings 点"设置"。
 */
@Composable
private fun WeekBar(
    state: ScheduleUiState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onBackToCurrent: () -> Unit,
    onOpenSettings: () -> Unit
) {
    /**
     * 学期为空就直接跳出。
     *
     * `?: return` 是 Elvis 运算符 + 提前返回的组合写法，读作
     * "如果 state.semester 是 null，就结束这个函数"。
     *
     * 理论上调用方 [ScheduleScreen] 已经判过非空了，这里判是**防御性**的：
     * 组件本身不依赖调用方的检查，这样它挂到任何地方都安全。
     * 代价是一行代码，收益是这个组件可以独立预览、独立测试。
     *
     * 这里返回后 `semester` 被编译器智能转换成非空类型，
     * 所以下面可以直接写 `semester.weekOverride` 不需要 `?.`。
     */
    val semester = state.semester ?: return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            /**
             * `SpaceBetween` 把子元素推到两端——左边是"第 N 周"那一块，
             * 右边是"设置"按钮。这比手算边距稳妥，也不会因为字号变化而错位。
             */
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    /**
                     * `style = MaterialTheme.typography.titleLarge` 是从
                     * **主题**里取字号。这是 Material 3 的推荐做法：
                     * 不要手写 `fontSize = 22.sp`，因为主题里
                     * `titleLarge` 的定义会跟随系统字体设置、也会被
                     * 用户的辅助功能选项影响。
                     *
                     * 但本项目里也有很多地方直接写了 `fontSize = 13.sp`。
                     * 原因是课表格子里的字需要精确控制（格子就 50dp 高，
                     * 按主题字号放不下），属于合理例外。
                     */
                    Text(
                        text = "第 ${state.week} 周",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium
                    )
                    /**
                     * 手动周次的小标签。`weekOverride != null` 表示
                     * 用户在设置页开了"手动指定当前周"（假期调课场景）。
                     *
                     * 必须显式提示——否则用户会困惑"为什么今天明明是第 3 周，
                     * app 却显示第 5 周"。这个标签就是那个解释。
                     */
                    if (semester.weekOverride != null) {
                        Box(
                            Modifier
                                .padding(start = 8.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "手动",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
                /**
                 * 学期摘要行。注意这里用了**字符串模板 + 相邻字符串拼接**：
                 * `${semester.name}` 是模板插值，后面那个 `+` 是把两段
                 * 长字符串拼起来免得一行太长。
                 *
                 * `TimeUtils.epochDayToText` 把 epochDay 转成 `2026-10-12`
                 * 这种可读格式——直接显示 20738 没人看得懂。
                 */
                HintText(
                    "${semester.name} · 共 ${state.totalWeeks} 周 · 开学 " +
                        TimeUtils.epochDayToText(semester.startEpochDay)
                )
            }
            TextButton(onClick = onOpenSettings) { Text("设置") }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onPrevious,
                /**
                 * 默认的按钮内边距很大（为了满足触摸目标最小尺寸），
                 * 这里手动收窄，让两个按钮能并排放下。
                 * `PaddingValues(horizontal = ..., vertical = ...)` 是
                 * Compose 里表达内边距的通用对象。
                 */
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) { Text("上一周", fontSize = 13.sp) }
            OutlinedButton(
                onClick = onNext,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) { Text("下一周", fontSize = 13.sp) }
            /**
             * "回到本周"**只在非本周时出现**。
             *
             * 用 `if` 直接包住组件，而不是"显示但禁用"——
             * 因为大多数人停在的是本周，那个按钮在他们眼里就是噪声。
             * Compose 里 `if` 包组件是完全正常的写法：
             * 条件为 false 时该组件不进入组合树，不占布局位置。
             */
            if (!state.isCurrentWeek) {
                TextButton(onClick = onBackToCurrent) { Text("回到本周", fontSize = 13.sp) }
            }
        }
    }
}

/**
 * 今日课程卡片。
 *
 * 两种形态：
 * 1. **非本周**时显示一条提示（"正在浏览第 N 周"），因为"今天"只对本周有意义；
 * 2. **本周**时列出今天所有课，正在上的那节带"上课中"标签。
 *
 * @param onEditCourse 点某一节课时跳去编辑。
 */
@Composable
private fun TodayCard(state: ScheduleUiState, onEditCourse: (Long) -> Unit) {
    /**
     * 非本周 → 只显示提示，然后提前返回。
     *
     * 这里用 `return` 而不是 `if/else` 包住剩余全部内容，
     * 是为了避免整段缩进一层。Composable 里提前返回是常见做法。
     */
    if (!state.isCurrentWeek) {
        CardShell(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            HintText("正在浏览第 ${state.week} 周（不是本周），点「回到本周」跳回。")
        }
        return
    }

    /**
     * 把 epochDay 转成日期对象，用来取"几月几日"。
     *
     * 可以直接在 Composable 里写这种纯计算——它不是副作用，
     * 每次重组重算一遍的开销可以忽略。**只有"有副作用的操作"
     * （读写文件、发请求、改外部状态）才需要包在 `LaunchedEffect` 里。**
     */
    val date = LocalDate.ofEpochDay(state.todayEpochDay)
    CardShell(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("今日课程")
                HintText(
                    text = "${date.monthValue} 月 ${date.dayOfMonth} 日 ${weekDayName(date.dayOfWeek.value)}",
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            /**
             * 空态处理。列表为空时显示一句提示，而不是留一片空白——
             * 空白会让用户以为"是不是没加载出来"。
             */
            if (state.todayEntries.isEmpty()) {
                EmptyHint("今天没有课")
            } else {
                /**
                 * `forEach` 直接遍历渲染，**没有用 `LazyColumn`**。
                 *
                 * 原因：一天的课最多十几节，全部渲染的开销可以忽略；
                 * 而 LazyColumn 会引入"只有可见项被渲染"的复杂度，
                 * 还在这个已经在滚动容器里的场景下容易出高度问题。
                 * **列表短就用 forEach，长（几十条以上）才用 LazyColumn。**
                 */
                state.todayEntries.forEach { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        /**
                         * 固定宽度的左侧时间列。
                         *
                         * 宽度写死 52dp 是为了**让所有行的课程名左边缘对齐**。
                         * 如果用自适应宽度，`08:00` 和 `09:40` 宽度一样没问题，
                         * 但一旦出现 `8:00` 这种短字符串（理论上不会，
                         * 因为 [TimeUtils.minutesToText] 一定补零），列宽就会跳。
                         */
                        Column(Modifier.width(52.dp)) {
                            Text(
                                text = TimeUtils.minutesToText(entry.startMinute),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = TimeUtils.minutesToText(entry.endMinute),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        /**
                         * 课程颜色的小竖条。
                         *
                         * 用 `Box` + 链式 `Modifier` 画一个纯色矩形——
                         * Compose 里没有"色块"这种控件，但用
                         * `Modifier.background()` 配 `clip()` 就能画出来。
                         * 所以**不要觉得"没有对应控件就画不出来"**，
                         * 绝大多数视觉元素都能用 Box + Modifier 拼出来。
                         *
                         * `CoursePalette.colorOf(index)` 会做取模，
                         * 所以 `colorIndex` 越界也不会崩。
                         */
                        Box(
                            Modifier
                                .width(4.dp)
                                .height(30.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(CoursePalette.colorOf(entry.course.colorIndex))
                        )
                        Column(
                            Modifier
                            /**
                             * `weight(1f)` 让这一列把剩余宽度全吃掉。
                             * 这样长课程名会正常换行/省略，而不是把
                             * 右侧挤出屏幕。
                             */
                                .weight(1f)
                                .padding(start = 10.dp)
                                .clickable { onEditCourse(entry.course.id) }
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = entry.course.name,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                /**
                                 * "上课中"标签。`entry.ongoing` 由 ViewModel 每 30 秒重算，
                                 * 所以它会在上课时间到点时自动亮起，不需要任何额外的定时逻辑。
                                 */
                                if (entry.ongoing) {
                                    Box(
                                        Modifier
                                            .padding(start = 6.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(MaterialTheme.colorScheme.primary)
                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                    ) {
                                        Text(
                                            text = "上课中",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                    }
                                }
                            }
                            /**
                             * 拼出"教室 · 教师 · 第 1-2 节"这一行。
                             *
                             * ## `listOfNotNull` 解决什么问题
                             *
                             * 教室和教师都可能为空。如果直接拼
                             * `"${room} · ${teacher} · 第 x-y 节"`，
                             * 没教室时会出现"` · 张三 · 第 1-2 节`"
                             * 这种以分隔符开头的难看结果。
                             *
                             * `listOfNotNull(a, b, c)` 会把 `null` 元素**直接丢掉**，
                             * 再用 `joinToString(" · ")` 拼起来，分隔符就不会多余。
                             * 所以这里的 `ifBlank { null }` 是刻意的——
                             * 把"空字符串"转成 `null` 好让 `listOfNotNull` 丢掉它。
                             *
                             * 最后那一项"第 x-y 节"永远非空，所以结果一定不会是空串。
                             *
                             * 这个模式（把可选的字符串转成 null、用 listOfNotNull 收集、
                             * joinToString 拼接）在 Android 里非常常见，值得记住。
                             */
                            val sub = listOfNotNull(
                                entry.room.ifBlank { null },
                                entry.course.teacher.ifBlank { null },
                                "第 ${entry.slot.startSection}-${entry.slot.endSection} 节"
                            ).joinToString(" · ")
                            HintText(sub)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 星期表头：周一～周日 + 各自的日期，今天那一列高亮。
 *
 * ## 为什么表头要和下面的课格分成两个组件
 *
 * 因为**表头不滚动、课格要滚动**。
 *
 * [ScheduleScreen] 里的结构是：表头在可滚动区**外面**，
 * 课格在 `verticalScroll` **里面**。这样上下滑动课表时表头固定不动。
 *
 * 如果表头也放进滚动区，用户往下滚看第 10 节的课时就看不见"哪列是周三"了。
 * 代价是表头里的列宽必须和课格里完全一致——所以两处都用
 * `LABEL_WIDTH` 和 `weight(1f)` 这套相同的规则来计算宽度，靠复用常量保证一致。
 */
@Composable
private fun WeekHeader(state: ScheduleUiState) {
    /**
     * 把主题里的主色取出来存成局部变量。
     *
     * `MaterialTheme.colorScheme.primary` 是一个 Composable 读取操作
     * （它会订阅主题变化）。在这个函数里读 3 次和读 1 次的效果一样，
     * 但读 1 次存起来更短也更清楚。
     */
    val primary = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().height(HEADER_HEIGHT)) {
        /**
         * 左边留一个和节次列等宽的空 Box。
         *
         * 这个**占位是必须的**——否则表头的 7 列会向左平移
         * [LABEL_WIDTH] 的距离，和下面的课格对不上。
         * 这类"用空 Box 占位对齐"是 Compose 里常见的技巧，
         * 因为没有 CSS 那种 `padding-left` 能一眼看出意图。
         */
        Box(Modifier.width(LABEL_WIDTH))
        for (day in 1..7) {
            val epochDay = state.weekStartEpochDay + (day - 1)
            val date = LocalDate.ofEpochDay(epochDay)
            /**
             * 判断这一列是不是"今天"。
             *
             * 注意比较的是 **epochDay**（日期），不是"星期几"——
             * 否则看第 8 周时所有周三都会被高亮。
             * 这正是 [com.courseschedule.feature.schedule.ScheduleUiState.weekStartEpochDay]
             * 和 `todayEpochDay` 要各自算好的原因。
             */
            val isToday = epochDay == state.todayEpochDay
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    /**
                     * ## `Modifier` 的书写顺序为什么重要
                     *
                     * 这是 Compose 最容易踩的坑之一：**修饰符按顺序依次生效**，
                     * 换顺序结果就不同。
                     *
                     * 以这里为例，链条是 `weight → fillMaxHeight → background`。
                     * 可以理解成"每一环都在包住上一个结果"，
                     * 所以 `background` 画在"已经确定尺寸的那块区域"上，
                     * 正好铺满整个格子高度。
                     *
                     * 如果写成 `.background(...).fillMaxHeight()`，
                     * 背景会画在"尺寸还没确定"的状态上，表现为**颜色条高度不对**。
                     *
                     * 记忆方法：**布局类修饰符（size / weight / padding）放前面，
                     * 绘制类修饰符（background / border / clip）放后面**。
                     * 但 `clip` 必须在 `background` 之前，否则背景会溢出圆角。
                     */
                    .background(
                        if (isToday) primary.copy(alpha = 0.10f) else Color.Transparent
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                /**
                 * `primary.copy(alpha = 0.10f)` 是 Color 的 `copy` 方法
                 * （和 data class 的 copy 是两回事，这个是 Compose 给 Color 加的），
                 * 用来生成"同一个颜色、透明度 10%"的版本。
                 *
                 * 用低透明度而不是写死一个浅灰，好处是**跟着主题走**——
                 * 暗色主题下主色变浅，这个高亮也不会显得突兀。
                 */
                Text(
                    text = weekDayName(day),
                    fontSize = 11.sp,
                    color = if (isToday) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (isToday) FontWeight.Medium else FontWeight.Normal
                )
                Text(
                    /**
                     * `"${date.monthValue}/${date.dayOfMonth}"` 得到 `10/12`。
                     * 用 `monthValue` 而不是 `month`——后者返回的是
                     * `Month` 枚举对象，打印出来是 `OCTOBER` 而不是 `10`。
                     */
                    text = "${date.monthValue}/${date.dayOfMonth}",
                    fontSize = 10.sp,
                    color = if (isToday) primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 7 天 × N 节的课格，是整个 app 里最复杂的一段布局。
 *
 * ## 为什么不用 `LazyVerticalGrid`（这个决定值得单独讲）
 *
 * 第一直觉肯定是"7 列的网格 → 用 `LazyVerticalGrid(columns = GridCells.Fixed(7))`"。
 * 但它在这里行不通，有两个具体原因：
 *
 * 1. **滚动方向冲突**。本组件外面已经套了一层 `verticalScroll`
 *    （见 [ScheduleScreen]）。`LazyVerticalGrid` 自己就是滚动容器，
 *    而且它需要"无限高度约束"才能工作——两个纵向滚动容器嵌套，
 *    Compose 会在运行期直接抛异常：
 *    `Vertically scrollable component was measured with an infinity maximum height constraints`。
 * 2. **格子高度是算出来的**。`LazyVerticalGrid` 的 item 高度由内容决定，
 *    而课块的跨行高度必须是 `CELL_HEIGHT × 节数` 才能和左侧节次列对齐。
 *
 * ## 所以改用「绝对定位」
 *
 * 结构是"一列一个 `Box`，`Box` 内部按节次用 `offset` 摆放课程块"：
 *
 * ```
 * Row
 * ├── Column  左侧节次列（每个格子固定 CELL_HEIGHT 高）
 * └── Box ×7  每天的柱子，高度 = CELL_HEIGHT × sections
 *     ├── Column   底层：网格线 + 点击新增
 *     └── Box ×N   上层：课程块，用 offset(y = CELL_HEIGHT × (起始节次-1)) 定位
 * ```
 *
 * 高度是**算出来的确定值**（12 节 × 50dp = 600dp），不存在测量不确定性，
 * 也就不会触发上面那个异常。滚动完全交给外层。
 *
 * @param onAddCourse 点空白格子时触发，参数是"星期几、第几节"。
 * @param onEditCourse 点课程块时触发。
 */
@Composable
private fun WeekGrid(
    state: ScheduleUiState,
    onAddCourse: (Int, Int) -> Unit,
    onEditCourse: (Long) -> Unit
) {
    val sections = state.gridSections
    /**
     * 把作息表转成"节次 → 作息"的 Map，方便下面按节次查时间。
     *
     * `associateBy { it.section }` 是 Kotlin 标准库的扩展函数，
     * 把 `List<T>` 变成 `Map<Key, T>`。等价于 C++ 里手写一个循环往
     * `std::unordered_map` 里塞。
     *
     * 在循环**外面**建一次、循环里复用，比在循环里反复 `firstOrNull` 查找
     * 快得多——虽然这里数据量小，但这是应该养成的习惯。
     */
    val periodBySection = state.periods.associateBy { it.section }
    val outline = MaterialTheme.colorScheme.outline
    val primary = MaterialTheme.colorScheme.primary

    Row(Modifier.fillMaxWidth()) {
        // 左侧节次列
        Column(Modifier.width(LABEL_WIDTH)) {
            for (section in 1..sections) {
                Column(
                    /**
                     * 每一格固定 [CELL_HEIGHT] 高。
                     *
                     * 右边课格里的课程块高度也是 `CELL_HEIGHT × 节数`，
                     * 两处必须用同一个常量，否则"第 3 节"在左边和右边的
                     * 纵坐标会差几个像素，看起来就是没对齐。
                     */
                    modifier = Modifier.fillMaxWidth().height(CELL_HEIGHT),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "$section",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    /**
                     * `?.let { }` 是"安全调用 + 作用域函数"的组合：
                     * 只有查到了作息才执行 lambda，查不到就整块跳过。
                     *
                     * 为什么要判空：用户可能把作息表删到只剩 6 节，
                     * 而 `gridSections` 因为课程占了第 8 节而仍是 8。
                     * 这时第 7、8 节就没有作息数据，但格子还要画出来。
                     */
                    periodBySection[section]?.let { period ->
                        Text(
                            text = TimeUtils.minutesToText(period.startMinute),
                            fontSize = 9.sp,
                            /**
                             * `copy(alpha = 0.7f)` 让时间比节次号更淡一点，
                             * 形成视觉层次——用户先看到"第几节"，
                             * 需要时才看具体时间。
                             */
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

        // 七天课格
        for (day in 1..7) {
            val epochDay = state.weekStartEpochDay + (day - 1)
            val isToday = epochDay == state.todayEpochDay

            Box(
                Modifier
                    .weight(1f)
                    /**
                     * 整根柱子的高度 = 单格高 × 节数。
                     *
                     * `CELL_HEIGHT * sections` 里 `sections` 是 `Int`，
                     * 所以这是 `Dp * Int`——Compose 给 `Dp` 重载了 `times(Int)`，
                     * 结果仍是 `Dp`。所以可以放心地乘。
                     */
                    .height(CELL_HEIGHT * sections)
                    .background(if (isToday) primary.copy(alpha = 0.06f) else Color.Transparent)
            ) {
                // 底层：网格线 + 点空白处新增课程（预填这一天）
                Column(
                    Modifier
                        /**
                         * `matchParentSize()` 让它和父 `Box` 一样大。
                         *
                         * **注意它和 `fillMaxSize()` 的区别**，这个区别很关键：
                         * - `fillMaxSize()` 会**参与父容器的尺寸计算**，
                         *   意思是"我要占满"，从而可能把父容器撑大；
                         * - `matchParentSize()` **不参与**父容器的尺寸计算，
                         *   只是"测量完之后跟着父容器的大小走"。
                         *
                         * 这里必须用后者：父 Box 的高度是由 `.height(CELL_HEIGHT * sections)`
                         * 定的，如果子元素用 `fillMaxSize()`，在某些约束下会出现
                         * 循环依赖（父等子、子等父），Compose 会给出莫名其妙的尺寸。
                         */
                        .matchParentSize()
                        .clickable { onAddCourse(day, 1) }
                ) {
                    for (section in 1..sections) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(CELL_HEIGHT)
                                /**
                                 * 只画 0.5dp 的细边框。透明度 0.35 让它很淡——
                                 * 网格线的作用是"暗示格子的存在"，不该抢眼。
                                 *
                                 * 用 `border` 而不是 `background` 是因为
                                 * 我们要的是"边框"而不是"填充"。
                                 */
                                .border(0.5.dp, outline.copy(alpha = 0.35f))
                        )
                    }
                }

                // 上层：课程块（比背景层晚绘制，点击优先命中）
                /**
                 * ## 为什么课程块写在网格线**后面**就能盖住它、并且优先响应点击
                 *
                 * Compose 里同一个 `Box` 内的子元素**按声明顺序绘制**，
                 * 后声明的画在上面（这就是 z 轴顺序，等价于 CSS 的 `z-index`）。
                 *
                 * 点击同理：命中测试从上层往下找，上面的先拿到事件。
                 * 所以课程块能正常点击，点不到下面那层的"新增课程"。
                 *
                 * **这一点很重要**：如果顺序反了，点课程块会变成"新增课程"，
                 * 而且因为上层是透明的，看起来完全没毛病——是个很难查的 bug。
                 */
                state.activeCourses
                    /**
                     * 只取这一天的。`activeCourses` 已经过滤掉了
                     * "这周不上的"（单双周、起止周），这里只按星期分列。
                     */
                    .filter { it.slot.dayOfWeek == day }
                    .forEach { placed ->
                        /**
                         * 把节次范围夹到"当前格子数"之内。
                         *
                         * 防止的是一种真实情况：用户先加了"第 1-12 节"的课，
                         * 然后把作息删到 6 节，于是 `gridSections` 变成 10
                         * （下限兜底），而课还占着第 12 节。
                         * 不夹的话课块会被画到格子外面，甚至盖住下面的内容。
                         *
                         * `endSection` 用的是 `coerceIn(startSection, sections)`，
                         * 下界是 `startSection` 而不是 1——保证结束节次不会小于
                         * 开始节次，否则高度算出来是负数。
                         */
                        val startSection = placed.slot.startSection.coerceIn(1, sections)
                        val endSection = placed.slot.endSection.coerceIn(startSection, sections)
                        Box(
                            Modifier
                                /**
                                 * ## `offset` 是绝对定位
                                 *
                                 * `offset(y = ...)` 让元素**在正常布局位置的基础上偏移**。
                                 *
                                 * 因为父 `Box` 里每个课程块都是"从左上角开始布局"的，
                                 * 所以 `y = CELL_HEIGHT * (startSection - 1)` 就把
                                 * "第几节"翻译成了像素位置：
                                 * - 第 1 节 → 偏移 0
                                 * - 第 3 节 → 偏移 2 × 50dp = 100dp
                                 *
                                 * ## 为什么 `offset` 放在链的**最前面**
                                 *
                                 * 回看修饰符顺序规则：`offset` 是布局类修饰符，
                                 * 要放在 `height` / `padding` / `background` 之前。
                                 * 如果放到最后，背景会先按"没偏移的位置"画一遍，
                                 * 结果是色块位置和背景对不上（能看到重影或错位）。
                                 *
                                 * 另外 `offset` **不影响点击区域的位置**——
                                 * 但也不会让点击区域错位，因为它是在布局阶段生效的，
                                 * 命中测试会跟着一起挪。
                                 */
                                .offset(y = CELL_HEIGHT * (startSection - 1))
                                /**
                                 * 跨节课程块的高度 = 单格高 × 占的节数。
                                 * `endSection - startSection + 1` 要 +1 是因为
                                 * "第 1-2 节"占了 **2** 格（2-1+1），不是 1 格。
                                 */
                                .height(CELL_HEIGHT * (endSection - startSection + 1))
                                .fillMaxWidth()
                                /**
                                 * 1dp 内边距让相邻的课程块之间有一条缝，
                                 * 不会糊成一片。这个 padding 在 `background` 之前，
                                 * 所以它缩小的是**背景色的范围**。
                                 */
                                .padding(1.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(CoursePalette.colorOf(placed.course.colorIndex))
                                .clickable { onEditCourse(placed.course.id) }
                                /**
                                 * 这一层 padding 在 `clickable` **之后**，
                                 * 所以它缩小的是**内容（文字）的范围**，
                                 * 不会缩小可点击区域。顺序不同、效果不同——
                                 * 这正是修饰符顺序重要性的又一个例子：
                                 *
                                 * `clickable` 之前的修饰符决定"可点击区域长什么样"，
                                 * 之后的决定"里面的内容怎么排"。
                                 */
                                .padding(3.dp)
                        ) {
                            Column {
                                Text(
                                    text = placed.course.name,
                                    fontSize = 10.sp,
                                    /**
                                     * `lineHeight` 必须给：默认行高是按
                                     * 字号的 1.4 倍算的，10sp 字号会得到 14sp 行高，
                                     * 两行就是 28sp——在 50dp 高的格子里放不下三行。
                                     * 手动压成 12sp 才能在格子里多塞一点字。
                                     */
                                    lineHeight = 12.sp,
                                    /**
                                     * 色块上的文字颜色**写死白色**，没走主题。
                                     *
                                     * 这是刻意的：[CoursePalette] 里的 8 个颜色
                                     * 都是中深色调，配白字的对比度是够的；
                                     * 而主题里的 `onPrimary` 在暗色模式下会变成深色，
                                     * 配深色块就看不清了。
                                     *
                                     * 代价是如果以后加浅色（比如黄色）到调色板里，
                                     * 得同时改这里。目前 8 个色都够深，先这样。
                                     */
                                    color = Color.White,
                                    /**
                                     * `maxLines` + `overflow` 是文字溢出的标准处理：
                                     * 最多 3 行，超出用省略号。
                                     *
                                     * **一定要给 `maxLines`**：不给的话长课程名
                                     * 会一直往右撑或往下换行，把格子撑破。
                                     */
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                                /**
                                 * 教室只有在非空时才画。用 `isNotBlank()` 而不是
                                 * `isNotEmpty()`——后者对"只有一个空格"会判为有值。
                                 */
                                if (placed.room.isNotBlank()) {
                                    Text(
                                        text = placed.room,
                                        fontSize = 9.sp,
                                        lineHeight = 11.sp,
                                        color = Color.White.copy(alpha = 0.85f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
            }
        }
    }
}

/**
 * 星期几的数字 → 中文名。
 *
 * ## 为什么它**没有** `@Composable` 注解
 *
 * 因为它只做纯计算，不渲染任何东西、也不读任何主题状态。
 * 普通函数更灵活：可以在任何地方调用，包括非 Composable 的上下文里
 * （比如 ViewModel 或单元测试）。
 *
 * **判断标准**：函数里有没有调用别的 `@Composable`、有没有读
 * `MaterialTheme` 这类 Compose 状态。有就是 Composable，没有就不是。
 * 常见错误是给所有函数都加 `@Composable`，那会让这个函数
 * 在测试里没法直接调。
 *
 * ## 为什么不用 `when` 或数组常量
 *
 * 每次调用都会 `listOf(...)` 新建一个列表，理论上有点浪费。
 * 但这里被调用的次数是"表头 7 次 + 今日卡片 1 次" = 每帧 8 次，
 * 建 8 个小列表的开销远小于一次重组的开销，不值得优化。
 *
 * `(day - 1).coerceIn(0, 6)` 是**下标保护**：如果传入 0 或 8
 * 这种越界值，会夹到合法范围而不是抛
 * `IndexOutOfBoundsException`。这类"宁可显示错误内容也不崩"
 * 的取舍在整个项目里是一致的。
 */
private fun weekDayName(day: Int): String =
    listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[(day - 1).coerceIn(0, 6)]
