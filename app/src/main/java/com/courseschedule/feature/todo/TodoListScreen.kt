package com.courseschedule.feature.todo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.courseschedule.data.entity.Todo
import com.courseschedule.ui.EmptyHint
import com.courseschedule.ui.appViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 待办列表页：未完成在上、已完成折叠在下，右下角新建。
 * 用 LazyColumn 而非 Column + verticalScroll——判断标准：
 * 内容长度由用户决定（待办无上限）用 LazyColumn，固定且不大才用普通 Column。
 *
 * @param onEditTodo 点某一行，参数是待办 id。
 */
@Composable
fun TodoListScreen(onAddTodo: () -> Unit, onEditTodo: (Long) -> Unit) {
    val viewModel = appViewModel { TodoListViewModel(it.repository) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "待办",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (state.active.isNotEmpty()) {
                    Text(
                        text = "${state.active.size} 项未完成",
                        modifier = Modifier.padding(start = 8.dp),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            when {
                state.loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                state.active.isEmpty() && state.done.isEmpty() -> Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        EmptyHint("还没有待办")
                        Text(
                            text = "点右下角 ＋ 记一条",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    // 底部留 96dp：给 FAB 让位，否则最后一条会被按钮压住且滚不出来
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    if (state.active.isNotEmpty()) {
                        item(key = "header-active") { GroupHeader("未完成") }
                        // key 用 id：勾选后条目会在两组之间移动，稳定 key 让 Compose 做移动/移除而不是整列重建。
                        items(state.active, key = { it.todo.id }) { item ->
                            TodoRow(
                                item = item,
                                onToggle = { viewModel.setDone(item, true) },
                                onOpen = { onEditTodo(item.todo.id) }
                            )
                        }
                    }
                    if (state.done.isNotEmpty()) {
                        item(key = "header-done") { GroupHeader("已完成") }
                        items(state.done, key = { it.todo.id }) { item ->
                            TodoRow(
                                item = item,
                                onToggle = { viewModel.setDone(item, false) },
                                onOpen = { onEditTodo(item.todo.id) }
                            )
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onAddTodo,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
            Text("＋", fontSize = 24.sp)
        }
    }
}

/** 分组标题。用文件内私有，别处不关心这两个字怎么写。 */
@Composable
private fun GroupHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary
    )
}

/**
 * 一行待办。圆圈和行是两个独立点击区：点圆圈只切换完成状态，点其他位置进编辑页。
 * 不用 Material `Checkbox`——固定触摸区和自带样式会把 14sp 标题的紧凑行撑到 56dp 以上，
 * 自绘 22dp 圆圈 + 行级点击区更合适。
 *
 * @param onToggle 点圆圈，标完成/取消完成由调用方决定。
 */
@Composable
private fun TodoRow(item: TodoListItem, onToggle: () -> Unit, onOpen: () -> Unit) {
    val todo = item.todo
    val done = todo.done
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (done) MaterialTheme.colorScheme.primary else Color.Transparent)
                .border(
                    width = 1.5.dp,
                    color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    shape = CircleShape
                )
                // 触摸区 22dp 小于无障碍建议的 48dp：点标题进编辑是另一条同样好按的路径，可接受。
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            if (done) {
                Text("✓", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimary)
            }
        }

        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = todo.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                // 完成后划一道横线，是清单类应用最省事的"已完成"视觉标记
                textDecoration = if (done) TextDecoration.LineThrough else null,
                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val subtitle = todoSubtitle(item)
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    modifier = Modifier.padding(top = 2.dp),
                    fontSize = 11.sp,
                    // 逾期的日期用错误色，是列表里唯一需要"立刻看见"的信息
                    color = if (isOverdue(todo)) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 拼副标题：`课程名 · 截止时间`，两段都可能没有；用 `listOfNotNull` 拼接缺段时不会留多余分隔符。 */
private fun todoSubtitle(item: TodoListItem): String {
    val due = item.todo.dueAt?.let(::dueText)
    return listOfNotNull(item.courseName, due).joinToString(" · ")
}

/** 截止时间文案：今天/明天用相对说法，更远的用"X 月 X 日"；逾期加"已逾期"前缀配合红色。 */
private fun dueText(dueAt: Long): String {
    val date = Instant.ofEpochMilli(dueAt).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    val base = when (date) {
        today -> "今天截止"
        today.plusDays(1) -> "明天截止"
        else -> "${date.monthValue} 月 ${date.dayOfMonth} 日"
    }
    return if (date.isBefore(today)) "已逾期 · $base" else base
}

/** 是否逾期。已完成的待办不算逾期——昨天该交、今天做完的作业不该再标红。 */
private fun isOverdue(todo: Todo): Boolean {
    if (todo.done) return false
    val dueAt = todo.dueAt ?: return false
    val date = Instant.ofEpochMilli(dueAt).atZone(ZoneId.systemDefault()).toLocalDate()
    return date.isBefore(LocalDate.now())
}
