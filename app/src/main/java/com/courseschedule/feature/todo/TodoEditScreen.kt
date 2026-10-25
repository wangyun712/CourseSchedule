package com.courseschedule.feature.todo

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.courseschedule.ui.ChoiceChips
import com.courseschedule.ui.DatePickerDialogM3
import com.courseschedule.ui.EmptyHint
import com.courseschedule.ui.SectionTitle
import com.courseschedule.ui.VerticalSpacer
import com.courseschedule.ui.appViewModel
import java.time.LocalDate

/**
 * 待办编辑页（新建 / 编辑共用），显式保存（与便签的自动保存并存是有意的，见 `TodoEditViewModel`）。
 * 刻意不做：优先级（排序已够用）、提醒时间（用户不要闹钟，字段留表不暴露）、
 * 重复规则（做错了会静默漏项）。
 *
 * @param todoId 要编辑的待办 id，`0L` 表示新建。
 */
@Composable
fun TodoEditScreen(todoId: Long, onBack: () -> Unit) {
    val viewModel = appViewModel { TodoEditViewModel(it.repository, todoId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

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
            EmptyHint("这条待办已经不在了")
            TextButton(onClick = onBack) { Text("返回") }
        }

        else -> Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onBack,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) { Text("返回", fontSize = 14.sp) }

                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (state.todoId == 0L) "新建待办" else "编辑待办",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                TextButton(
                    onClick = viewModel::save,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("保存", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }

            OutlinedTextField(
                value = state.title,
                onValueChange = viewModel::onTitleChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("要做什么") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )

            state.error?.let { error ->
                Text(
                    text = error,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }

            VerticalSpacer(12)
            SectionTitle("关联课程")
            VerticalSpacer(4)
            // 课程用横向滚动的胶囊组而非下拉菜单：选项约 10 个，横滑一下就能看到，少两次操作。
            // 第一项固定是 null（不关联）；`ChoiceChips` 是泛型组件，这里 T = Long?，selected 可直接传 courseId。
            val options = remember(state.courses) { listOf<Long?>(null) + state.courses.map { it.id } }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                ChoiceChips(
                    options = options,
                    selected = state.courseId,
                    labelOf = { id ->
                        if (id == null) "不关联"
                        // 课程可能已被删除（软关联），这时显示"已删除"而不是崩掉
                        else state.courses.firstOrNull { it.id == id }?.name ?: "已删除"
                    },
                    onSelect = viewModel::onCourseChange
                )
            }

            VerticalSpacer(12)
            SectionTitle("截止日期")
            // 日期用按钮 + 系统日期选择器而非输入框：选择器天然给不出"2026-13-45"这类非法值。
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { showDatePicker = true },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = state.dueEpochDay?.let(::formatDueDate) ?: "未设置",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                if (state.dueEpochDay != null) {
                    TextButton(
                        onClick = { viewModel.onDueChange(null) },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    ) { Text("清除", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }

            VerticalSpacer(8)
            OutlinedTextField(
                value = state.note,
                onValueChange = viewModel::onNoteChange,
                modifier = Modifier.fillMaxWidth().weight(1f),
                placeholder = { Text("补充说明（可留空）") }
            )

            if (state.todoId != 0L) {
                VerticalSpacer(8)
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("删除这条待办", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                }
            }
            VerticalSpacer(12)
        }
    }

    if (showDatePicker) {
        // 没设过日期时，默认定位到今天——这是新建待办时最常见的选择起点
        DatePickerDialogM3(
            initialEpochDay = state.dueEpochDay ?: LocalDate.now().toEpochDay(),
            onDismiss = { showDatePicker = false },
            onPicked = { epochDay ->
                showDatePicker = false
                viewModel.onDueChange(epochDay)
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除待办") },
            text = { Text("删除后无法恢复，确定要删除这条待办吗？") },
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

/** 截止日期的显示格式：`2026 年 10 月 21 日`。 */
private fun formatDueDate(epochDay: Long): String {
    val date = LocalDate.ofEpochDay(epochDay)
    return "${date.year} 年 ${date.monthValue} 月 ${date.dayOfMonth} 日"
}
