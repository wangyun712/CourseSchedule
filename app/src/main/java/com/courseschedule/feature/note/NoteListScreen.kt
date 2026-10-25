package com.courseschedule.feature.note

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.courseschedule.data.entity.Note
import com.courseschedule.ui.EmptyHint
import com.courseschedule.ui.appViewModel
import com.courseschedule.ui.theme.CoursePalette
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 便签列表页：两列瀑布流（卡片按内容自适应高度，长短不一更紧凑）+ 右下角新建按钮。
 * 数据源是 `repository.observeNotes()`，数据库一变就自动重发，
 * 编辑页保存/删除后返回无需任何刷新通知。
 *
 * @param onEditNote 点某张卡片时触发，参数是便签 id。
 */
@Composable
fun NoteListScreen(onAddNote: () -> Unit, onEditNote: (Long) -> Unit) {
    val viewModel = appViewModel { NoteListViewModel(it.repository) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // 标题栏：标题 + 条数。没有返回/操作按钮，不需要 TopAppBar 的多余高度。
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "便签",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (state.notes.isNotEmpty()) {
                    Text(
                        text = "${state.notes.size} 条",
                        modifier = Modifier.padding(start = 8.dp),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            when {
                // 首次查询还没回来：转圈。不加这一条的话，空态文案会先闪一下再被列表顶掉。
                state.loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                state.notes.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        EmptyHint("还没有便签")
                        Text(
                            text = "点右下角 ＋ 记一条",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    // bottom 留 88dp 给 FAB 让位，否则最后一行卡片被压住且滚不出来。
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 88.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalItemSpacing = 10.dp
                ) {
                    // key 用 id：删除后 Compose 能把剩下的卡片和原来的对上，不整列重建、不丢滚动位置。
                    items(state.notes, key = { it.id }) { note ->
                        NoteCard(
                            note = note,
                            onClick = { onEditNote(note.id) },
                            onTogglePin = { viewModel.setPinned(note, !note.pinned) }
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onAddNote,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
            // 仍然用文字字形代替图标库，理由同 MainActivity 里底部导航的注释
            Text("＋", fontSize = 24.sp)
        }
    }
}

/**
 * 一张便签卡片。底色用便签色压到 12% 透明度，文字仍用 `onSurface`（跟随主题）——
 * 黄色便签配便签色文字在浅色模式下直接看不见。
 *
 * @param onTogglePin 点"置顶/取消置顶"，和卡片主体是两个独立点击区：
 *   子元素自己的 clickable 先消费点击，不会再触发外层。
 */
@Composable
private fun NoteCard(note: Note, onClick: () -> Unit, onTogglePin: () -> Unit) {
    val accent = CoursePalette.colorOf(note.colorIndex)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            // clip 必须在 background 前，圆角才生效
            .background(accent.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 小色点：10% 的底色在小屏上不太看得出来，加个实色圆点明确"这条是哪个颜色"
            Box(Modifier.size(7.dp).clip(CircleShape).background(accent))
            if (note.pinned) {
                Text(
                    text = "置顶",
                    modifier = Modifier.padding(start = 6.dp),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Text(
            text = note.displayTitle(),
            modifier = Modifier.padding(top = 6.dp),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            // 标题最多两行：太长的标题会把卡片撑得比正文还高
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        // 标题留空时，首行已经被当成标题显示了，摘要就从第二行开始，避免同一句话出现两次
        val preview = if (note.title.isBlank()) note.content.substringAfter('\n', "").trim() else note.content
        if (preview.isNotBlank()) {
            Text(
                text = preview,
                modifier = Modifier.padding(top = 4.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // 正文最多 6 行，超出省略——瀑布流卡片只用于扫一眼，完整内容进编辑页看。
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = noteTimeText(note.updatedAt),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (note.pinned) "取消置顶" else "置顶",
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onTogglePin)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * 卡片上显示什么标题。自动保存不强制填标题，`title` 可能为空，
 * 用正文第一个非空行兜底——只在显示层兜底，不写回数据库，
 * 否则用户"没写过标题"会凭空多出一段自动生成的文字。
 */
private fun Note.displayTitle(): String {
    if (title.isNotBlank()) return title
    val firstLine = content.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    return if (firstLine.isBlank()) "无标题" else firstLine.take(20)
}

/**
 * 把 `updatedAt`（epoch 毫秒）格式化成三种粒度：今天/昨天、今年内的月日、跨年才补年份。
 * `updatedAt == 0L` 的历史数据兜底返回空串，不把"1970年1月1日"显示到界面上。
 */
private fun noteTimeText(updatedAt: Long): String {
    if (updatedAt <= 0L) return ""
    val dateTime = Instant.ofEpochMilli(updatedAt).atZone(ZoneId.systemDefault())
    val date = dateTime.toLocalDate()
    val today = LocalDate.now()
    return when {
        date == today -> "今天 " + dateTime.format(DateTimeFormatter.ofPattern("HH:mm"))
        date == today.minusDays(1) -> "昨天"
        date.year == today.year -> "${date.monthValue} 月 ${date.dayOfMonth} 日"
        else -> "${date.year}/${date.monthValue}/${date.dayOfMonth}"
    }
}
