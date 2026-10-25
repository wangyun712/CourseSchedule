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
 * 编辑态的一节作息。时间存文本而非分钟数：用户按文本编辑，敲到一半的 "8:0" 塞不进 Int；
 * 编辑期宽松、保存时校验，是表单编辑的通用做法。
 *
 * @property section 第几节，同时充当身份标识：updatePeriod 靠它定位；保存时按下标重编保证连续。
 */
data class PeriodDraft(val section: Int, val startText: String, val endText: String)

/**
 * 设置页状态。表单字段与三个只读计数（courseCount/noteCount/todoCount，数据库实时值）
 * 混在同一个 state 是有意的：界面只订阅一个流；分类边界是"该字段会不会被用户改"。
 *
 * @property semesterId 保存时做外键；恢复备份后会被 AUTOINCREMENT 重编，必须重读，见 loadForm。
 * @property startDateText 开学日期文本（表单），用户可能敲到一半，所以不是 Long。
 * @property startEpochDay 上次成功解析的日期。与 startDateText 故意不同步：编辑期宽松、保存时严格；
 *   日期选择器靠它定位到最后一个有效日期，而不是崩溃或跳到今天。
 * @property overrideWeek 关闭开关时仍保留值，重开后不用重调；仅 overrideEnabled 为 true 时有意义。
 * @property periods 节次作息（表单），用 PeriodDraft 而非 PeriodTime：编辑期存文本。
 * @property message 一次性事件：弹完 Toast 必须调 consumeMessage 清掉，否则同文案不再弹或重复弹；
 *   加课页的 error 是持续状态、输入即清，两者消费方式不同。
 * @property backupBusy 备份读写中，界面据此禁用两个按钮防重复点击。
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
 * 设置页 ViewModel。学期信息与节次作息放一页：作息的周次上界来自 totalWeeks，
 * 两者耦合，放一起才能保证保存的是一致的一份数据。三个计数互不相关，
 * 各自 launch 独立 collect 更新，不需要 combine。
 *
 * backupManager 不放进 Repository：它要 ContentResolver，而 Repository 刻意不碰 Android API。
 */
