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
 * 「课程 + 某一个具体的时间段」＝课表上的一个色块。
 *
 * ## 为什么需要这个类，而不是直接用 [CourseSlot]
 *
 * [CourseSlot] 里只有 `courseId`，没有课程名和颜色。周视图要画一个色块，
 * 需要"课程名 + 颜色索引 + 教室"（来自 [Course]）**加上**"星期几 + 起止节次"
 * （来自 [CourseSlot]）——两份数据缺一不可。
 *
 * 数据库查询确实返回了两份（[CourseWithSlots]），但那是"一门课挂着它所有时间段"
 * 的**嵌套**结构。而周视图的绘制逻辑是"遍历所有色块，按 `dayOfWeek` 分列"，
 * 需要的是**扁平**列表。这个类就是那个扁平化的结果，一层嵌套被拍平
 * （对应 [ScheduleViewModel] 里 `courseList.flatMap { ... }` 那一步）。
 *
 * ## 为什么是 `data class` 却**没有**参与状态相等性比较
 *
 * 它被放在 `ScheduleUiState.activeCourses` 里，而 Compose 靠比较新旧 State
 * 来判断要不要重绘。`data class` 的 `equals` 是逐字段比较，
 * [Course] 和 [CourseSlot] 也都是 `data class`，所以比较是**值语义**的——
 * 内容没变就不会触发重绘。这正是我们要的。
 *
 * @property course 课程本体，提供名字、颜色索引、默认地点。
 * @property slot 当一个具体的时间段，提供星期几、起止节次、单双周、地点覆盖。
 */
data class PlacedCourse(val course: Course, val slot: CourseSlot) {
    /**
     * 这个色块应该显示的上课地点。
     *
     * `ifBlank` 是 Kotlin 标准库给 `String` 加的扩展函数：
     * 字符串全空白（空串或只有空格）时返回 lambda 里的值。
     *
     * 这里的优先顺序是 "**时段自己的地点覆盖 → 课程默认地点**"。
     * 注意是 `ifBlank`（判断"是不是空白"）而不是 `ifEmpty`（判断"是不是空串"）——
     * 用户在教室输入框里误打一个空格时，`ifEmpty` 会当成"有值"，
     * 结果显示一个空格，看起来就像地点丢了。
     *
     * 用 `get() = ...` 声明成**计算属性**：每次访问都重新算，不占内存。
     * 注意它**没有使用任何可变状态**，所以不会带来"值变了但没通知 UI"的问题。
     */
    val room: String get() = slot.roomOverride.ifBlank { course.location }
}

