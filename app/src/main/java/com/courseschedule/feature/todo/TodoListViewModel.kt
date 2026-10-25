package com.courseschedule.feature.todo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.courseschedule.data.ScheduleRepository
import com.courseschedule.data.entity.Todo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 列表里的一条待办 + 它关联的课程名。
 * 课程名在 ViewModel 一次性建好 `courseId -> name` 映射，避免界面逐条查询（N+1）。
 *
 * @property courseName `null` 表示没关联或关联的课程已删了（软关联），界面上都不显示课程名，无需区分。
 */
data class TodoListItem(
    val todo: Todo,
    val courseName: String?
)

/**
 * 待办列表页的状态。
 *
 * @property active 未完成，排序由 SQL 决定，见 `TodoDao.observeAll`。
 * @property done 已完成，界面上折叠在底部。
 */
data class TodoListUiState(
    val loading: Boolean = true,
    val active: List<TodoListItem> = emptyList(),
    val done: List<TodoListItem> = emptyList()
)

/**
 * 待办列表页的 ViewModel。
 * 课程名要跟着"当前学期"走：课程流用 `flatMapLatest` 跟随学期 Flow，
 * 学期一变就取消上一条课程流、换上新学期的，课程名随之重查。
 * `flatMapLatest` 仍标记为实验性 API，故 `@OptIn`。
 */
class TodoListViewModel(private val repository: ScheduleRepository) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val courses = repository.observeSemester().flatMapLatest { semester ->
        // 还没有学期（首帧）→ 空流。不这么写的话 observeCourses(0L) 会一直查不到东西。
        if (semester == null) flowOf(emptyList()) else repository.observeCourses(semester.id)
    }

    val uiState: StateFlow<TodoListUiState> = combine(repository.observeTodos(), courses) { todos, courseList ->
        val names = courseList.associate { it.course.id to it.course.name }
        val items = todos.map { TodoListItem(it, it.courseId?.let(names::get)) }
        TodoListUiState(
            loading = false,
            active = items.filter { !it.todo.done },
            done = items.filter { it.todo.done }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TodoListUiState()
    )

    /** 勾选/取消勾选。传目标值而不是取反：`todo.done` 是那一帧的快照，点击瞬间算出的目标值最接近用户意图。 */
    fun setDone(item: TodoListItem, done: Boolean) {
        viewModelScope.launch { repository.setTodoDone(item.todo.id, done) }
    }
}