class SettingsViewModel(
    private val repository: ScheduleRepository,
    private val backupManager: BackupManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /**
     * 表单走 loadForm 只读一次：持续跟随会覆盖用户正在输入的内容。
     * 例外是恢复备份——整库替换后必须重调（见 loadForm）。
     * 三个计数则要持续 collect："数据"栏要的是活着的数据而非快照。
     * 每个 collect 单独开协程：collect 永不返回，写在同一个 launch 里后面的不会执行。
     */
    init {
        viewModelScope.launch { loadForm() }
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

    /**
     * 把数据库里的学期和作息读进表单。恢复备份成功后必须重调：
     * 整库替换后学期 id 被 AUTOINCREMENT 重编，残留的旧 semesterId 会让
     * 保存时 @Update 匹配不到行（静默无效）、插作息触发外键约束而崩溃。
     *
     * 开头先调 ensureSemester：不假设别的页面已初始化过，该函数幂等。
     */
    private suspend fun loadForm() {
        repository.ensureSemester()
        val semester = repository.observeSemester().first() ?: return
        val periods = repository.observePeriods(semester.id).first()
        _uiState.update {
            it.copy(
                loading = false,
                semesterId = semester.id,
                name = semester.name,
                startDateText = TimeUtils.epochDayToText(semester.startEpochDay),
                startEpochDay = semester.startEpochDay,
                totalWeeks = semester.totalWeeks,
                // weekOverride 非 null 即开关状态：能从已有数据推导的不另存布尔字段，避免两处不一致。
                overrideEnabled = semester.weekOverride != null,
                // 开关关闭时给"当前实际周次"兜底：用户打开开关，步进器直接显示本周，通常不用改。
                overrideWeek = semester.weekOverride
                    ?: WeekCalculator.currentWeek(semester.startEpochDay, semester.totalWeeks),
                // 分钟数转文本填表单，反向转换在 save，两个方向各自集中一处。
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

    /** 改学期名称。 */
    fun onNameChange(value: String) = _uiState.update { it.copy(name = value) }

    /**
     * 只改文本不动 startEpochDay：用户可能敲到一半（"2026-1"），此时解析必失败。
     * 文本永远跟输入走，值是最后一个有效解析结果，真正解析在 save。
     */
    fun onStartDateChange(value: String) = _uiState.update { it.copy(startDateText = value) }

    /** 这里拿到的是已合法的 epochDay，文本和值可以同步更新。 */
    fun onStartDatePicked(epochDay: Long) = _uiState.update {
        it.copy(startDateText = TimeUtils.epochDayToText(epochDay), startEpochDay = epochDay)
    }

    /**
     * coerceIn 不能省：先设第 18 周再改 10 周的话 overrideWeek 越界，
     * 课表会显示不存在的周次、表头日期飘到学期外。
     * 字段间联动修正放 ViewModel，界面只做展示不做数据一致性维护。
     */
    fun onTotalWeeksChange(value: Int) = _uiState.update {
        it.copy(
            totalWeeks = value,
            overrideWeek = it.overrideWeek.coerceIn(1, value)
        )
    }

    /** 只改开关不动 overrideWeek：关掉再打开之前设的周次还在；落库映射（关着写 null）在 save。 */
    fun onOverrideEnabledChange(enabled: Boolean) = _uiState.update {
        it.copy(overrideEnabled = enabled)
    }

    /** 调手动指定的周次。范围限制由界面上那个 [NumberStepper] 的 `range` 保证。 */
    fun onOverrideWeekChange(value: Int) = _uiState.update { it.copy(overrideWeek = value) }

    /** 委托给 updatePeriod，把"找到第几节、改哪个字段"的逻辑收在一处。 */
    fun onPeriodStartChange(section: Int, text: String) = updatePeriod(section) { it.copy(startText = text) }

    /** 改某一节的结束时间（手动输入文本）。 */
    fun onPeriodEndChange(section: Int, text: String) = updatePeriod(section) { it.copy(endText = text) }

    /** isStart 区分改开始/结束：调用点只有一处，布尔参数可接受；调用点变多再拆两个方法。 */
    fun onPeriodTimePicked(section: Int, isStart: Boolean, minutes: Int) = updatePeriod(section) {
        if (isStart) it.copy(startText = TimeUtils.minutesToText(minutes))
        else it.copy(endText = TimeUtils.minutesToText(minutes))
    }

    /**
     * 追加一节：开始时间接上一节结束 +10 分钟课间，默认时长 45 分钟；
     * 无上一节或其文本非法时兜底 08:00，怎么都能得到合法起始时间。
     * 编号用 size + 1 而非 last.section + 1：列表异常不连续时也不跳号（保存时还会按下标重编）。
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
     * 只删末节：节次连续编号，删中间会让后面的课程全部前移一号，用户难以理解；
     * 保留最后一节：0 节作息让课表左列全空，没有意义，直接拦住。
     * 只剩一节时返回原对象：StateFlow 用 equals 判等，值没变就不触发多余重组。
     */
    fun removeLastPeriod() = _uiState.update { state ->
        if (state.periods.size <= 1) state
        else state.copy(periods = state.periods.dropLast(1))
    }

    /**
     * 校验顺序先便宜后贵：名称非空（内存）→ 日期可解析 → 作息非空 → 逐条解析时间（最贵），
     * 逐条用 for 以便在第一条错误处停下并报出节次。
     * 先全部解析完再写库：写库异步且不可回滚，边解析边写会留下"改了一半"的数据。
     * 两个写操作不合并事务：作息失败重存一次即可，两表间无一致性约束，取舍已确认可接受。
     */
    fun save() {
        val state = _uiState.value
        if (state.name.isBlank()) {
            showMessage("学期名称不能为空")
            return
        }
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
                // 提示带节次，用户直接知道改哪一条，不用逐条排查。
                showMessage("第 ${period.section} 节的时间格式应为 08:00")
                return
            }
            // 用 <=：结束等于开始（零长度）也是错。
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
                     * 数据库只存可空 weekOverride：开关开着存周次，关着存 null（按开学日期自动算）。
                     * coerceIn 是第二次夹（onTotalWeeksChange 已夹过）：落库前最后一道关口，
                     * 数据层的正确性不依赖界面事件按预期顺序触发。
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
                 * 按列表下标重编 section：不管界面原编号如何，落库一定是连续的 1、2、3…，
                 * 删过中间的节也不会跳号。
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
            // 经 message 弹提示而非直接调 Toast：ViewModel 不碰 Android UI 类才能做单元测试。
            showMessage("已保存")
        }
    }

    /**
     * 导出备份到用户选定的 uri。走 SAF 只有 Uri 没有真实路径（用户可能存到云盘等位置），
     * app 因此不需要存储权限。失败不抛异常：ViewModel 里抛了没人接会崩，
     * 统一翻译成人话放进 message 由界面弹出。
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
     * 恢复备份，覆盖当前全部数据，界面必须已二次确认。
     * 成功提示带条数：用户选错文件（如旧的空备份）时只说"成功"会让他以为数据都在。
     */
    fun importBackup(uri: Uri) {
        if (_uiState.value.backupBusy) return
        _uiState.update { it.copy(backupBusy = true) }
        viewModelScope.launch {
            val text = try {
                val summary = backupManager.restore(uri)
                // 恢复是整库替换：学期 id 被重新编号了，表单必须重读，
                // 否则残留的旧 semesterId 会让下一次「保存」触发外键冲突而崩溃
                loadForm()
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

    /** 把失败原因翻译成人话：用户才知道该换文件、升级 app，还是数据本身有问题。 */
    private fun backupErrorText(e: BackupException): String = when (e.reason) {
        BackupException.Reason.IO -> "文件读写失败，请重试"
        BackupException.Reason.NOT_BACKUP -> "这不是课程表的备份文件"
        BackupException.Reason.TOO_NEW -> "备份来自更新版本的 app，请先升级再恢复"
        BackupException.Reason.TOO_LARGE -> "文件太大，不像是备份文件"
        BackupException.Reason.CORRUPTED -> "备份文件已损坏，读不出来"
        BackupException.Reason.INCONSISTENT -> "备份里的数据对不上（可能被改过），已取消导入"
    }

    /**
     * 界面弹完 Toast 后立刻调用。message 是待消费的一次性事件，谁消费谁清掉；
     * 不清则 key 停在旧文案，下一次同一提示不再弹（error 是持续状态，消费方式不同）。
     */
    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    /**
     * 按 section 定位（PeriodDraft 自带唯一编号）、套用 transform 后写回。
     * CourseEdit 用下标定位是因为 SlotDraft 是无 id 的新草稿——各自用最能唯一标识的元素。
     */
    private fun updatePeriod(section: Int, transform: (PeriodDraft) -> PeriodDraft) =
        _uiState.update { state ->
            state.copy(
                periods = state.periods.map { if (it.section == section) transform(it) else it }
            )
        }

    /** 抽出来让 save 的错误分支都能写成一行 showMessage("...")。 */
    private fun showMessage(text: String) = _uiState.update { it.copy(message = text) }
}
