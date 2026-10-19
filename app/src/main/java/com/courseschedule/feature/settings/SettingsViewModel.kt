package com.courseschedule.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.courseschedule.core.TimeUtils
import com.courseschedule.core.WeekCalculator
import com.courseschedule.data.ScheduleRepository
import com.courseschedule.data.backup.BackupException
import com.courseschedule.data.backup.BackupManager
import com.courseschedule.data.entity.PeriodTime
import com.courseschedule.data.entity.Semester
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * **编辑态的**一节课作息。
 *
 * ## 为什么时间存成字符串而不是分钟数
 *
 * [com.courseschedule.data.entity.PeriodTime] 里存的是 `startMinute: Int`（`480`），
 * 但用户是**按文本编辑**的——他要能看到"08:00"、也要能手动敲"8:00"。
 *
 * 如果这里也存 `Int`，那么用户输入到一半（比如刚敲完"0"）就得往
 * `Int` 里塞，会出现"输入 8 的时候界面上先变成 0:00"这种问题。
 * 存字符串则用户敲什么就显示什么，非法输入到保存时才校验——
 * 这是**表单编辑的通用做法**：编辑期宽松，提交时严格。
 *
 * ## 为什么不含地点、教师之类的字段
 *
 * 因为这只是一个"节次 → 起止时间"的三元组。作息表里没有别的信息。
 * 保持最小。
 *
 * @param section 第几节。**注意它同时充当"身份标识"**——
 *   [SettingsViewModel.updatePeriod] 靠它来找到要改的那一条。
 *   所以保存时 [SettingsViewModel.save] 会**按列表下标重新编号**，
 *   保证 section 永远是连续的 1、2、3…（理由见
 *   [com.courseschedule.data.ScheduleRepository.savePeriods]）。
 * @param startText 开始时间的文本形式，如 `"08:00"`。
 * @param endText 结束时间的文本形式。
 */
data class PeriodDraft(val section: Int, val startText: String, val endText: String)

/**
 * 设置页的状态。
 *
 * ## 注意它和另外两个 UiState 的结构差异
 *
 * [com.courseschedule.feature.schedule.ScheduleUiState] 是"全部算出来"的
 * （驱动显示）。[CourseEditUiState] 是"用户正在编辑的表单"。
 * 这一个则是**混合的**：
 *
 * - `name` / `startDateText` / `totalWeeks` / `overrideEnabled` / `overrideWeek` /
 *   `periods` 是**用户正在编辑的表单内容**（和 CourseEditUiState 同类）；
 * - `courseCount` / `noteCount` / `todoCount` 是**从数据库实时读来的只读数据**
 *   （和 ScheduleUiState 同类），它们不参与编辑，只是显示。
 *
 * 混在一起是**有意的**：这样界面只订阅一个流。如果拆成两个状态，
 * 界面要订阅两次，而且"保存时"还要注意不要误把实时数据写回去。
 * 分类的边界是"这个字段会不会被用户改"——不会改的，从数据库来就行。
 *
 * @property loading 是否还在把数据库里的学期和作息读进表单。
 * @property semesterId 当前学期的 id。保存时要用它做外键。
 * @property name 学期名称（表单）。
 * @property startDateText 开学日期的**文本形式**（表单）。
 *   用户可能在输入框里敲到一半，所以这里不是 `Long`。
 * @property startEpochDay 上次**成功解析出来**的 epochDay。
 *   它有两个用处：给日期选择器当初始值、以及数据没变时不用重新解析。
 *   注意它和 [startDateText] 可能**不同步**——用户敲了非法日期时，
 *   `startDateText` 变了但这个值还停在旧的上。这是刻意的：
 *   界面上的日期选择器应该打开"最后一个有效日期"，而不是崩溃或跳到今天。
 * @property totalWeeks 学期总周数（表单）。
 * @property overrideEnabled "手动指定当前周"开关（表单）。
 * @property overrideWeek 手动指定的周次（表单）。**只有 [overrideEnabled] 为 true 时才有意义。**
 *   关闭开关时这个值仍然保留着，这样用户关掉再打开，之前的设置还在。
 * @property periods 节次作息列表（表单）。用 [PeriodDraft] 而不是 [PeriodTime]，
 *   理由见 [PeriodDraft]。
 * @property courseCount / noteCount / todoCount 数据库里的真实条数，只读。
 *   "数据"那一栏靠它们展示，顺带证明三张表都在正常工作。
 * @property message 要弹 Toast 的提示文案，`null` 表示没有。
 *
 *   ## 它和 [CourseEditUiState.error] 的区别
 *
 *   两者都是"给用户看的一句话"，但消费方式不同：
 *   - `error` 是**状态**：只要非空就一直显示在界面上（一行红字），
 *     用户改输入时会自动清掉。适合"表单校验失败"。
 *   - `message` 是**一次性事件**：弹出 Toast 之后必须立刻清掉，
 *     否则每次重组都会再弹一次。适合"保存成功"这种瞬时反馈。
 *
 *   所以 `message` 必须配套 [SettingsViewModel.consumeMessage] 使用。
 * @property backupBusy 是否正在导出/恢复备份。
 *   期间禁用两个按钮——重复点会同时跑两个读写同一文件的协程，
 *   第二个多半以"文件被占用"失败，用户看到的是莫名其妙的报错。
 */
