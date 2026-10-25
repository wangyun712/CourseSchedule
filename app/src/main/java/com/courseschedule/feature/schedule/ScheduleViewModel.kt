package com.courseschedule.feature.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.courseschedule.core.WeekCalculator
import com.courseschedule.data.ScheduleRepository
import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.CourseSlot
import com.courseschedule.data.entity.CourseWithSlots
import com.courseschedule.data.entity.PeriodTime
import com.courseschedule.data.entity.Semester
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/**
 * 课表上的一个色块：`CourseSlot` 只有 courseId，周视图还需要课程名与颜色，
 * 故把 `CourseWithSlots` 的嵌套结构拍平成绘制所需的扁平列表。
 * data class 的值语义 equals 正好让 Compose 在内容没变时跳过重绘。
 */
data class PlacedCourse(val course: Course, val slot: CourseSlot) {
    /** 上课地点：时段自己的地点覆盖优先，为空白（含纯空格，故用 ifBlank 而非 ifEmpty）时回退课程默认地点。 */
    val room: String get() = slot.roomOverride.ifBlank { course.location }
}

/**
 * 今日课程卡片里的一行。与 [PlacedCourse] 的区别：把"第几节"翻译成了具体时间。
 * 翻译要查节次作息表，"是否上课中"依赖 ViewModel 的当前时刻流，
 * 都得在数据齐全后由 ViewModel 完成，故单独建类，UI 直接显示。
 *
 * @property startMinute 开始时间（距 0 点的分钟数，480 即 08:00）。
 * @property ongoing 此刻是否正在上这节课（随 nowMinute 每 30 秒重算）。
 */
data class TodayEntry(
    val course: Course,
    val slot: CourseSlot,
    val room: String,
    val startMinute: Int,
    val endMinute: Int,
    val ongoing: Boolean
)

/**
 * 课表界面的唯一状态：由 [ScheduleViewModel] 的 5 路流 combine 而成，
 * 任何一路变化都用全部最新值整体重算，界面只订阅这一个 StateFlow，
 * 不会读到"周次已变但课程还是上一周的"这类撕裂快照。
 * 所有字段都有默认值——`ScheduleUiState()` 即 stateIn 的 loading 占位初值。
 *
 * @property loading 等"学期读到"才变 false（没有学期就没有课表；课程/作息为空是合法状态）。
 * @property week 界面正在显示的周次，用户翻页时它变而 currentWeek 不变。
 * @property currentWeek 真实本周（按开学日期/手动覆盖算出）；与 week 分开存才能提示"正在查看非本周"。
 * @property isCurrentWeek 刻意的冗余字段（= week == currentWeek）：界面 3 处要用，收成一个字段只留单一来源。
 * @property weekStartEpochDay 本周周一的 epochDay，表头 7 个日期即它 + 0..6。
 * @property gridSections 周视图行数 = max(作息表长度, 课程最大节次, 下限 10)，兼顾删减作息与无课两种情况。
 * @property activeCourses 本周要画的全部色块，已过滤单双周/起止周并拍平；"该不该显示"都在 ViewModel，UI 只管怎么画。
 * @property todayEntries 只在 isCurrentWeek 为 true 时有内容——"今天"只对本周有意义。
 */
data class ScheduleUiState(
    val loading: Boolean = true,
    val semester: Semester? = null,
    val periods: List<PeriodTime> = emptyList(),
    val week: Int = 1,
    val currentWeek: Int = 1,
    val totalWeeks: Int = 20,
    val isCurrentWeek: Boolean = true,
    val weekStartEpochDay: Long = 0L,
    val todayEpochDay: Long = 0L,
    val gridSections: Int = 12,
    val activeCourses: List<PlacedCourse> = emptyList(),
    val todayEntries: List<TodayEntry> = emptyList(),
    val courseCount: Int = 0
)

