package com.courseschedule.feature.schedule

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
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
 * 周视图三个尺寸常量。课程块的 offset/height 都是 `CELL_HEIGHT` 的整数倍，
 * 与左侧节次列共用，改一处必须联动，集中声明防止漏改错位。
 */
private val CELL_HEIGHT = 50.dp
private val LABEL_WIDTH = 46.dp
private val HEADER_HEIGHT = 44.dp

/**
 * 今日卡片默认最多显示几节。卡片钉在课格上方，每高一行课格就少一行；
 * 更长的日子靠「展开全部」展开，不截断数据。
 */
private const val TODAY_ROWS_COLLAPSED = 3

/**
 * 课表主界面：周次栏 + 今日课程卡片 + 星期表头 + 7×N 课格。
 * 导航类操作通过回调交给上层，本组件不感知导航，便于预览与复用。
 *
 * @param onAddCourse 点课格空白处触发，用于预填加课页；当前实现固定传 `section = 1`。
 * @param onEditCourse 点已有的课程块时触发，参数是课程 id。
 */
@Composable
fun ScheduleScreen(
    onOpenSettings: () -> Unit,
    onAddCourse: (dayOfWeek: Int, section: Int) -> Unit,
    onEditCourse: (courseId: Long) -> Unit
) {
    // ViewModel 必须在转屏等界面重建时存活，因此用工厂创建而不是 remember。
    val viewModel = appViewModel { ScheduleViewModel(it.repository) }

    // collectAsStateWithLifecycle 跟随界面生命周期自动暂停/恢复收集，
    // 退到后台不再跑数据库监听，比 collectAsState 省电。
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 判 semester 而非 loading：loading 拿到学期后就变 false，
    // semester 一旦有值便恒定，用它判断更可靠。
    if (state.semester == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    // 纵向排列：周次栏 → 今日卡片 → 表头 → 课格。课格区的 weight(1f) 必须有：
    // 否则高度按内容自然展开（12 节约 600dp），小屏放不下且不会出现滚动。
    /**
     * 表格是否可操作。课格满屏都是点击响应区，滑动易误触，所以默认只读。
     * 今日卡片永远只读，这个开关只管下面的表格。
     * rememberSaveable 保住转屏，编辑到一半不被踢回只读。
     */
    var gridEditable by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        WeekBar(
            state = state,
            onPrevious = viewModel::previousWeek,
            onNext = viewModel::nextWeek,
            onBackToCurrent = viewModel::goToCurrentWeek,
            onOpenSettings = onOpenSettings
        )
        TodayCard(
            state = state,
            gridEditable = gridEditable,
            onToggleGridEdit = { gridEditable = !gridEditable }
        )
        WeekHeader(state)
        if (gridEditable) {
            // 编辑提示条放在卡片外：卡片永远只读，不暗示它也能编辑。
            HintText(
                text = "课表已可编辑：点空白格子加课，点课程色块修改",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }
        // 课格区。外层 Box 只为空状态引导提供不参与滚动的坐标系：
        // 引导卡放进滚动容器会跟着课格滚走，无法固定在可视区中央。
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                WeekGrid(
                    state = state,
                    editable = gridEditable,
                    onAddCourse = onAddCourse,
                    onEditCourse = onEditCourse
                )
            }
            // 只读态引导用户先点「修改课表」，不给点了没反应的按钮。
            if (state.courseCount == 0) {
                EmptyGridGuide(
                    editable = gridEditable,
                    onAdd = { onAddCourse(1, 1) },
                    onEnableEdit = { gridEditable = true }
                )
            }
        }
    }
}

/**
 * 空课表时盖在课格中央的引导卡。不换整屏空状态页：7×12 格子本身就是说明书。
 * 卡片没有背景遮罩、外层 Box 不挂 clickable，触摸穿透到下面的格子。
 *
 * @param onAdd 点「添加第一门课」，默认周一第 1 节，进加课页可改。
 * @param onEnableEdit 只读态点「修改课表」，直接帮用户放开表格。
 */