/**
 * 今日课程卡片里的一行。
 *
 * 和 [PlacedCourse] 的区别：这里把"第几节"翻译成了**具体时间**
 * （`startMinute` / `endMinute`），因为今日卡片上要显示 "08:00 09:40"，
 * 而不是"第 1-2 节"。翻译需要查节次作息表，这个动作需要 `periods` 数据，
 * 所以只能在 [ScheduleViewModel] 拿到全部数据之后做——因此单独建一个类承载结果。
 *
 * `ongoing` 也是同理：判断"是不是正在上"需要当前时刻，
 * 而当前时刻是 [ScheduleViewModel] 里的一路数据流，UI 层拿不到。
 *
 * @property room 已经算好回退逻辑的地点（同 [PlacedCourse.room]）。
 *   这里直接存成 `String` 而不是像 [PlacedCourse] 那样用计算属性，
 *   是因为它已经加工过了（回退逻辑在 ViewModel 里算完），UI 直接显示即可。
 * @property startMinute 这节课开始的时间，距 0 点的分钟数。`480` = 08:00。
 * @property endMinute 这节课结束的时间，距 0 点的分钟数。
 * @property ongoing 此刻是否正在上这节课。由 [ScheduleViewModel] 每 30 秒重算一次。
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
 * 课表界面的**唯一状态**。界面画成什么样，完全由这一个对象决定。
 *
 * ## 为什么把所有字段塞进一个类（而不是散在 ViewModel 的多个字段里）
 *
 * 因为 Compose 判断"要不要重绘"靠的是**比较新旧状态对象是否相等**。
 * 如果状态散在多个 `MutableStateFlow` 里，界面就得订阅好几个流、
 * 还得处理"先后到达导致中间态闪烁"的问题。
 *
 * 合成一个 `data class` 之后：
 * - 界面只订阅一个 `StateFlow`，读到的是一个**自洽的快照**——
 *   不可能出现"周次已经变了但课程还是上一周的"这种撕裂状态；
 * - 相等性判断自动由 `data class` 生成的 `equals` 完成，不用手写；
 * - 加字段时只改这一个类，不用改界面的订阅代码。
 *
 * ## 每个字段都有默认值的作用
 *
 * 所有字段都给默认值，是为了能写出 `ScheduleUiState()` ——
 * 也就是"一个什么都不知道的初始状态"。它有两个用处：
 * 1. 作为 `stateIn` 的初始值（Flow 还没发出第一个值时的占位）；
 * 2. 作为 [loading] = true 时的值，界面看到 `loading` 为 true 就先转圈。
 *
 * @property loading 是否还在等第一批数据。为 true 时界面只显示一个转圈。
 *   变 false 的条件是**学期读到了**（见 `combine` 里那个 `sem == null` 判断），
 *   而不是"所有数据都到齐了"——没有学期就没有课表可看，
 *   而课程/作息表为空是完全合法的状态（新用户还没加课）。
 * @property semester 当前学期。可空，因为首次启动时 `ensureSemester()` 还没跑完。
 * @property periods 节次作息表，用来把"第 n 节"翻译成具体时间。
 *   界面左侧那一列和今日卡片的时间显示都要用它。
 * @property week **当前界面正在显示第几周**。注意它不一定等于 [currentWeek]——
 *   用户点"上一周/下一周"翻页时，[week] 变了但 [currentWeek] 没变。
 * @property currentWeek **真实的本周是第几周**（按开学日期或手动覆盖算出来）。
 *   和 [week] 分开存，界面才能实现"正在查看第 5 周（不是本周）"这种提示。
 * @property totalWeeks 学期总周数。翻页时的上界。
 * @property isCurrentWeek [week] == [currentWeek]。
 *   **这是一个冗余字段**——理论上界面可以自己算 `week == currentWeek`。
 *   留它的原因是：界面上有 3 处都要用这个判断（周次栏、"回到本周"按钮、
 *   今日卡片要不要显示），分散着算容易漏改；存成字段后只有一个来源。
 * @property weekStartEpochDay 当前显示的这一周的**周一**的 epochDay。
 *   表头的 7 个日期就是 `它 + 0..6`。由 [WeekCalculator.weekStartEpochDay] 算出来。
 * @property todayEpochDay 今天的 epochDay。
 *   界面用它来给"今天那一列"加底色，以及判断表头里哪一列是今天。
 * @property gridSections 周视图要画**多少行**（几节）。
 *   不是固定 12——取"作息表长度、课程实际用到的最大节次、10"三者的最大值。
 *   为什么要这样取：
 *   - 只用作息表长度 → 用户把作息删到 6 节，但课程里还存着第 8 节的记录（会画到格子外面）；
 *   - 只用课程最大节次 → 一门课都没有时高度是 0，课表塌成一条线；
 *   - 所以三个都取，再加个 10 的下限兜底，保证任何时候都是一张像样的表。
 * @property activeCourses **这一周要画的全部色块**。
 *   已经过滤掉"这周不上的"（单双周、起止周都不匹配的），
 *   也拍平成了 [PlacedCourse]。界面只需要按 `slot.dayOfWeek` 分列即可，
 *   不用再做任何业务判断——**这是 ViewModel 和 UI 的分工线**：
 *   所有"该不该显示"的逻辑在 ViewModel，UI 只负责"怎么画"。
 * @property todayEntries 今日课程卡片的列表，已按节次排好序。
 *   **只有 [isCurrentWeek] 为 true 时才有内容**，翻到别的周时是空列表
 *   （因为"今天"只对本周有意义）。
 * @property courseCount 课程总数。目前界面用不到，
 *   留着是为了 P2 做"空课表引导"（一门课都没有时显示"点格子开始添加"）。
 */