data class SettingsUiState(
    val loading: Boolean = true,
    val semesterId: Long = 0L,
    val name: String = "",
    val startDateText: String = "",
    val startEpochDay: Long = 0L,
    val totalWeeks: Int = 20,
    val overrideEnabled: Boolean = false,
    val overrideWeek: Int = 1,
    val periods: List<PeriodDraft> = emptyList(),
    val courseCount: Int = 0,
    val noteCount: Int = 0,
    val todoCount: Int = 0,
    val message: String? = null,
    val backupBusy: Boolean = false
)

/**
 * 设置页的 ViewModel。管两块东西：**学期信息** 和 **节次作息**。
 *
 * ## 为什么这两个放一个页面
 *
 * 因为它们**耦合**：作息的周次上界来自 `totalWeeks`，
 * 用户改周数时作息列表的含义也跟着变。放一起能保证保存时是一致的一份数据。
 *
 * ## 与另外两个 ViewModel 的关键区别：这里用了四个独立的 `launch`
 *
 * 课表页用的是 `combine`（把多路数据合成一个状态），
 * 这里用的是"每条数据流开一个 `launch`，各自往状态里 `update` 一部分"。
 *
 * 两者都可以，选择依据是**"数据之间有没有耦合计算"**：
 * - 课表页的 `activeCourses` 需要同时用学期、课程、作息、周次才能算出来
 *   —— 必须有 `combine` 才能拿到"同一时刻的全部输入"；
 * - 设置页的 `courseCount` / `noteCount` / `todoCount` 是**三个互不相关的数字**，
 *   各自独立更新就行，凑在一起算纯属多余。
 *
 * 所以这里的写法更简单，而且每个 `launch` 的意图很直白：
 * "订阅这个计数，变了就更新状态里对应的那个字段"。
 *
 * @param repository 数据来源。
 * @param backupManager 备份读写。**不放进 Repository**，
 *   因为它要 `ContentResolver`，而 Repository 刻意不碰 Android API。
 */