@Composable
private fun EmptyGridGuide(
    editable: Boolean,
    onAdd: () -> Unit,
    onEnableEdit: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            shadowElevation = 6.dp
        ) {
            Column(
                Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "还没有课程",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                HintText(
                    text = if (editable) "点任意空白格子就能加课" else "课表现在是只读的，先点下面的按钮",
                    modifier = Modifier.padding(top = 6.dp)
                )
                // 只读态只给一个按钮，避免先点「添加第一门课」却因表格还锁着没反应。
                if (editable) {
                    OutlinedButton(
                        onClick = onAdd,
                        modifier = Modifier.padding(top = 12.dp)
                    ) {
                        Text("添加第一门课", fontSize = 13.sp)
                    }
                } else {
                    Button(
                        onClick = onEnableEdit,
                        modifier = Modifier.padding(top = 12.dp)
                    ) {
                        Text("修改课表", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/**
 * 顶部周次栏：周次、学期信息、翻页按钮、设置入口，仅本文件使用。
 *
 * @param onBackToCurrent 只在非本周时显示。
 */
@Composable
private fun WeekBar(
    state: ScheduleUiState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onBackToCurrent: () -> Unit,
    onOpenSettings: () -> Unit
) {
    // 防御性判空：不依赖调用方检查，组件可独立预览；智能转换后可直接访问非空属性。
    val semester = state.semester ?: return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "第 ${state.week} 周",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium
                    )
                    // `weekOverride != null` 即设置页开了「手动指定当前周」（假期调课），
                    // 必须显式标出，否则用户会困惑显示的周次和实际不一致。
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
                // 收窄默认内边距（默认值按触摸目标尺寸给得较大），让按钮能并排放下。
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) { Text("上一周", fontSize = 13.sp) }
            OutlinedButton(
                onClick = onNext,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) { Text("下一周", fontSize = 13.sp) }
            // 只在非本周时显示：对停在本周的用户，禁用按钮只是噪声。
            if (!state.isCurrentWeek) {
                TextButton(onClick = onBackToCurrent) { Text("回到本周", fontSize = 13.sp) }
            }
        }
    }
}

/**
 * 今日课程卡片，钉在课表上方，不随课格滚动。
 * 两种形态：非本周只显示一条提示（"今天"只对本周有意义），
 * 本周显示日期条 + 课程时间轴。
 * 卡片永远只读——可点会在滑动课表时误触；右上角「修改课表」开关管的是下面的表格。
 * 默认只显示前 3 节：卡片每高一格课格就少一格；展开只是多显示，数据一条不藏。
 */
@Composable
private fun TodayCard(
    state: ScheduleUiState,
    gridEditable: Boolean,
    onToggleGridEdit: () -> Unit
) {
    // 非本周只显示提示后提前返回，避免整段缩进一层。
    if (!state.isCurrentWeek) {
        CardShell(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            HintText("正在浏览第 ${state.week} 周（不是本周），点「回到本周」跳回。")
        }
        return
    }

    val date = LocalDate.ofEpochDay(state.todayEpochDay)
    val entries = state.todayEntries

    // 折叠态用 rememberSaveable：转屏、切深色模式后不丢。
    // 纯显示状态不进 ViewModel，进程被杀后重置为折叠也合理。
    var expanded by rememberSaveable { mutableStateOf(false) }

    val visible = if (expanded) entries else entries.take(TODAY_ROWS_COLLAPSED)
    val hiddenCount = entries.size - visible.size

    CardShell(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("今日课程")
                    // `ongoing` 由 ViewModel 每 30 秒重算，到点自动亮起，无需额外定时逻辑。
                    if (entries.any { it.ongoing }) {
                        Box(
                            Modifier
                                .padding(start = 8.dp)
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
                 * 文案写全「修改课表」：按钮长在卡片上但改的是表格，只写「修改」会被误读。
                 * 用描边/实心按钮而非纯文字小字，是为了看起来可点；
                 * 压到 32dp 高，避免把标题行撑高、挤占下方课格。
                 */
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HintText(if (entries.isEmpty()) "今天没课" else "共 ${entries.size} 节")
                    Spacer(Modifier.width(8.dp))
                    if (gridEditable) {
                        Button(
                            onClick = onToggleGridEdit,
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp)
                        ) { Text("完成", fontSize = 12.sp) }
                    } else {
                        OutlinedButton(
                            onClick = onToggleGridEdit,
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp)
                        ) { Text("修改课表", fontSize = 12.sp) }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // 日期条。用主题色加透明度而非写死浅蓝，深色模式下不刺眼。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${date.year}年${date.monthValue}月${date.dayOfMonth}日 " +
                        weekDayName(date.dayOfWeek.value),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            /**
             * 空态处理。列表为空时显示一句提示，而不是留一片空白——
             * 空白会让用户以为"是不是没加载出来"。
             */
            if (entries.isEmpty()) {
                EmptyHint("今天没有课，好好休息")
            } else {
                Spacer(Modifier.height(2.dp))
                // 不用 LazyColumn：一天最多十几节，全量渲染开销可忽略；
                // 且此处已在滚动容器内，Lazy 组件嵌套易出高度问题。
                visible.forEachIndexed { index, entry ->
                    TodayRow(entry = entry, isLast = index == visible.lastIndex)
                }
                if (hiddenCount > 0) {
                    TextButton(
                        onClick = { expanded = true },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
                    ) { Text("展开全部（还有 $hiddenCount 节）", fontSize = 12.sp) }
                } else if (expanded && entries.size > TODAY_ROWS_COLLAPSED) {
                    TextButton(
                        onClick = { expanded = false },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
                    ) { Text("收起", fontSize = 12.sp) }
                }
            }
        }
    }
}

/**
 * 今日卡片里的一行：时间 + 时间轴 + 课程信息。
 *
 * `IntrinsicSize.Min` 必须有：虚线要 `fillMaxHeight()`，而 Row 高度默认由
 * 子元素撑开，是循环依赖；先按最小固有高度确定行高，`fillMaxHeight()` 才生效，
 * 否则虚线高度为 0，什么都画不出来。
 *
 * @param isLast 最后一行不画虚线，避免线下端悬空。
 */
@Composable
private fun TodayRow(entry: TodayEntry, isLast: Boolean) {
    val accent = CoursePalette.colorOf(entry.course.colorIndex)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        // 时间列固定 46dp 让各行课程名左边缘对齐；时间字符串定长（补零），列宽不会跳动。
        Text(
            text = TimeUtils.minutesToText(entry.startMinute),
            modifier = Modifier.width(46.dp).padding(top = 6.dp),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )

        TimelineRail(
            dotColor = accent,
            drawLine = !isLast,
            modifier = Modifier.fillMaxHeight().width(16.dp)
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp, top = 4.dp, bottom = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.course.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    // 长课名省略而不是换行：换行会让每行高度不一致，
                    // 时间轴看起来就断断续续了。
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (entry.ongoing) {
                    Box(
                        Modifier
                            .padding(start = 6.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text("上课中", fontSize = 10.sp, color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
            Spacer(Modifier.height(3.dp))
            // 教室/教师可能为空：`ifBlank { null }` 让 `listOfNotNull` 丢掉空字段，
            // 避免「 · 张三 · …」这种以分隔符开头的结果。节次区间非空，结果不会是空串。
            val sections = if (entry.slot.startSection == entry.slot.endSection) {
                "第 ${entry.slot.startSection} 节"
            } else {
                "第 ${entry.slot.startSection}~${entry.slot.endSection} 节"
            }
            val sub = listOfNotNull(
                sections,
                entry.room.ifBlank { null },
                entry.course.teacher.ifBlank { null }
            ).joinToString(" · ")
            Text(
                text = sub,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 时间轴：圆点 +（可选）向下延伸的虚线。
 * 用 `Canvas` 是因为 `background` 只能填纯色，虚线必须在 `DrawScope` 里
 * 设 `PathEffect.dashPathEffect` 才画得出来。
 *
 * @param dotColor 取课程配色，与下面课格的颜色对得上。
 * @param drawLine 最后一行传 false，不画下半段。
 */
@Composable
private fun TimelineRail(dotColor: Color, drawLine: Boolean, modifier: Modifier = Modifier) {
    // 颜色跟主题走而非写死灰色，深浅两套主题都不刺眼。
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    Canvas(modifier) {
        val cx = size.width / 2
        val dotRadius = 3.5.dp.toPx()
        // 圆点下移到与课程名首行居中：行内约 4dp 上内边距 + 8sp 半行高
        val dotY = 10.dp.toPx()
        if (drawLine) {
            drawLine(
                color = lineColor,
                start = Offset(cx, dotY + dotRadius + 2.dp.toPx()),
                end = Offset(cx, size.height),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(
                    intervals = floatArrayOf(4.dp.toPx(), 4.dp.toPx())
                )
            )
        }
        drawCircle(color = dotColor, radius = dotRadius, center = Offset(cx, dotY))
    }
}

/**
 * 星期表头：周一～周日 + 各自日期，今天那一列高亮。
 * 与课格拆成两个组件，是因为表头不滚动、课格要滚动：表头放在可滚动区外，
 * 上下滑课时列标题始终可见。代价是两处列宽必须一致——
 * 都用 `LABEL_WIDTH` + `weight(1f)` 这套相同规则计算。
 */
@Composable
private fun WeekHeader(state: ScheduleUiState) {
    val primary = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().height(HEADER_HEIGHT)) {
        // 左侧空 Box 占位必须与节次列等宽，否则表头 7 列整体左移、与课格对不上。
        Box(Modifier.width(LABEL_WIDTH))
        for (day in 1..7) {
            val epochDay = state.weekStartEpochDay + (day - 1)
            val date = LocalDate.ofEpochDay(epochDay)
            // 比较的是 epochDay 而不是星期几，否则看第 8 周时所有周三都会被高亮。
            val isToday = epochDay == state.todayEpochDay
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    // 注意修饰符按顺序生效：布局类（weight/尺寸）在前、绘制类（background）在后，
                    // 背景才铺满确定尺寸后的整格；clip 则必须在 background 之前，否则背景溢出圆角。
                    .background(
                        if (isToday) primary.copy(alpha = 0.10f) else Color.Transparent
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // 低透明度主色做高亮，跟主题走，深色模式下不突兀。
                Text(
                    text = weekDayName(day),
                    fontSize = 11.sp,
                    color = if (isToday) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (isToday) FontWeight.Medium else FontWeight.Normal
                )
                Text(
                    text = "${date.monthValue}/${date.dayOfMonth}",
                    fontSize = 10.sp,
                    color = if (isToday) primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 7 天 × N 节的课格。不用 `LazyVerticalGrid` 的两个原因：
 * 1. 外层已有 `verticalScroll`，纵向嵌套滚动容器在运行期会抛异常
 *    （网格要求无限高度约束）；
 * 2. 格子高度是算出来的（`CELL_HEIGHT` × 节数），Lazy 的 item 高度由内容决定，
 *    无法与左侧节次列对齐。
 * 改用绝对定位：课程块用 `offset(y = CELL_HEIGHT × (startSection - 1))` 定位、
 * `height(CELL_HEIGHT × (endSection - startSection + 1))` 定高，滚动交给外层。
 *
 * @param editable false 时两层都「不挂」clickable 而非挂了再忽略：
 *   后者仍会出水波纹，用户会以为点了没反应。
 */
@Composable
private fun WeekGrid(
    state: ScheduleUiState,
    editable: Boolean,
    onAddCourse: (Int, Int) -> Unit,
    onEditCourse: (Long) -> Unit
) {
    val sections = state.gridSections
    val periodBySection = state.periods.associateBy { it.section }
    val outline = MaterialTheme.colorScheme.outline
    val primary = MaterialTheme.colorScheme.primary

    Row(Modifier.fillMaxWidth()) {
        // 左侧节次列
        Column(Modifier.width(LABEL_WIDTH)) {
            for (section in 1..sections) {
                Column(
                    // 每格固定 CELL_HEIGHT：与右侧课块共用同一常量，否则同一节次左右纵坐标错位。
                    modifier = Modifier.fillMaxWidth().height(CELL_HEIGHT),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "$section",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // gridSections 可能大于作息节数（课程占了后面的节次），
                    // 查不到作息就跳过时间，格子照画。
                    periodBySection[section]?.let { period ->
                        Text(
                            text = TimeUtils.minutesToText(period.startMinute),
                            fontSize = 9.sp,
                            // 时间比节次号淡一档，形成视觉层次。
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
                    .height(CELL_HEIGHT * sections)
                    .background(if (isToday) primary.copy(alpha = 0.06f) else Color.Transparent)
            ) {
                // 底层：网格线 + 点空白处新增课程（预填这一天）
                Column(
                    Modifier
                        // matchParentSize 不参与父容器尺寸计算，只跟随父 Box 已定的大小；
                        // fillMaxSize 会参与计算，与父 Box 的固定高度形成循环依赖，尺寸异常。
                        .matchParentSize()
                        .then(
                            if (editable) {
                                Modifier.clickable(onClick = { onAddCourse(day, 1) })
                            } else {
                                Modifier
                            }
                        )
                ) {
                    for (section in 1..sections) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(CELL_HEIGHT)
                                // 0.5dp 细线加低透明度：网格线只暗示格子存在，不抢眼。
                                .border(0.5.dp, outline.copy(alpha = 0.35f))
                        )
                    }
                }

                // 上层：课程块。同一 Box 内子元素按声明顺序绘制，后声明者在上层，
                // 命中测试也先命中，因此课程块盖住网格线并优先响应点击；
                // 顺序反了点课程块会变成加课，且上层透明看不出异常，是很难查的 bug。
                state.activeCourses
                    // activeCourses 已过滤单双周与起止周，这里只按星期分列。
                    .filter { it.slot.dayOfWeek == day }
                    .forEach { placed ->
                        // 节次夹到 [1, sections]，防止作息删减后课块画到格子外；
                        // endSection 下界用 startSection，保证结束不小于开始，高度不为负。
                        val startSection = placed.slot.startSection.coerceIn(1, sections)
                        val endSection = placed.slot.endSection.coerceIn(startSection, sections)
                        Box(
                            Modifier
                                // y = CELL_HEIGHT × (startSection - 1)：第 1 节偏移 0，第 3 节偏移 2 格。
                                // offset 是布局类修饰符，必须放在链最前；
                                // 放最后背景会按未偏移的位置绘制，色块错位。
                                .offset(y = CELL_HEIGHT * (startSection - 1))
                                // 高度 = 单格高 × (endSection - startSection + 1)：第 1-2 节占 2 格，要 +1。
                                .height(CELL_HEIGHT * (endSection - startSection + 1))
                                .fillMaxWidth()
                                // padding 在 background 前，缩小的是背景范围，相邻课块间留出缝隙。
                                .padding(1.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(CoursePalette.colorOf(placed.course.colorIndex))
                                .then(
                                    if (editable) {
                                        Modifier.clickable(onClick = { onEditCourse(placed.course.id) })
                                    } else {
                                        Modifier
                                    }
                                )
                                // 这层 padding 在 clickable 之后，只缩小文字范围，不缩小可点击区域。
                                .padding(3.dp)
                        ) {
                            Column {
                                Text(
                                    text = placed.course.name,
                                    fontSize = 10.sp,
                                    // 默认行高约为字号的 1.4 倍，压成 12sp 才能在 50dp 格子里多排几行。
                                    lineHeight = 12.sp,
                                    // 文字写死白色：CoursePalette 的 8 个色都是中深色调，白字对比度够；
                                    // 主题 onPrimary 在暗色模式下会变深，配深色块看不清。
                                    color = Color.White,
                                    // 必须给 maxLines，否则长课程名会把格子撑破。
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                                // isNotBlank 而非 isNotEmpty：只有一个空格的教室名也不画。
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
 * 星期数字 → 中文名。`(day - 1).coerceIn(0, 6)` 是下标保护：
 * 越界输入夹回合法范围而不是抛异常，宁可显示错也不崩。
 */
private fun weekDayName(day: Int): String =
    listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[(day - 1).coerceIn(0, 6)]
