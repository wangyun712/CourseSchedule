package com.courseschedule.feature.note

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.courseschedule.ui.ColorDots
import com.courseschedule.ui.EmptyHint
import com.courseschedule.ui.LabeledRow
import com.courseschedule.ui.VerticalSpacer
import com.courseschedule.ui.appViewModel

/**
 * 便签编辑页（新建 / 编辑共用），内容自动保存（防抖 600ms + 退出强制刷盘，见 `NoteEditViewModel`），
 * 因此没有"保存"按钮，右上角"完成"只负责立刻刷盘并返回。
 * 返回、完成、系统返回键三条返回路径都必须走 `finish`——系统返回键最容易漏，由 `BackHandler` 接管。
 *
 * @param noteId 要编辑的便签 id，`0L` 表示新建。
 */
@Composable
fun NoteEditScreen(noteId: Long, onBack: () -> Unit) {
    val viewModel = appViewModel { NoteEditViewModel(it.repository, noteId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var showDeleteConfirm by remember { mutableStateOf(false) }

    // 系统返回键：先刷盘再返回。这一页没有别的返回语义，无条件拦截。
    BackHandler { viewModel.finish() }

    // finished 是一次性事件，放 LaunchedEffect 里避免每次重组都调一次 onBack。
    LaunchedEffect(state.finished) {
        if (state.finished) onBack()
    }

    when {
        state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        state.notFound -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            EmptyHint("这条便签已经不在了")
            TextButton(onClick = onBack) { Text("返回") }
        }

        else -> Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {

            // 顶部：返回 / 标题 / 完成，用 weight(1f) 的 Box 夹住标题使其居中。
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = viewModel::finish,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) { Text("返回", fontSize = 14.sp) }

                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        // noteId 为 0 表示还没入过库；第一次自动保存之后它就变成"编辑中"了
                        text = if (state.noteId == 0L) "新建便签" else "编辑便签",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                TextButton(
                    onClick = viewModel::finish,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("完成", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }

            OutlinedTextField(
                value = state.title,
                onValueChange = viewModel::onTitleChange,
                modifier = Modifier.fillMaxWidth(),
                // 自动保存下不强制标题：留空时列表会用正文首行顶上（见 NoteListScreen）
                placeholder = { Text("标题（可留空）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )

            // 只有保存失败（磁盘满之类）才出现，显示 ViewModel 抛上来的错误。
            state.error?.let { error ->
                Text(
                    text = error,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }

            VerticalSpacer(10)
            ColorDots(selectedIndex = state.colorIndex, onSelect = viewModel::onColorChange)
            VerticalSpacer(4)

            LabeledRow(label = "置顶") {
                // Switch 默认左对齐，套一个 Row 推到右边，和设置页的观感一致
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Switch(checked = state.pinned, onCheckedChange = { viewModel.onTogglePinned() })
                }
            }

            OutlinedTextField(
                value = state.content,
                onValueChange = viewModel::onContentChange,
                modifier = Modifier.fillMaxWidth().weight(1f),
                placeholder = { Text("写点什么…") }
                // 不给 maxLines：多行输入，超出时文本框内部自己滚动
            )

            // 只有已经入库的便签才显示删除——新建的还没东西可删
            if (state.noteId != 0L) {
                VerticalSpacer(8)
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("删除这条便签", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                }
            }
            VerticalSpacer(12)
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除便签") },
            text = { Text("删除后无法恢复，确定要删除这条便签吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.delete()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }
}
