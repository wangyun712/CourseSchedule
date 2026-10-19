package com.courseschedule.feature.placeholder

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.courseschedule.ui.HintText
import com.courseschedule.ui.VerticalSpacer
import com.courseschedule.ui.appContainer

/**
 * # P3 / P4 的占位页
 *
 * ## 为什么它要真的去查数据库
 *
 * 便签和待办在 P1 阶段只建了表、没写界面。如果这里写一段死文案
 * （"功能开发中"），那"表建好了"就只是一句承诺——谁知道 SQL 语句写对了没有、
 * 外键索引建了没有、DAO 能不能正常返回？
 *
 * 这里把 `note` / `todo` 两张表的**真实条数**读出来显示，
 * 数据链路就是从 Room 到界面完整跑通一遍了。跑通了才叫"已就绪"。
 *
 * ## ⚠️ 这个页面**直接**调用了 Repository，没走 ViewModel
 *
 * 这是对整个项目分层约定的一个有意破例，理由：
 * - 只有只读查询，没有任何交互状态（没有"输入一半"的内容需要保护）
 * - 没有状态需要跨转屏保存（条数从数据库重新查一遍就行）
 * - 这是一个临时页面，写完 P3 就会被真正的列表页替换掉
 *
 * **什么时候必须补上 ViewModel**：开始有"用户输入但未提交"的状态、
 * 有多个查询需要合并成一个自洽快照、或者要处理"保存失败重试"这类逻辑时。
 * 到那时把这个页面直接改造成 `PlaceholderViewModel` 就行。
 *
 * ## 对比一下正确分层的做法（课表页）
 *
 * `ScheduleViewModel` 把多个 Flow 用 `combine` 合成一个 `UiState` 再暴露给界面，
 * 界面只 `collectAsStateWithLifecycle()` 拿一个对象。
 * 那种做法在"状态复杂"时才划算。
 *
 * @param title       页面标题
 * @param description 这段功能未来的说明
 */
@Composable
fun PlaceholderScreen(title: String, description: String) {
    // appContainer() 本身是 @Composable 函数（见 ui/VmFactory.kt），
    // 所以可以在 Composable 里直接调。取的是 Application 里那个唯一的实例。
    val repository = appContainer().repository

    // collectAsStateWithLifecycle：把 Flow 变成 Compose 能用的 State。
    //
    // - `initialValue = 0`：Flow 的第一次发射要等一个异步往返（数据库查询），
    //   在那之前必须有值可渲染。给 0 是最自然的初值。
    //   （其实 StateFlow 有 `.value` 可以同步读，官方也提供了
    //   `collectAsStateWithLifecycle()` 的无参重载，但显式写 initialValue
    //   读起来更明确。）
    // - `WithLifecycle` 这一半很重要：界面退到后台（比如按 Home 键）时，
    //   它会**自动停止收集**，避免后台无谓地占着数据库连接和 CPU。
    //   不带 Lifecycle 的 `collectAsState` 会一直收，直到 Composable 被销毁。
    // - `by` 是委托，声明后 `noteCount` 就是 Int 而不是 State<Int>，
    //   同时 Compose 会在读取处自动记录依赖（读它 = 订阅它）。
    val noteCount by repository.observeNoteCount().collectAsStateWithLifecycle(initialValue = 0)
    val todoCount by repository.observeTodoCount().collectAsStateWithLifecycle(initialValue = 0)

    Column(
        // Modifier 链顺序：fillMaxSize 先撑满，padding 再往里缩 24dp。
        // 反过来写就是"先缩再撑满"，会溢出 24dp 被裁掉。
        modifier = Modifier.fillMaxSize().padding(24.dp),
        // 横向居中。纵向没设只是默认顶对齐——
        // 内容贴着上方，看起来比"垂直居中"更稳（垂直居中会随内容多少上下跳）。
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, fontSize = 20.sp, style = MaterialTheme.typography.titleLarge)
        VerticalSpacer(16)
        HintText(description)
        VerticalSpacer(16)
        // 两个条数都在这一句里显示。现在必然都是 0（P1 还没写入入口），
        // 但因为是从真实表里查出来的，所以以后一旦有数据会自动跟着变。
        HintText("表已就绪：便签 $noteCount 条 · 待办 $todoCount 条")
    }
}
