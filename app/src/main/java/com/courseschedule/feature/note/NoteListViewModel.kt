package com.courseschedule.feature.note

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.courseschedule.data.ScheduleRepository
import com.courseschedule.data.entity.Note
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 便签列表页的状态。
 *
 * @property loading 首次查询未返回，用于区分"真的没有便签"和"还没查完"，避免空态文案闪一下。
 * @property notes 已排序的便签：置顶在前、其余按修改时间倒序，排序由 SQL 决定（`NoteDao.observeAll`）。
 */
data class NoteListUiState(
    val loading: Boolean = true,
    val notes: List<Note> = emptyList()
)

/**
 * 便签列表页的 ViewModel。纯响应式：界面不主动请求刷新，数据库一变列表自动更新。
 * 源头 Flow 经 `stateIn`（`WhileSubscribed(5_000)`）转成 StateFlow，5 秒宽限覆盖转屏等瞬断。
 */
class NoteListViewModel(private val repository: ScheduleRepository) : ViewModel() {

    val uiState: StateFlow<NoteListUiState> = repository.observeNotes()
        .map { NoteListUiState(loading = false, notes = it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = NoteListUiState()
        )

    /**
     * 切换置顶。传目标值而不是取反：界面写 `viewModel.setPinned(note, !note.pinned)`，
     * 一次点击对应一次明确写入（理由见 `ScheduleRepository.setNotePinned`）。
     */
    fun setPinned(note: Note, pinned: Boolean) {
        viewModelScope.launch { repository.setNotePinned(note.id, pinned) }
    }

    /** 删除一条便签。二次确认在界面里做，走到这里就代表用户已经确认了。 */
    fun delete(noteId: Long) {
        viewModelScope.launch { repository.deleteNote(noteId) }
    }
}