/**
 * 课表页 ViewModel。界面状态是声明式流水线，没有任何手写"通知界面刷新"的代码：
 * 5 路上游流（semester 学期 / courses 课程 / periods 作息 / selectedWeek 翻页 / nowMinute 时刻）
 * 经 combine 合成唯一状态，任何一路变化都用全部最新值整体重算——
 * 加课则色块出现、改开学日期则周次重算、翻页则表头日期变、时间到点则"上课中"标签亮。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleViewModel(private val repository: ScheduleRepository) : ViewModel() {

    /**
     * 用户手动翻到的周次。`null` 有语义：跟随本周（今天进入新一周时界面自动跟过去）；
     * 非 null 则停在用户选的周。因此"回到本周"只需置回 null（见 [goToCurrentWeek]）。
     */
    private val selectedWeek = MutableStateFlow<Int?>(null)

    /**
     * 学期流。`SharingStarted.WhileSubscribed(5_000)` 的 5 秒是转屏缓冲：
     * 转屏重建期间没有订阅者也先不停上游，避免数据库查询白跑；真正退到后台才停止。
     */
    private val semester: StateFlow<Semester?> = repository.observeSemester()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 课程列表。学期为 null 时发空列表顶替查询；学期一变就取消旧订阅、重订对应学期的数据。
     * 这是"依赖外键查子表"的标准写法，[periods] 用的是同一模式。
     * flatMapLatest 仍是实验性 API，故类上需 @OptIn(ExperimentalCoroutinesApi::class)。
     */
    private val courses: StateFlow<List<CourseWithSlots>> = semester
        .flatMapLatest { sem ->
            if (sem == null) flowOf(emptyList()) else repository.observeCourses(sem.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 节次作息表，flatMapLatest 用法同 [courses]：学期为 null 用空列表顶替，学期变了取消重订。 */
    private val periods: StateFlow<List<PeriodTime>> = semester
        .flatMapLatest { sem ->
            if (sem == null) flowOf(emptyList()) else repository.observePeriods(sem.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 当前时刻（距 0 点的分钟数），每 30 秒更新一次。
     * 专门驱动"上课中"标签与跨零点后的"今天"重算——这两件事都不依赖数据库变化。
     * 30 秒是感知不到延迟与重算开销可忽略之间的折中；初始值取构造时刻，不用等第一轮。
     */
    private val nowMinute = MutableStateFlow(minuteOfDay())

    /**
     * 界面的唯一状态：5 路流 combine 而成，任何一路发出新值就用全部最新值整体重算。
     * `sem == null` 时 `return@combine` 提前返回 loading 占位；week 取 `selected ?: currentWeek`
     * 再 coerceIn 总周数——防止用户翻到第 8 周后把总周数改小时越界。
     * todayEntries 只在 week == currentWeek 时填充；lambda 里重新取 `LocalDate.now()`
     * 是因为 nowMinute 只有时分，跨零点后"今天"必须换成新的一天。
     */
    val uiState: StateFlow<ScheduleUiState> = combine(
        semester, courses, periods, selectedWeek, nowMinute
    ) { sem, courseList, periodList, selected, now ->
        if (sem == null) return@combine ScheduleUiState(loading = true)

        val today = LocalDate.now().toEpochDay()
        val currentWeek = WeekCalculator.currentWeek(
            startEpochDay = sem.startEpochDay,
            totalWeeks = sem.totalWeeks,
            weekOverride = sem.weekOverride,
            todayEpochDay = today
        )
        val week = (selected ?: currentWeek).coerceIn(1, sem.totalWeeks)

        val active = courseList.flatMap { item ->
            item.slots
                .filter { WeekCalculator.isSlotActive(it, week) }
                .map { PlacedCourse(item.course, it) }
        }

        val periodBySection = periodList.associateBy { it.section }
        val todayDayOfWeek = WeekCalculator.dayOfWeek(today)
        val todayEntries = if (week == currentWeek) {
            active
                .filter { it.slot.dayOfWeek == todayDayOfWeek }
                .sortedBy { it.slot.startSection }
                .map { placed ->
                    val start = periodBySection[placed.slot.startSection]?.startMinute ?: 0
                    val end = periodBySection[placed.slot.endSection]?.endMinute ?: 0
                    TodayEntry(
                        course = placed.course,
                        slot = placed.slot,
                        room = placed.room,
                        startMinute = start,
                        endMinute = end,
                        ongoing = now >= start && now < end
                    )
                }
        } else {
            emptyList()
        }

        ScheduleUiState(
            loading = false,
            semester = sem,
            periods = periodList,
            week = week,
            currentWeek = currentWeek,
            totalWeeks = sem.totalWeeks,
            isCurrentWeek = week == currentWeek,
            weekStartEpochDay = WeekCalculator.weekStartEpochDay(sem.startEpochDay, week),
            todayEpochDay = today,
            gridSections = maxOf(
                periodList.size,
                courseList.flatMap { it.slots }.maxOfOrNull { it.endSection } ?: 0,
                10
            ),
            activeCourses = active,
            todayEntries = todayEntries,
            courseCount = courseList.size
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScheduleUiState())

    /**
     * 初始化：启动"建默认学期"的一次性任务和常驻定时器，两个 launch 并发互不等待，
     * 分别是"启动时做一次的事"和"常驻的定时器"。
     * while (true) 不会卡线程——delay 是挂起点，等待期间让出线程；
     * 循环跑在 viewModelScope 里，ViewModel 销毁时自动取消，不会泄漏。
     */
    init {
        // 首次启动建默认学期，保证打开就有东西可看
        viewModelScope.launch { repository.ensureSemester() }
        viewModelScope.launch {
            while (true) {
                nowMinute.value = minuteOfDay()
                delay(30_000)
            }
        }
    }

    /**
     * 往前翻一周。`uiState.value` 可同步读当前值，这是 StateFlow 相对冷流的便利。
     * 翻页即脱离"跟随本周"模式，想回去点 [goToCurrentWeek]。
     */
    fun previousWeek() {
        selectedWeek.value = (uiState.value.week - 1).coerceAtLeast(1)
    }

    /**
     * 往后翻一周。上界是学期总周数，用 `coerceAtMost` 夹住，
     * 所以翻到最后一页时按钮点了没反应（不会越界到不存在的第 21 周）。
     */
    fun nextWeek() {
        selectedWeek.value = (uiState.value.week + 1).coerceAtMost(uiState.value.totalWeeks)
    }

    /** 回到本周：selectedWeek 置回 null 即恢复"跟随本周"，之后进入新的一周也会自动跟随。 */
    fun goToCurrentWeek() {
        selectedWeek.value = null
    }

    /** 当前时刻转成距 0 点的分钟数（0..1439），与 `PeriodTime.startMinute` 等单位一致，可直接比较判断上课中。 */
    private fun minuteOfDay(): Int {
        val now = LocalTime.now()
        return now.hour * 60 + now.minute
    }
}
