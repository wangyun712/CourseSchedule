package com.courseschedule.feature.todo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.courseschedule.data.ScheduleRepository
import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.Todo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 待办编辑页的状态。
 *
 * @property title 标题，必填。
 * @property dueEpochDay 截止日期，按"天"存（`null` 表示没有）：待办只有天的精度，
 *   存毫秒反解容易因时区/夏令时漂移一天；落库时才转成当天 00:00 的毫秒（见 `epochDayToMillis`）。
 * @property courseId 关联课程 id，`null` 表示不关联。软关联，课程删了不报错。
 * @property done 完成状态。编辑页不提供修改，但必须原样带回去，否则编辑已完成的待办会变回未完成。
 */
data class TodoEditUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val todoId: Long = 0L,
    val title: String = "",
    val note: String = "",
    val dueEpochDay: Long? = null,
    val courseId: Long? = null,
    val done: Boolean = false,
    val courses: List<Course> = emptyList(),
    val error: String? = null,
    val finished: Boolean = false
)

/**
 * 待办编辑页的 ViewModel，显式保存而非自动保存：待办是小表单、有必填校验和明确的"建完了"这一刻
 * （便签是自由长文本所以用自动保存），两种保存模型并存是有意的，不是漏改。
 *
 * @param todoId 要编辑的待办 id，`0L` 表示新建。
 */
class TodoEditViewModel(
    private val repository: ScheduleRepository,
    private val todoId: Long
) : ViewModel() {

    private val _uiState = MutableStateFlow(TodoEditUiState(done = false, todoId = todoId))
    val uiState: StateFlow<TodoEditUiState> = _uiState.asStateFlow()

    /**
     * 原始对象。保存时以它为基准 `copy`，把界面不管的字段（`remindAt`、`repeatRule`）原样带过去；
     * 直接用界面字段 new `Todo` 会把它们静默清空——以后加提醒功能时，改一次标题提醒就没了，极难复现。
     */
    private var original: Todo? = null

    init {
        viewModelScope.launch {
            // 一次性读：学期 → 课程表。编辑页不需要跟着数据库变化刷新。
            val semester = repository.observeSemester().first()
            val courses = semester
                ?.let { repository.observeCourses(it.id).first() }
                ?.map { it.course }
                ?: emptyList()

            if (todoId == 0L) {
                _uiState.update { it.copy(loading = false, courses = courses) }
                return@launch
            }

            val existing = repository.todo(todoId)
            if (existing == null) {
                _uiState.update { it.copy(loading = false, notFound = true, courses = courses) }
                return@launch
            }
            original = existing
            _uiState.update {
                it.copy(
                    loading = false,
                    courses = courses,
                    todoId = existing.id,
                    title = existing.title,
                    note = existing.note,
                    dueEpochDay = existing.dueAt?.let(::millisToEpochDay),
                    courseId = existing.courseId,
                    done = existing.done
                )
            }
        }
    }

    fun onTitleChange(value: String) = _uiState.update { it.copy(title = value, error = null) }

    fun onNoteChange(value: String) = _uiState.update { it.copy(note = value) }

    /** 选截止日期，`null` 表示清除（界面上"清除"按钮传 null）。 */
    fun onDueChange(epochDay: Long?) = _uiState.update { it.copy(dueEpochDay = epochDay) }

    /** 选关联课程，`null` 表示不关联。 */
    fun onCourseChange(courseId: Long?) = _uiState.update { it.copy(courseId = courseId) }

    /** 保存。标题必填，其他都是可选的。 */
    fun save() {
        val state = _uiState.value
        if (state.title.isBlank()) {
            _uiState.update { it.copy(error = "标题不能为空") }
            return
        }
        viewModelScope.launch {
            // 以 original 为基准 copy（见其注释）；新建时用 `Todo(title = "")` 当底：
            // 实体的 title 没有默认值，也免得随字段增加手写全部参数。
            val base = original ?: Todo(title = "")
            repository.saveTodo(
                base.copy(
                    id = state.todoId,
                    title = state.title,
                    note = state.note,
                    dueAt = state.dueEpochDay?.let(::epochDayToMillis),
                    courseId = state.courseId,
                    done = state.done
                )
            )
            _uiState.update { it.copy(finished = true) }
        }
    }

    /** 删除。新建状态等于取消。二次确认在界面里做。 */
    fun delete() {
        val state = _uiState.value
        if (state.todoId == 0L) {
            _uiState.update { it.copy(finished = true) }
            return
        }
        viewModelScope.launch {
            repository.deleteTodo(state.todoId)
            _uiState.update { it.copy(finished = true) }
        }
    }
}

/** 毫秒时间戳 → 本地日期序号。用 `ZoneId.systemDefault()` 而非 UTC，否则东八区用户在 00:00–08:00 之间会看到"昨天"。 */
private fun millisToEpochDay(millis: Long): Long =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

/** 本地日期序号 → 当天 00:00 的毫秒。和 [millisToEpochDay] 互逆。 */
private fun epochDayToMillis(epochDay: Long): Long =
    LocalDate.ofEpochDay(epochDay).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