data class ScheduleUiState(
    val loading: Boolean = true,
    val semester: Semester? = null,
    val periods: List<PeriodTime> = emptyList(),
    /** 当前显示的周次 */
    val week: Int = 1,
    /** 真实当前周次（按开学日期算） */
    val currentWeek: Int = 1,
    val totalWeeks: Int = 20,
    val isCurrentWeek: Boolean = true,
    val weekStartEpochDay: Long = 0L,
    val todayEpochDay: Long = 0L,
    /** 周视图要画多少行（节次数），取值兼顾作息表长度和课程实际占用 */
    val gridSections: Int = 12,
    val activeCourses: List<PlacedCourse> = emptyList(),
    val todayEntries: List<TodayEntry> = emptyList(),
    val courseCount: Int = 0
)

/**
 * 课表页的 ViewModel。
 *
 * ## ViewModel 是什么、为什么必须有它
 *
 * 回顾一下第 1 课讲的：**界面随时可能被系统销毁重建**（转屏、内存吃紧、切后台太久）。
 * 普通字段一销毁就没了，用户翻到第 8 周、转个屏，回到第 1 周——体验很差。
 *
 * `ViewModel` 是 Android 专门为此提供的容器：
 * - 它**活在界面之外**，界面重建时它还在（所以状态不丢）；
 * - 界面的宿主（`Activity`）**真正结束时它才会被销毁**（所以不会内存泄漏）；
 * - 它自带一个协程作用域 [viewModelScope]，销毁时里面所有协程自动取消。
 *
 * ## 数据是"算出来的"而不是"塞进去的"
 *
 * 这个类里**没有任何一处手写"更新界面"的代码**。整个 [uiState] 是一条声明式的流水线：
 *
 * ```
 * 5 条上游数据流  ──combine──>  一份完整状态
 *   ├─ semester      学期（数据库）
 *   ├─ courses       课程（数据库）
 *   ├─ periods       作息（数据库）
 *   ├─ selectedWeek  用户翻了哪一周（内存）
 *   └─ nowMinute     当前时刻（每 30 秒更新）
 * ```
 *
 * 任何一条上游变了，`combine` 的 lambda 就整体重跑一遍，得到一份新的
 * [ScheduleUiState]。所以：
 * - 加了一门课 → `courses` 变 → 色块自己出现；
 * - 改了开学日期 → `semester` 变 → 周次自动重算；
 * - 翻到下一周 → `selectedWeek` 变 → 表头日期跟着变；
 * - 时间走到 10:00 → `nowMinute` 变 → "上课中"标签自己亮起来。
 *
 * **没有一处需要"通知界面刷新"**。这就是响应式数据流的核心价值。
 *
 * @param repository 数据来源。由 [com.courseschedule.ui.appViewModel] 注入，
 *   ViewModel 自己不知道 Repository 是怎么来的（这是依赖注入的意义）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleViewModel(private val repository: ScheduleRepository) : ViewModel() {

    /**
     * 用户手动翻到了哪一周。
     *
     * **`null` 是有语义的**，不是"没值"：
     * - `null` = "跟随本周"（默认状态，今天变成第 N 周时界面自动跟过去）；
     * - 非 null = 用户明确翻到了某一周，界面停在那儿不动。
     *
     * 这个设计让"回到本周"这个功能变成一行：把它设回 `null` 就行
     * （见 [goToCurrentWeek]），不需要额外记"用户是不是在跟随模式"。
     *
     * 用 `MutableStateFlow` 而不是普通 `var`，是因为它要作为一路数据流
     * 参与 [uiState] 的 combine——普通变量变了没人知道。
     * `MutableStateFlow` 就是 Kotlin 里的"可观察变量"。
     */
    private val selectedWeek = MutableStateFlow<Int?>(null)

    /**
     * 把 Repository 的学期流转成 `StateFlow`。
     *
     * ## `stateIn` 做的三件事
     *
     * Repository 返回的是 `Flow<Semester?>`（冷流），`stateIn` 把它变成
     * `StateFlow<Semester?>`（热流），带来三个好处：
     *
     * 1. **有"当前值"**：`.value` 可以同步读到，不用挂起等待。下面
     *    `previousWeek()` / `nextWeek()` 就靠这个。
     * 2. **需要初始值**：第三个参数 `null` 就是第一个值发出之前的占位值。
     * 3. **可以控制订阅生命周期**：就是第二个参数 [SharingStarted]。
     *
     * ## `SharingStarted.WhileSubscribed(5_000)` 为什么是 5000 而不是 0
     *
     * 含义是"没有订阅者之后，再等 5 秒才停止上游"。这个 5 秒是**刻意的缓冲**：
     *
     * - 转屏时界面会"销毁 → 重建"，中间有一段没有任何订阅者的瞬间。
     *   如果是 0，上游会被 Stop 再重启，数据库查询白跑一次；
     * - 5 秒足够覆盖转屏、Activity 重建这些场景，又短到不会在用户
     *   真的退到后台时白白跑着（那样会持续占着数据库连接）。
     *
     * 如果改成 `SharingStarted.Eagerly`，即使退到后台也会一直订阅，
     * 属于无谓的耗电。本项目没用过 Eagerly。
     */
    private val semester: StateFlow<Semester?> = repository.observeSemester()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 课程列表。
     *
     * ## `flatMapLatest` 在这里干什么
     *
     * 语义是"**上游一变，就取消旧的订阅，换一个新的数据源**"。
     *
     * 这里上游是 [semester]。当学期为 `null` 时没有 `id` 可用，
     * 查不了课程，所以发一个空列表；等学期读到了，才真正去订阅
     * `repository.observeCourses(sem.id)`。
     *
     * 如果不这么做，直接写 `repository.observeCourses(semester.value?.id ?: 0L)`，
     * 会有两个问题：
     * 1. 启动瞬间 `semester.value` 还是 null，那个查询就废了，
     *    而 `stateIn` 不会因为 `semester` 后续变化自动重查；
     * 2. 手写"学期变了要重新订阅"的代码，就退化成了命令式，
     *    而且容易漏掉取消旧订阅（泄漏）。
     *
     * `flatMapLatest` 一次解决这两件事。**它是"依赖外键去查子表"这个场景的标准写法**，
     * 项目里 [periods] 用的是同一个模式，以后加"待办跟着学期切"也是这么写。
     *
     * ## 为什么 `@OptIn(ExperimentalCoroutinesApi::class)`
     *
     * `flatMapLatest` 目前还标着 `@ExperimentalCoroutinesApi`，
     * 意思是"API 可能在未来版本变化"。要显式 opt-in 才能用，
     * 表示"我知道它可能会变，我自己承担"。
     * 这是 Kotlin 用编译期警告来标记 API 稳定性的一种机制——
     * 比 Java 的 `@Deprecated` 更细，比"什么都不做"更负责。
     */
    private val courses: StateFlow<List<CourseWithSlots>> = semester
        .flatMapLatest { sem ->
            if (sem == null) flowOf(emptyList()) else repository.observeCourses(sem.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 节次作息表。用 `flatMapLatest` 的原因和 [courses] 一样——按学期 id 查。
     *
     * `flowOf(emptyList())` 是"造一个只发一个空列表就结束的流"，
     * 用来在"还没有学期"时顶替真实的数据库查询。
     * 类型推断上它和 `repository.observePeriods(...)` 必须返回同一个类型
     * （都是 `Flow<List<PeriodTime>>`），否则 Kotlin 编译不过。
     */
    private val periods: StateFlow<List<PeriodTime>> = semester
        .flatMapLatest { sem ->
            if (sem == null) flowOf(emptyList()) else repository.observePeriods(sem.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 当前时刻（距 0 点的分钟数），每 30 秒更新一次。
     *
     * ## 为什么需要单独一路时间数据流
     *
     * 界面上有两处依赖"现在几点"：
     * 1. 今日卡片上的"**上课中**"标签——8:00 上课，到 8:00 那一瞬间标签该亮起来；
     * 2. 跨零点——用户晚上把 app 开着，第二天早上打开时"今天"应该是新的一天。
     *
     * 这两件事都不是"数据库变了"，如果只靠 `combine` 的数据库流，
     * 界面会一直停在旧时间上，标签永远不亮。
     *
     * 把它当成一路普通数据流接进 `combine`，就让"时间流逝"和"数据变化"
     * 用同一套机制驱动界面——不引入任何特殊的刷新逻辑。
     *
     * ## 为什么是 30 秒
     *
     * 一节课至少 40 分钟，"上课中"标签晚亮 30 秒完全看不出来。
     * 而如果改成每 1 秒刷新，每秒钟都要重跑一遍 `combine` 的 lambda
     * （里面有 flatMap、filter、sort，几十个对象），是真的在烧电。
     * 30 秒是"用户感知不到延迟"和"开销可以忽略"之间的平衡点。
     *
     * 初始值取构造函数里的当前时刻，所以**不需要等 30 秒才有第一个值**。
     */
    private val nowMinute = MutableStateFlow(minuteOfDay())

    /**
     * **界面的唯一状态**。5 路数据合成的结果，界面只读这一个。
     *
     * ## `combine` 的语义
     *
     * "这 5 条流里**任何一条**发出新值，就用全部 5 条**最新的值**重跑一遍 lambda"。
     * 注意是"最新的值"——不是"同时到达的值"。所以即使某条流很久没动，
     * 它的旧值也会被拿来参与计算，这正是我们要的（学期没变，但课程变了，
     * 重算时还是要用那个学期）。
     *
     * ## lambda 里 `sem == null` 那个提前返回
     *
     * `return@combine` 是 Kotlin 的**带标签返回**：从这里退出 lambda，
     * 返回值就是 `combine` 这一次的输出。标签名 `@combine` 就是被调函数的名字。
     *
     * 为什么这么写：`sem` 是可空的，但下面十几行都要用 `sem.startEpochDay` 之类，
     * 与其写一堆 `sem?.` 和 `?:`，不如在最前面一次性判掉——
     * 提前返回是处理可空值最清爽的写法，也是 Kotlin 官方推荐的。
     * 编译器从这里之后就把 `sem` 当成非空类型（这叫**智能转换**），
     * 所以后面直接用 `sem.totalWeeks` 不需要感叹号。
     *
     * ## 这段计算里最重要的三个判断
     *
     * 1. **`week = (selected ?: currentWeek)`**：用户翻过页就用他选的，
     *    否则跟随本周。再 `coerceIn` 一次是因为用户可能在翻到第 8 周之后
     *    去设置页把总周数改成 5 周——那时候 `selectedWeek` 还停在 8，会越界。
     * 2. **`active` 的 flatMap + filter**：flatMap 负责把"课程→它所有时间段"
     *    的嵌套结构拍平，filter 用 [WeekCalculator.isSlotActive] 筛掉这周不上的。
     *    这一步之后，界面拿到的就是"这一周真正要画的色块"。
     * 3. **`todayEntries` 的 `if (week == currentWeek)`**：
     *    "今日课程"只对本周有意义。用户翻到第 8 周时，
     *    卡片区应该显示提示而不是拿第 8 周的课冒充"今天"。
     *
     * ## 为什么 lambda 里要重新取 `LocalDate.now()`
     *
     * 而不是复用 `nowMinute` 那一路。因为 `nowMinute` 只有"时分"，
     * 跨零点时它的值会从 1439 跳回 0，但 `today` 必须跟着变成新的一天。
     * 直接调 `LocalDate.now()` 最准，而且这个 lambda 每 30 秒才跑一次，
     * 开销可以忽略。
     *
     * ## 最后那个 `stateIn` 的初始值是 `ScheduleUiState()`
     *
     * 也就是 `loading = true` 的那个默认状态。界面看到它就先画转圈，
     * 不会出现"空课表闪一下再填满"的观感问题。
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
     * 初始化。ViewModel 被创建时自动执行一次。
     *
     * ## 为什么用 `init` 块而不是构造函数体
     *
     * Kotlin 里两者几乎等价，`init` 块就是"构造时执行"的代码。
     * 分开写的好处是让"字段声明"和"启动副作用"在视觉上分层——
     * 上面的字段都是纯声明，下面的 `init` 才是"真的开始干活"。
     *
     * ## 两个 `launch` 为什么要分开写
     *
     * `viewModelScope.launch { }` 是**启动一个协程**。两个 `launch` 是
     * **并发执行**的，互不等待：
     *
     * - 第一个跑一次就结束；
     * - 第二个是死循环，会一直占着。
     *
     * 如果把它们写进同一个 `launch`，后面的 `while (true)` 永远不返回，
     * 但因为它前面那次 `ensureSemester()` 会挂起（等数据库），
     * 实际上也能跑起来——只是语义上纠缠在一起了，分开写意图更清楚：
     * **一个是"启动时做一次的事"，一个是"常驻的定时器"**。
     *
     * ## 关于那个 `while (true)`
     *
     * 这是本项目里最容易被误认为"会卡死界面"的一段代码。
     * 它不会。`delay(30_000)` 是一个**挂起点**：执行到这里时，
     * 当前协程会把线程让出去，界面照常响应，30 秒后系统再把线程还回来继续下一轮。
     * 线程从来不会被这个循环占住。
     *
     * 对比 C++ / Qt 的写法：要么起一个 `QThread` 专门跑这个循环，
     * 要么用 `QTimer` 每 30 秒触发一次槽函数。Kotlin 让你在一个函数里
     * 用**顺序写法**表达"等 30 秒再做下一轮"，不用拆成回调、不用管线程。
     *
     * ## 为什么放在 `viewModelScope` 而不是全局作用域
     *
     * 因为 `viewModelScope` 绑定了 ViewModel 的生命周期：
     * 界面真正结束、ViewModel 被销毁时，这个死循环**自动取消**。
     * 如果用了 `GlobalScope`（或者自己起线程），退出界面后循环还在跑，
     * 而且它持有 `nowMinute` 的引用——这就是内存泄漏。
     * 这是第 1 课"界面会被系统销毁"那条结论的直接后果。
     *
     * 另外注意第一句 `nowMinute.value = minuteOfDay()` 在 `delay` **之前**：
     * 进入循环先把时间对齐到当前时刻，而不是等 30 秒后才第一次更新。
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
     * 往前翻一周。
     *
     * `uiState.value.week` 是同步读取 [uiState] 的当前值——
     * 这正是用 `stateIn` 而不是裸 `Flow` 的理由：`Flow` 只能挂起收集，
     * 而 `StateFlow` 有 `.value` 可以随时同步读。
     *
     * `coerceAtLeast(1)` 保证不会翻到第 0 周。
     *
     * 注意翻页后 `selectedWeek` 从 `null` 变成了具体值，
     * 于是脱离"跟随本周"模式——这是符合直觉的：用户明确翻了页，
     * 就不该在他看的时候被自动跳回本周。
     * 想回去点 [goToCurrentWeek]。
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

    /**
     * 回到本周：把 [selectedWeek] 设回 `null`，恢复"跟随本周"模式。
     *
     * 注意**不需要**去算"本周是第几周"再赋进去。因为 `null` 的语义就是
     * "跟随"，[uiState] 里的 `selected ?: currentWeek` 会自动取到当前周。
     * 这个设计的好处是：用户在这里点了"回到本周"之后过了几天，
     * 界面会自动跟到新的那一周，不需要再做任何事。
     */
    fun goToCurrentWeek() {
        selectedWeek.value = null
    }

    /**
     * 取当前时刻，转成"距 0 点的分钟数"。
     *
     * 用 `LocalTime.now()` 而不是 `System.currentTimeMillis()` 取模，
     * 是为了避免时区和夏令时问题（理由同
     * [com.courseschedule.core.WeekCalculator] 的类注释）。
     *
     * 返回 0..1439 的值，和 [PeriodTime.startMinute] 等单位一致，
     * 所以可以直接用 `>=` / `<` 比较判断"是不是在上课"。
     *
     * 声明成 `private`，因为它是内部实现细节，界面上不需要知道几点几分。
     */
    private fun minuteOfDay(): Int {
        val now = LocalTime.now()
        return now.hour * 60 + now.minute
    }
}
