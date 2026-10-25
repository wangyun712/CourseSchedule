package com.courseschedule.feature.note

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.courseschedule.data.ScheduleRepository
import com.courseschedule.data.entity.Note
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 便签编辑页的状态。
 *
 * @property notFound 要编辑的便签不存在了（并发删除）。
 * @property noteId 当前便签 id，`0L` 表示还没入过库（新建且还没到第一次自动保存）。
 * @property error 保存失败的提示，`null` 表示没有。
 * @property finished 已经收尾，界面据此返回上一页。
 */
data class NoteEditUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val noteId: Long = 0L,
    val title: String = "",
    val content: String = "",
    val colorIndex: Int = 0,
    val pinned: Boolean = false,
    val error: String? = null,
    val finished: Boolean = false
)

/**
 * 便签编辑页的 ViewModel，自动保存 = 防抖 600ms + 退出前强制刷盘，两件事缺一不可
 * （只做防抖会丢最后一笔，只做退出保存扛不住中途崩溃）。
 *
 * 全空（标题正文皆空白）不写库；编辑已有便签时清空内容不会删掉整条便签，是刻意保留——
 * 自动保存下"清空"常是重写前的全选删除，真要删走删除按钮。
 *
 * @param noteId 要编辑的便签 id，`0L` 表示新建。
 */
class NoteEditViewModel(
    private val repository: ScheduleRepository,
    private val noteId: Long
) : ViewModel() {

    private val _uiState = MutableStateFlow(NoteEditUiState(noteId = noteId))
    val uiState: StateFlow<NoteEditUiState> = _uiState.asStateFlow()

    /** 待执行的防抖任务。每次编辑都要先 cancel 它，再起一个新的。 */
    private var saveJob: Job? = null

    /** 有没有"改了但还没写进数据库"的内容。避免定时器到点时白写一次。 */
    private var dirty = false

    init {
        if (noteId == 0L) {
            _uiState.update { it.copy(loading = false) }
        } else {
            viewModelScope.launch {
                val existing = repository.note(noteId)
                if (existing == null) {
                    _uiState.update { it.copy(loading = false, notFound = true) }
                } else {
                    _uiState.update {
                        it.copy(
                            loading = false,
                            noteId = existing.id,
                            title = existing.title,
                            content = existing.content,
                            colorIndex = existing.colorIndex,
                            pinned = existing.pinned
                        )
                    }
                }
            }
        }
    }

    fun onTitleChange(value: String) = edit { it.copy(title = value) }

    fun onContentChange(value: String) = edit { it.copy(content = value) }

    /** 改颜色。颜色也是便签内容的一部分，同样要触发自动保存。 */
    fun onColorChange(index: Int) = edit { it.copy(colorIndex = index) }

    /** 切换置顶。 */
    fun onTogglePinned() = edit { it.copy(pinned = !it.pinned) }

    /**
     * 所有编辑动作的唯一入口：改状态 → 标脏 → 重新计时。
     * 只有一个入口才不可能漏标脏/漏重排定时器——这类遗漏不报错，
     * 只表现为"某些改动没保存"，极难查。
     */
    private fun edit(transform: (NoteEditUiState) -> NoteEditUiState) {
        _uiState.update(transform)
        dirty = true
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DELAY_MS)
            persist()
        }
    }

    /**
     * 真正的落库动作，返回是否成功（没有待保存内容也视为成功）。
     * 新建便签首次保存后要把新分配的 id 回填进状态，否则后续每次保存都会再插入一条新的。
     */
    private suspend fun persist(): Boolean {
        val state = _uiState.value
        if (!dirty) return true
        // 全空不写库，理由见类注释
        if (state.title.isBlank() && state.content.isBlank()) return true
        dirty = false
        return try {
            val newId = repository.saveNote(
                Note(
                    id = state.noteId,
                    title = state.title,
                    content = state.content,
                    colorIndex = state.colorIndex,
                    pinned = state.pinned
                )
            )
            if (state.noteId == 0L) {
                _uiState.update { it.copy(noteId = newId, error = null) }
            }
            true
        } catch (e: Exception) {
            // 磁盘满等极端情况：恢复 dirty，用户继续编辑或再次退出时会重试，而不是静默丢数据。
            dirty = true
            _uiState.update { it.copy(error = "保存失败：${e.message ?: "未知错误"}") }
            false
        }
    }

    /**
     * 收尾：取消定时器并立刻保存未落库的改动，成功才置 `finished` 让界面返回。
     * "返回"按钮和系统返回键都必须走这里。`persist()` 失败时错误已写入 `state.error`，
     * 留在本页且 `dirty` 仍为 true，用户再按"完成"或继续编辑都会重试。
     */
    fun finish() {
        saveJob?.cancel()
        viewModelScope.launch {
            if (persist()) {
                _uiState.update { it.copy(finished = true) }
            }
        }
    }

    /** 删除。`noteId == 0L` 表示从没入过库、无东西可删，直接收尾；二次确认在界面里做。 */
    fun delete() {
        saveJob?.cancel()
        val state = _uiState.value
        if (state.noteId == 0L) {
            _uiState.update { it.copy(finished = true) }
            return
        }
        viewModelScope.launch {
            repository.deleteNote(state.noteId)
            _uiState.update { it.copy(finished = true) }
        }
    }

    companion object {
        /** 防抖窗口：600ms 足够把 200-300ms 的打字间隔合并成一次写入。 */
        private const val SAVE_DELAY_MS = 600L
    }
}