class SettingsViewModel(
    private val repository: ScheduleRepository,
    private val backupManager: BackupManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /**
     * 初始化：读学期和作息填进表单，并订阅三个计数。
     *
     * ## 第一个 `launch` 为什么先调 `ensureSemester()`
     *
     * 因为用户可能**直接从课表页点"设置"进来**，而课表页的 ViewModel
     * 虽然也调了 `ensureSemester()`，但那是另一个协程、不保证已经执行完。
     *
     * 这里也调一次，靠函数开头的"已有学期就返回"保证幂等
     * （详见 [com.courseschedule.data.ScheduleRepository.ensureSemester]）。
     * **不假设别的页面已经初始化过**——这类隐形依赖是最容易出 bug 的地方。
     *
     * ## 后面三个 `launch` 为什么用 `collect` 而不是 `first`
     *
     * 因为这三个是**要持续看的**：
     * - 用户在设置页停留时，理论上另一个入口（比如 P2 的导入功能）
     *   可能加课，计数要跟着变；
     * - 更重要的是，"数据"那一栏是想给用户一个"我的数据在这里"的确认感，
     *   活着的数据比快照更可信。
     *
     * 而学期和作息用 `first()` 只读一次，因为**它们是表单**，
     * 持续跟随会覆盖用户正在输入的内容（理由同
     * [com.courseschedule.feature.courseedit.CourseEditViewModel] 的注释）。
     *
     * ## 为什么是四个独立的 launch 而不是一个
     *
     * 因为 `collect` 是**永不返回**的（Flow 没有尽头），
     * 写在一个 `launch` 里，后面那几句永远不会执行。
     * 必须各自开协程才能并发地收集。
     */
    init {
        viewModelScope.launch {
            repository.ensureSemester()
            val semester = repository.observeSemester().first() ?: return@launch
            val periods = repository.observePeriods(semester.id).first()
            _uiState.update {
                it.copy(
                    loading = false,
                    semesterId = semester.id,
                    name = semester.name,
                    startDateText = TimeUtils.epochDayToText(semester.startEpochDay),
                    startEpochDay = semester.startEpochDay,
                    totalWeeks = semester.totalWeeks,
                    /**
                     * `weekOverride != null` 就是"手动模式开着"。
                     * 数据库里只存一个可空的周次值，开关状态是从它推出来的——
                     * 不需要单独存一个布尔字段（那样两个字段可能不一致）。
                     * **能从已有数据推出来的东西就不要另存一份。**
                     */
                    overrideEnabled = semester.weekOverride != null,
                    /**
                     * 开关是关着的时候，`weekOverride` 是 null，
                     * 但我们还是要给用户一个合理的初始值——取"当前实际是第几周"。
                     * 这样用户打开开关时，步进器显示的就是本周，通常不用改。
                     *
                     * 这个 `?:` 是 Elvis 运算符：左边非空用左边，否则用右边。
                     */
                    overrideWeek = semester.weekOverride
                        ?: WeekCalculator.currentWeek(semester.startEpochDay, semester.totalWeeks),
                    /**
                     * 把数据库的分钟数转成文本填进表单。
                     * 这是**唯一**把 `PeriodTime` 转成 `PeriodDraft` 的地方，
                     * 反向转换在 [save] 里。两个方向各自集中一处。
                     */
                    periods = periods.map { period ->
                        PeriodDraft(
                            section = period.section,
                            startText = TimeUtils.minutesToText(period.startMinute),
                            endText = TimeUtils.minutesToText(period.endMinute)
                        )
                    }
                )
            }
        }
        viewModelScope.launch {
            repository.observeCourseCount().collect { count ->
                _uiState.update { it.copy(courseCount = count) }
            }
        }
        viewModelScope.launch {
            repository.observeNoteCount().collect { count ->
                _uiState.update { it.copy(noteCount = count) }
            }
        }
        viewModelScope.launch {
            repository.observeTodoCount().collect { count ->
                _uiState.update { it.copy(todoCount = count) }
            }
        }
    }

    /** 改学期名称。 */
    fun onNameChange(value: String) = _uiState.update { it.copy(name = value) }

    /**
     * 手动输入开学日期。
     *
     * 注意这里**只改文本，不改 [SettingsUiState.startEpochDay]**——
     * 因为用户可能正敲到一半（`"2026-1"`），这时候解析必然失败。
     * 真正解析发生在 [save]。
     *
     * 这个"文本和值是分开的两个字段"的设计是表单编辑的常见模式：
     * 文本永远跟着用户的输入走，值是最后一个有效解析结果。
     */
    fun onStartDateChange(value: String) = _uiState.update { it.copy(startDateText = value) }

    /**
     * 从日期选择器里选了一个日期。
     *
     * 和 [onStartDateChange] 不同，这里拿到的是**已经合法的 epochDay**，
     * 所以两个字段可以同时更新（文本和值保持同步）。
     */
    fun onStartDatePicked(epochDay: Long) = _uiState.update {
        it.copy(startDateText = TimeUtils.epochDayToText(epochDay), startEpochDay = epochDay)
    }

    /**
     * 改总周数。
     *
     * ## 那个 `coerceIn` 不能省
     *
     * 用户可能先把"手动当前周"设成第 18 周，再把总周数改成 10 周。
     * 这时候 `overrideWeek` 停在 18，比总周数还大——
     * 保存到数据库后，课表页会显示"第 18 周"这个不存在的周次，
     * 表头算出来的日期会飘到学期结束之后。
     *
     * 所以这里**顺手把 overrideWeek 夹到新范围内**。
     * 这类"一个字段变了要连带修正另一个字段"的联动，
     * 都放在 ViewModel 里做，不要交给界面——
     * 界面上永远只做展示，不做数据一致性维护。
     */
    fun onTotalWeeksChange(value: Int) = _uiState.update {
        it.copy(
            totalWeeks = value,
            overrideWeek = it.overrideWeek.coerceIn(1, value)
        )
    }

    /**
     * 开关"手动指定当前周"。
     *
     * **只改开关，不动 [SettingsUiState.overrideWeek]。**
     * 这样用户关掉再打开，之前设的周次还在，不用重新调。
     * 真正决定"数据库里存不存"的逻辑在 [save] 里
     * （关着就写 `null`）。
     */
    fun onOverrideEnabledChange(enabled: Boolean) = _uiState.update {
        it.copy(overrideEnabled = enabled)
    }

    /** 调手动指定的周次。范围限制由界面上那个 [NumberStepper] 的 `range` 保证。 */
    fun onOverrideWeekChange(value: Int) = _uiState.update { it.copy(overrideWeek = value) }

    /**
     * 改某一节的开始时间（手动输入文本）。
     *
     * 委托给 [updatePeriod]，把"找到第几节、改哪个字段"的逻辑收在一处。
     */
    fun onPeriodStartChange(section: Int, text: String) = updatePeriod(section) { it.copy(startText = text) }

    /** 改某一节的结束时间（手动输入文本）。 */
    fun onPeriodEndChange(section: Int, text: String) = updatePeriod(section) { it.copy(endText = text) }

    /**
     * 从时间选择器里选了一个时间。
     *
     * ## `isStart: Boolean` 这个参数的取舍
     *
     * 用它区分"改开始还是改结束"，比开两个方法
     * （`onPeriodStartPicked` / `onPeriodEndPicked`）省一个方法，
     * 但代价是布尔参数在调用处**看不出含义**——
     * 界面上是 `viewModel.onPeriodTimePicked(section, true, minutes)`，
     * 光看这行不知道 `true` 是什么意思。
     *
     * 这里可以接受，因为调用点只有一处。如果以后调用点多起来，
     * 应该换成两个方法或者定义一个 `enum class TimeField { START, END }`。
     *
     * Kotlin 有个更优雅的方案是定义**内联值类**（`@JvmInline value class`）
     * 或者用命名参数，但这里不值得。
     */
    fun onPeriodTimePicked(section: Int, isStart: Boolean, minutes: Int) = updatePeriod(section) {
        if (isStart) it.copy(startText = TimeUtils.minutesToText(minutes))
        else it.copy(endText = TimeUtils.minutesToText(minutes))
    }

    /**
     * 追加一节作息，**默认接着上一节的结束时间往后排**。
     *
     * ## 这段代码在算默认值，每一步都可能失败
     *
     * ```
     * val last = state.periods.lastOrNull()                                       // ①
     * val nextStart = (last?.endText?.let { TimeUtils.textToMinutes(it) } ?: 480) + 10   // ②
     * ```
     *
     * ① `lastOrNull()` 而不是 `last()`——列表为空时后者会抛异常。
     *    正常情况下列表不会为空（至少有一节），但不假设这一点。
     *
     * ② 这一串是本文件里最"Elvis 味"的一句，拆开读：
     *    - `last?.endText` —— 没有上一节就是 null；
     *    - `?.let { TimeUtils.textToMinutes(it) }` —— 解析成分钟数，
     *      解析失败返回 null；
     *    - `?: 480` —— 兜底成 08:00（如果没上一节、或者上一节的文本非法）；
     *    - `+ 10` —— 课间休息 10 分钟。
     *
     * 所以无论怎么样都能拿到一个合法的起始时间。**这种"一路可空、
     * 层层兜底"的写法在 Kotlin 里很常见，也基本只有在 Kotlin 里能写得这么短。**
     *
     * ## 新节次的编号是 `size + 1`
     *
     * 而不是 `last.section + 1`。两者在列表连续时等价，
     * 但如果列表因为某种原因不连续（理论上不会），用 `size + 1` 能保证
     * 编号不会跳号。保存时还会再按下标重编一次，所以这里只是为了让
     * 界面在保存前看起来正常。
     *
     * 默认时长 45 分钟（`nextStart + 45`），这是国内高校最常见的单节长度。
     */
    fun addPeriod() = _uiState.update { state ->
        val last = state.periods.lastOrNull()
        val nextStart = (last?.endText?.let { TimeUtils.textToMinutes(it) } ?: 480) + 10
        state.copy(
            periods = state.periods + PeriodDraft(
                section = state.periods.size + 1,
                startText = TimeUtils.minutesToText(nextStart),
                endText = TimeUtils.minutesToText(nextStart + 45)
            )
        )
    }

    /**
     * 删掉最后一节。
     *
     * ## 为什么不让用户删任意一节（比如"删掉第 3 节"）
     *
     * 因为节次是**连续编号**的：删掉第 3 节之后，第 4 节要变成第 3 节，
     * 而课表里所有"第 4 节"的课程都跟着变——这会让用户困惑
     * （"我只是想删一节时间，怎么我的课都往前挪了"）。
     *
     * 只允许从末尾删，语义最清楚：**作息表就是一个长度可调的序列**。
     * 想把第 3 节的时间改掉？直接改那两个时间，不用删。
     *
     * ## 为什么保留最后一节
     *
     * `if (state.periods.size <= 1) state` 表示"只剩一节时点删除没反应"。
     * 因为 0 节作息意味着课表左边那一列全空，是个没有意义的状态。
     * 与其让用户删到空再去处理，不如直接拦住。
     *
     * 注意这里返回的是**原对象本身**（`state` 而不是 `state.copy(...)`）。
     * `StateFlow` 在值相等时不会通知订阅者（它用 `equals` 比较），
     * 所以返回原对象等于"什么都不发生"，不会触发多余的重组。
     * 这是一个很实用的优化技巧。
     */
    fun removeLastPeriod() = _uiState.update { state ->
        if (state.periods.size <= 1) state
        else state.copy(periods = state.periods.dropLast(1))
    }

    /**
     * 保存学期信息和全部作息。
     *
     * ## 结构：先一串同步校验，再一段异步写库
     *
     * 校验有**四类**，而且顺序是精心安排的——**先便宜的、后贵的**：
     *
     * 1. 学期名非空（内存判断，最快）；
     * 2. 开学日期能解析（字符串解析）；
     * 3. 作息列表非空（内存判断）；
     * 4. **逐条**校验每节的时间（要做 2N 次解析）。
     *
     * 第 4 步放在最后，因为它是唯一一个"要遍历列表"的检查。
     * 前面几步能挡掉大部分错误输入，就不用白遍历一遍。
     *
     * ## 为什么逐条校验要用 `for` 而不是 `map` 加异常
     *
     * 因为要**在第一条错误处停下来并告诉用户是哪一节**。
     * 用 `map` 的话得在 lambda 里 `return@map`，做不到"整体提前退出"。
     * 而且这里同时在做"解析结果累加"（`parsed`），
     * 用 `for` 显式写反而最清楚。
     *
     * `parsed` 用 `mutableListOf` 是因为 `+=` 要改它。
     * 这是本文件里少数几个可变集合之一——**局部构建、立刻消费**，
     * 不逃逸到状态里，所以不违反"状态不可变"的规矩。
     *
     * ## `parsed += start to end` 里的 `to`
     *
     * `to` 是 Kotlin 的**中缀函数**，`a to b` 等价于 `Pair(a, b)`。
     * 这里 `parsed` 的类型是 `MutableList<Pair<Int, Int>>`，
     * 存的是"开始分钟数 to 结束分钟数"。
     *
     * ## 为什么要先把全部解析完再写库
     *
     * 因为写库是异步的、不可回滚。如果边解析边写，
     * 遇到第 5 节格式错误时前 4 节已经写进去了——
     * 用户看到的是"保存失败"但数据其实改了一半。
     * **先全部校验通过，再一次性提交**，这个顺序不能反。
     *
     * ## 两个写操作为什么不合并成一个事务
     *
     * `saveSemester` 和 `savePeriods` 各自是一个事务。理论上可以合成一个，
     * 但那样要让 Repository 开一个"同时写两张表"的方法。
     * 现在的做法有个小缺陷：如果 `saveSemester` 成功、`savePeriods` 失败，
     * 会出现"学期改了但作息没改"的中间状态。
     * 判断是**可以接受**——作息失败了用户会看到报错，重新点一次保存即可，
     * 而且两张表之间没有一致性约束（不像课程/时间段那样会丢数据）。
     * 为了这个概率极低的场景增加一层 API 不值得。
     */
    fun save() {
        val state = _uiState.value
        if (state.name.isBlank()) {
            showMessage("学期名称不能为空")
            return
        }
        /**
         * 解析开学日期。返回 `Long?`，null 表示格式不对。
         * 用 `val x = ...; if (x == null) { ...; return }` 的形式而不是
         * `?: return`，是因为这里要先弹提示再返回，两件事做不了。
         *
         * 注意这里得到的 `startEpochDay` **不是可空的**——
         * 因为 if 里已经 return 了，编译器从这个点之后就知道它是 `Long`
         * （这叫**智能转换**）。所以下面写 `Semester(startEpochDay = startEpochDay, ...)`
         * 不用加任何 `!!`。
         */
        val startEpochDay = TimeUtils.textToEpochDay(state.startDateText)
        if (startEpochDay == null) {
            showMessage("开学日期格式应为 2026-09-01")
            return
        }
        if (state.periods.isEmpty()) {
            showMessage("至少需要一节作息时间")
            return
        }

        val parsed = mutableListOf<Pair<Int, Int>>()
        for (period in state.periods) {
            val start = TimeUtils.textToMinutes(period.startText)
            val end = TimeUtils.textToMinutes(period.endText)
            if (start == null || end == null) {
                /**
                 * 错误提示里带上 `第 X 节`，让用户知道要去改哪一条。
                 * 只说"时间格式错误"的话，用户得一条条看过去——16 节的时候很痛苦。
                 */
                showMessage("第 ${period.section} 节的时间格式应为 08:00")
                return
            }
            /**
             * `end <= start` 用 `<=` 而不是 `<`：
             * **结束时间等于开始时间也是错的**（那一节长度为零）。
             * 这类边界条件很容易漏，测试时值得专门试一下"两个时间填成一样"。
             */
            if (end <= start) {
                showMessage("第 ${period.section} 节的结束时间要晚于开始时间")
                return
            }
            parsed += start to end
        }

        viewModelScope.launch {
            repository.saveSemester(
                Semester(
                    id = state.semesterId,
                    name = state.name.trim(),
                    startEpochDay = startEpochDay,
                    totalWeeks = state.totalWeeks,
                    /**
                     * ## 开关到数据库字段的映射
                     *
                     * 数据库里只有一个可空的 `weekOverride`，没有布尔开关。
                     * 所以这里要做映射：
                     * - 开关**开着** → 存周次（并且再夹一次范围，防止
                     *   `overrideWeek` 因为某种路径跑到范围外）；
                     * - 开关**关着** → 存 `null`（表示"按开学日期自动算"）。
                     *
                     * 注意那个 `coerceIn` 是**第二次**夹范围
                     * （[onTotalWeeksChange] 里已经夹过一次）。
                     * 保留它是因为这是**落库前的最后一道关口**——
                     * 数据层的正确性不该依赖界面事件按预期顺序触发。
                     */
                    weekOverride = if (state.overrideEnabled) {
                        state.overrideWeek.coerceIn(1, state.totalWeeks)
                    } else {
                        null
                    }
                )
            )
            repository.savePeriods(
                semesterId = state.semesterId,
                /**
                 * 把"开始分钟数 to 结束分钟数"的列表转成 [PeriodTime] 实体。
                 *
                 * 那个 `(start, end)` 是**解构声明**——因为 `Pair` 能被拆开。
                 * 等价于 C++17 的结构化绑定：`auto [start, end] = parsed[i];`
                 *
                 * `section = index + 1` 是按**列表下标重新编号**，
                 * 所以不管界面上原来的 section 是什么，落库时一定是
                 * 连续的 1、2、3…。这是删掉中间某一节后不会出现
                 * "1、2、4"这种跳号的保证。
                 */
                periods = parsed.mapIndexed { index, (start, end) ->
                    PeriodTime(
                        semesterId = state.semesterId,
                        section = index + 1,
                        startMinute = start,
                        endMinute = end
                    )
                }
            )
            /**
             * 成功了弹一句。用 `showMessage`（也就是设置 `message` 字段）
             * 而不是直接调 Toast，是因为 ViewModel 里不能碰 Android 的 UI 类
             * （那样就没法做单元测试了）。它只负责"说有这么一件事"，
             * 具体怎么弹由界面决定。
             */
            showMessage("已保存")
        }
    }

    /**
     * 导出备份到用户选定的 [uri]（由界面的文件选择器给）。
     *
     * ## 为什么这里是 `Uri` 而不是文件路径
     *
     * 走的是 SAF（系统文件选择器），用户可能存到本地、OneDrive、微信收藏。
     * 这些位置**没有真实文件路径可给**，只有一个 `Uri` 授权凭证。
     * 好处是 app 不需要任何存储权限。
     *
     * ## 出错为什么不抛异常
     *
     * ViewModel 里抛异常没人接，app 会直接崩。所以这里把每一种失败
     * 翻译成一句人话塞进 [SettingsUiState.message]，由界面弹出来。
     */
    fun exportBackup(uri: Uri) {
        if (_uiState.value.backupBusy) return
        _uiState.update { it.copy(backupBusy = true) }
        viewModelScope.launch {
            val text = try {
                val summary = backupManager.export(uri)
                "已导出 ${summary.courses} 门课、${summary.slots} 个时段"
            } catch (e: BackupException) {
                backupErrorText(e)
            } catch (e: Exception) {
                // 兜底：SQLite 异常之类的意外情况，不能让 app 崩掉
                "导出失败：${e.message ?: "未知错误"}"
            }
            _uiState.update { it.copy(backupBusy = false) }
            showMessage(text)
        }
    }

    /**
     * 从 [uri] 恢复备份。**会覆盖当前全部数据**，界面上必须已经二次确认过。
     *
     * 成功后的提示带上条数，是为了让用户确认"导进来的就是我那份"——
     * 如果他选错了文件（比如一份很旧的空备份），只说"成功"会让他以为数据都在。
     */
    fun importBackup(uri: Uri) {
        if (_uiState.value.backupBusy) return
        _uiState.update { it.copy(backupBusy = true) }
        viewModelScope.launch {
            val text = try {
                val summary = backupManager.restore(uri)
                "已恢复 ${summary.courses} 门课、${summary.slots} 个时段"
            } catch (e: BackupException) {
                backupErrorText(e)
            } catch (e: Exception) {
                "恢复失败：${e.message ?: "未知错误"}"
            }
            _uiState.update { it.copy(backupBusy = false) }
            showMessage(text)
        }
    }

    /**
     * 把失败原因翻译成用户能看懂的话。
     *
     * 分类的价值就在这里：如果只抛 `Exception("失败")`，
     * 用户不知道该换个文件、还是该升级 app、还是数据本身有问题。
     */
    private fun backupErrorText(e: BackupException): String = when (e.reason) {
        BackupException.Reason.IO -> "文件读写失败，请重试"
        BackupException.Reason.NOT_BACKUP -> "这不是课程表的备份文件"
        BackupException.Reason.TOO_NEW -> "备份来自更新版本的 app，请先升级再恢复"
        BackupException.Reason.TOO_LARGE -> "文件太大，不像是备份文件"
        BackupException.Reason.CORRUPTED -> "备份文件已损坏，读不出来"
        BackupException.Reason.INCONSISTENT -> "备份里的数据对不上（可能被改过），已取消导入"
    }

    /**
     * 清掉提示文案。**由界面在弹完 Toast 之后立刻调用**。
     *
     * ## 不调会怎样
     *
     * `message` 是状态的一部分。如果不清掉，它一直是非空，
     * 界面上的 `LaunchedEffect(state.message)` 虽然不会重复触发
     * （因为 key 没变），但用户下次改点别的、触发状态变化后
     * 如果恰好又让 `message` 从 null 变成同一个字符串，
     * 老的这句会再弹一次。更直接的坏处是"用户没做任何操作却又看到上一次的提示"。
     *
     * 所以约定是：**`message` 是"待消费的一次性事件"，谁消费谁负责清掉。**
     * 项目里 [CourseEditUiState.error] 用的是另一种策略（当普通状态一直显示），
     * 两种模式的区别见 [SettingsUiState.message] 的注释。
     */
    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    /**
     * 找到第 [section] 节、套用 [transform]、写回状态。
     *
     * ## 为什么是 `private` 而上面那三个 `onPeriodXxx` 是公开的
     *
     * 因为"改一节作息"这个动作有两个入口（手动输入 / 时间选择器），
     * 但底层逻辑只有一个。公开的三个方法是**给界面用的具体入口**，
     * 这个是**内部的公共实现**。
     *
     * 这种分层的好处是：以后要加"从剪贴板粘贴一整份作息"时，
     * 只要在里面也调用 `updatePeriod` 就行，不用重复找元素的逻辑。
     *
     * ## 定位用的是 `section` 而不是下标
     *
     * 和 [com.courseschedule.feature.courseedit.CourseEditViewModel.onSlotChange]
     * 用下标定位不同，这里用的 `section`。
     *
     * 为什么不一样：因为 `PeriodDraft` 自己的 `section` 字段就是它的身份
     * （它是从数据库读出来的、本身带编号），而 `SlotDraft` 没有 id
     * （它是新造的草稿）。**各自用现成的、最能唯一标识的元素**。
     *
     * ## `map` 的写法在这里为什么可行
     *
     * 因为 `section` 是唯一的，`if (it.section == section)` 只会命中一个。
     * 而 CourseEdit 那边如果用 `dayOfWeek` 之类去匹配就会命中多个——
     * 这就是两处写法不同的根本原因。
     */
    private fun updatePeriod(section: Int, transform: (PeriodDraft) -> PeriodDraft) =
        _uiState.update { state ->
            state.copy(
                periods = state.periods.map { if (it.section == section) transform(it) else it }
            )
        }

    /**
     * 设置提示文案。抽出来是为了让 [save] 里的错误分支都写成一行：
     * `showMessage("...")`，不用每次都 `_uiState.update { it.copy(message = ...) }`。
     *
     * 一个有意思的细节：`showMessage` 返回值是 `Unit`，
     * 但 [save] 里写的是 `showMessage("..."); return`（两句），
     * 而不是 `return showMessage("...")`。因为后者在 Kotlin 里
     * 意味着"返回这个 Unit"，语义上会让人以为 `save()` 有返回值。
     * 两句分开写更清楚。
     */
    private fun showMessage(text: String) = _uiState.update { it.copy(message = text) }
}
