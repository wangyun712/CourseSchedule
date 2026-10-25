package com.courseschedule.feature.courseedit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.courseschedule.data.ScheduleRepository
import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.CourseSlot
import com.courseschedule.data.entity.WeekType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 节次上限，编辑页步进器与落库前校验共用。
 * 改这个值要同步考虑设置页作息表的上限，否则会出现"作息有第 16 节但课程占不到"。
 */
const val MAX_SECTION = 15

/**
 * 编辑态的时间段草稿。与 `CourseSlot` 分离：草稿没有 id/courseId（保存时才分配），
 * "表单里的样子"和"数据库里的样子"是两个东西，转换集中在保存那一刻（见 `save`）。
 * 目前两边只差 id，是有意不做多余抽象；所有字段都有默认值，`SlotDraft()` 即一份空白草稿。
 *
 * @property startSection 不保证 <= endSection，保存时用 minOf/maxOf 统一纠正，编辑过程不打断用户。
 * @property endWeek 默认 20，但新建时段时会跟随学期总周数（见 `addSlot`）。
 * @property roomOverride 空串表示用课程默认地点。
 */
data class SlotDraft(
    val dayOfWeek: Int = 1,
    val startSection: Int = 1,
    val endSection: Int = 2,
    val startWeek: Int = 1,
    val endWeek: Int = 20,
    val weekType: WeekType = WeekType.ALL,
    val roomOverride: String = ""
)

/**
 * 加课 / 改课界面的状态，整个表单的内容都在这里。
 * 文本框内容也放进状态是 Compose 受控组件的要求：保存时直接读这一份即可，不用逐个问控件取值。
 *
 * @property notFound 要编辑的课不存在；界面显示"没找到"而不是空表单，防止用户填完保存却新建了课。
 * @property courseId 正在编辑的课程 id，`0L` 表示新建，该约定贯穿导航参数与 `saveCourse`。
 * @property isNew 即 `courseId == 0L`，单独存一份方便界面切标题、显隐删除按钮。
 * @property slots 这门课的全部时间段草稿，加/删时段就是对这个列表增删。
 * @property error 待显示的错误信息，null 表示没有；存文案而不是布尔标志，展示时直接用。
 * @property finished 保存或删除完成后置 true 的一次性事件，界面据此返回，ViewModel 不感知导航。
 */
data class CourseEditUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val semesterId: Long = 0L,
    val courseId: Long = 0L,
    val isNew: Boolean = true,
    val totalWeeks: Int = 20,
    val name: String = "",
    val teacher: String = "",
    val location: String = "",
    val colorIndex: Int = 0,
    val note: String = "",
    val slots: List<SlotDraft> = emptyList(),
    val error: String? = null,
    /** 保存或删除完成后置 true，界面据此返回 */
    val finished: Boolean = false
)

/**
 * 加课 / 改课页的 ViewModel。
 * 与课表页的响应式（数据库 Flow 驱动）相反，这里是命令式：进来 `first()` 读一次初值，
 * 之后用户说了算——正在输入的表单不能被数据库 Flow 覆盖。
 *
 * @param repository 数据来源。
 * @param courseId 要编辑的课程 id，`0L` 表示新建。
 * @param presetDay 从课表格子点进来时预填的星期几。
 * @param presetSection 预填的起始节次，目前总传 1，保留参数以便以后做"精确到第几节"。
 */
class CourseEditViewModel(
    private val repository: ScheduleRepository,
    private val courseId: Long,
    private val presetDay: Int,
    private val presetSection: Int
) : ViewModel() {

    // 私有可写。`_xxx` 私有可写 / `xxx` 公开只读是通用命名约定。
    private val _uiState = MutableStateFlow(CourseEditUiState())

    // 对外只读视图：编译期就写不进去，底层仍是同一个流。
    val uiState: StateFlow<CourseEditUiState> = _uiState.asStateFlow()

    /**
     * 初始化：把已有数据（或空白草稿）读进表单。
     *
     * 用 first() 而不是 collect：只取一次初值就取消订阅，之后表单完全由用户掌控，
     * 只有点保存才写回数据库，不会被数据库流刷新。
     * 三条分支：无学期 → notFound（防御，正常进不来）；新建 → 预填空白草稿；
     * 编辑 → 读出课程与时间段映射成草稿。
     */
    init {
        viewModelScope.launch {
            val semester = repository.observeSemester().first()
            if (semester == null) {
                _uiState.update { it.copy(loading = false, notFound = true) }
                return@launch
            }

            if (courseId == 0L) {
                // 预设值来自导航参数（字符串解析），先夹到合法范围，防止构造异常链接进来。
                val day = presetDay.coerceIn(1, 7)
                val start = presetSection.coerceIn(1, MAX_SECTION)
                _uiState.update {
                    it.copy(
                        loading = false,
                        semesterId = semester.id,
                        isNew = true,
                        totalWeeks = semester.totalWeeks,
                        slots = listOf(
                            SlotDraft(
                                dayOfWeek = day,
                                startSection = start,
                                // 结束节次默认比开始多一节，coerceAtMost 防止超过上限
                                endSection = (start + 1).coerceAtMost(MAX_SECTION),
                                // 默认生效范围是整个学期，绝大多数课不用改
                                endWeek = semester.totalWeeks
                            )
                        )
                    )
                }
                return@launch
            }

            val existing = repository.courseWithSlots(courseId)
            if (existing == null) {
                _uiState.update { it.copy(loading = false, notFound = true) }
                return@launch
            }
            // 编辑已有课程：CourseSlot → SlotDraft 逐字段手写映射。
            // 手写让"实体加字段而这里漏了"在编译期报错，反射映射会静默丢数据；正反向转换各只有这一处。
            _uiState.update {
                it.copy(
                    loading = false,
                    semesterId = existing.course.semesterId,
                    courseId = existing.course.id,
                    isNew = false,
                    totalWeeks = semester.totalWeeks,
                    name = existing.course.name,
                    teacher = existing.course.teacher,
                    location = existing.course.location,
                    colorIndex = existing.course.colorIndex,
                    note = existing.course.note,
                    slots = existing.slots.map { slot ->
                        SlotDraft(
                            dayOfWeek = slot.dayOfWeek,
                            startSection = slot.startSection,
                            endSection = slot.endSection,
                            startWeek = slot.startWeek,
                            endWeek = slot.endWeek,
                            weekType = slot.weekType,
                            roomOverride = slot.roomOverride
                        )
                    }
                )
            }
        }
    }

    /** 改课程名，同时清空 error：用户正在解决它，红字再挂着会让人以为没生效。 */
    fun onNameChange(value: String) = _uiState.update { it.copy(name = value, error = null) }

    /** 改教师。不需要清错——教师不是必填项，它变了不会让已有错误失效。 */
    fun onTeacherChange(value: String) = _uiState.update { it.copy(teacher = value) }

    /** 改默认地点。 */
    fun onLocationChange(value: String) = _uiState.update { it.copy(location = value) }

    /** 改备注。 */
    fun onNoteChange(value: String) = _uiState.update { it.copy(note = value) }

    /** 改颜色下标。不校验范围：`colorOf` 会取模，越界也不会崩。 */
    fun onColorChange(index: Int) = _uiState.update { it.copy(colorIndex = index) }

    /**
     * 改一个时间段的某个字段。
     *
     * 参数是"改法"（函数）而不是新值：调用方写 `onSlotChange { it.copy(dayOfWeek = day) }`，
     * ViewModel 只需这一个方法，加字段时它完全不用动。用下标定位是因为草稿没有 id，
     * 界面 forEachIndexed 拿到的也是下标，两边严格对应（删除也因此只能用 removeSlot，不能中间插入）。
     * 顺带清掉 error："至少需要一个时间段"这个错误可能已被这次修改解决。
     */
    fun onSlotChange(index: Int, transform: (SlotDraft) -> SlotDraft) {
        _uiState.update { state ->
            state.copy(
                slots = state.slots.mapIndexed { i, slot ->
                    if (i == index) transform(slot) else slot
                },
                error = null
            )
        }
    }

    /**
     * 加一个空白时间段。endWeek 默认到学期末而不是写死的 20，
     * 避免短学期在"第 17-20 周"还显示新课。
     * 不清 error：加时段不会让"课程名不能为空"这类错误消失，清掉红字会闪一下。
     */
    fun addSlot() = _uiState.update {
        it.copy(slots = it.slots + SlotDraft(endWeek = it.totalWeeks))
    }

    /**
     * 删掉第 index 个时间段。
     * 这里允许删到 0 个不拦（用户可能想先删完再加一批），真正的拦截在 save。
     */
    fun removeSlot(index: Int) = _uiState.update {
        it.copy(slots = it.slots.filterIndexed { i, _ -> i != index })
    }

    /**
     * 保存，整个编辑流程的收口。先同步校验（纯内存判断），再在协程里写库。
     * 校验只做两件事：课程名非空（isBlank，"   "也算空）、至少一个时间段。
     * 刻意不校验节次/周次范围：步进器已用 range 锁死，用户输不出越界值；
     * 但下面的 minOf/maxOf 仍必须——它纠正的是"顺序颠倒"，步进器拦不住。
     * 校验失败不抛异常，统一写入 error 由界面红字展示，单一入口。
     */
    fun save() {
        // 取一份快照，整段校验和组装都基于同一份状态。
        val state = _uiState.value
        if (state.name.isBlank()) {
            _uiState.update { it.copy(error = "课程名不能为空") }
            return
        }
        if (state.slots.isEmpty()) {
            _uiState.update { it.copy(error = "至少需要一个时间段") }
            return
        }

        viewModelScope.launch {
            // 组装课程实体。id 新建时为 0L，saveCourse 靠它区分插入还是更新。
            // 文本在落库前统一 trim（"高数"和"高数 "不该是两门课）；
            // 不在 onXxxChange 里 trim，否则输入过程中没法打空格。
            val course = Course(
                id = state.courseId,
                semesterId = state.semesterId,
                name = state.name.trim(),
                teacher = state.teacher.trim(),
                location = state.location.trim(),
                colorIndex = state.colorIndex,
                note = state.note.trim()
            )
            // 组装时间段实体，全部数据纠正逻辑都在这里，数据层正确性不依赖 UI：
            // minOf/maxOf 静默纠正起止颠倒（意图明确，纠正比报错友好），coerceIn 夹范围防导航参数等绕过界面的来源。
            // endWeek 不设上界可接受：超出学期总周数的周次在课表上本来就不会显示。
            val slots = state.slots.map { draft ->
                CourseSlot(
                    courseId = state.courseId,
                    dayOfWeek = draft.dayOfWeek.coerceIn(1, 7),
                    // 用户可能把开始节次点到比结束节次大，落库前统一纠正，避免脏数据
                    startSection = minOf(draft.startSection, draft.endSection).coerceIn(1, MAX_SECTION),
                    endSection = maxOf(draft.startSection, draft.endSection).coerceIn(1, MAX_SECTION),
                    startWeek = minOf(draft.startWeek, draft.endWeek).coerceAtLeast(1),
                    endWeek = maxOf(draft.startWeek, draft.endWeek),
                    weekType = draft.weekType,
                    roomOverride = draft.roomOverride.trim()
                )
            }
            // 跨两张表的写入，事务在 saveCourse 内部。courseId 不回填：写完立刻 finished 返回；
            // 若以后改成"保存后停留在编辑页"，需回填 courseId 并置 isNew = false。
            repository.saveCourse(course, slots)
            _uiState.update { it.copy(finished = true) }
        }
    }

    /**
     * 删除这门课，不可撤销。
     * isNew 时还没有入库、无东西可删，置 finished 返回即等于取消。
     * 二次确认在界面层做（CourseEditScreen 的 AlertDialog）：
     * delete 被调用即视为用户已确认，其他入口复用删除能力时可自定确认方式。
     */
    fun delete() {
        val state = _uiState.value
        if (state.isNew) {
            _uiState.update { it.copy(finished = true) }
            return
        }
        viewModelScope.launch {
            repository.deleteCourse(state.courseId)
            _uiState.update { it.copy(finished = true) }
        }
    }

    /**
     * 清掉 error。当前界面把 error 当普通状态显示，用不到它；
     * 留给以后改 Toast/Snackbar 一次性展示时使用（否则重组一次就会再弹一次）。
     */
    fun consumeError() = _uiState.update { it.copy(error = null) }
}
