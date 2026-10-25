package com.courseschedule.feature.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.courseschedule.BuildConfig
import com.courseschedule.core.TimeUtils
import com.courseschedule.ui.CardShell
import com.courseschedule.ui.DatePickerDialogM3
import com.courseschedule.ui.HintText
import com.courseschedule.ui.NumberStepper
import com.courseschedule.ui.SectionTitle
import com.courseschedule.ui.TimePickerDialogM3
import com.courseschedule.ui.VerticalSpacer
import com.courseschedule.ui.appViewModel
import java.time.LocalDate

/**
 * 设置页：学期信息 + 节次作息 + 数据概览 + 备份。
 *
 * 校验失败/保存结果用 Toast 而非加课页那种顶部红字：本页保存按钮在最底部，
 * 用户点击后视线还在下面，Toast 弹在中下方正好可见；加课页的错误发生在
 * 顶部输入框附近，红字才合适。
 *
 * @param onBack 返回上一页。
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val viewModel = appViewModel { SettingsViewModel(it.repository, it.backupManager) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Activity 级 Context，只在 Composable 作用域内临时使用（此处仅用于 Toast）：
    // 存入长生命周期对象（ViewModel/单例）会阻止 Activity 回收，即 Context 泄漏。
    val context = LocalContext.current

    // 纯展示状态，丢了无所谓，留在界面不进 ViewModel。
    var showDatePicker by remember { mutableStateOf(false) }

    // 编辑目标 = Pair(节次, 是否开始时间)：两者必须同时确定或同时为 null，
    // 合成一个可空对象，避免"一个有值一个没有"的不一致中间态。
    var timePickerTarget by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }

    // 只读闸门：默认只读防误触。用 rememberSaveable，转屏重建 Activity 后不丢编辑态；纯 UI 状态，不进 ViewModel。
    var editingPeriods by rememberSaveable { mutableStateOf(false) }

    // 已选、待确认的备份文件。恢复不可撤销，分两步（选文件 → 确认框展示文件名和条数）防点错。
    // 纯交互中间态，进程被杀后重置为"无待确认文件"是正确行为，不进 ViewModel。
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }

    // CreateDocument 拉起系统"保存到"面板；参数是建议文件名，带日期避免同目录多次导出互相覆盖。
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        // uri 为 null 表示用户取消了，什么都不做
        if (uri != null) viewModel.exportBackup(uri)
    }

    // 恢复第一步：选文件。MIME 用通配符而非 application/json：很多文件管理器（尤其网盘）
    // 把 .json 报成 octet-stream/text/plain，限死会"文件在那里却选不了"；真实校验交给 BackupCodec。
    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) pendingRestoreUri = uri
    }

    // 一次性事件标准三步：副作用须放在 LaunchedEffect 里；以 state.message 为 key，变化才执行；
    // 弹完必须 consumeMessage 清掉，否则 message 停在旧文案、key 不再变化，下次保存成功就不弹了。
    LaunchedEffect(state.message) {
        state.message?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    /** 数据还没读进来 → 转圈 + 提前返回。和课表页、加课页的写法一致。 */
    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    Column(Modifier.fillMaxSize()) {
        // 无常驻的顶部保存按钮，左对齐即可，不需要 SpaceBetween 撑开两侧。
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("返回") }
            Text("设置", fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }

        // weight(1f) 拿走剩余高度：顶部栏固定，表单区可滚。
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // ---------- 学期 ----------
            SectionTitle("学期")
            VerticalSpacer(6)
            // 三张卡片对应三个分区，形成视觉分组。
            CardShell {
                Column {
                    OutlinedTextField(
                        value = state.name,
                        onValueChange = viewModel::onNameChange,
                        label = { Text("学期名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    VerticalSpacer(8)
                    // 手动输入框：有精确日期时直接填/粘比选择器快；只改文本，解析留到保存时校验。
                    // 标签点明"第 1 周的周一"：字段含义不是开学典礼那天，填错整学期周次都会偏。
                    OutlinedTextField(
                        value = state.startDateText,
                        onValueChange = viewModel::onStartDateChange,
                        label = { Text("开学日期（第 1 周的周一）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    VerticalSpacer(6)
                    OutlinedButton(
                        onClick = { showDatePicker = true },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 12.dp,
                            vertical = 4.dp
                        )
                    ) { Text("选择日期", fontSize = 13.sp) }

                    VerticalSpacer(10)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("总周数", fontSize = 13.sp)
                        // 上界 30 是务实值（含考试周可到 22 周）；范围不能取 totalWeeks 自身——组件正在改它。
                        NumberStepper(
                            value = state.totalWeeks,
                            range = 1..30,
                            suffix = " 周",
                            onValueChange = viewModel::onTotalWeeksChange
                        )
                    }

                    VerticalSpacer(6)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 文字用 weight(1f) 占住剩余宽度，开关固定宽度被推到最右。
                        Column(Modifier.weight(1f)) {
                            Text("手动指定当前周", fontSize = 13.sp)
                            // 开关本身不传达语义，必须写明开启后的效果与使用场景。
                            HintText("假期调课、补课时用；开启后第 N 周完全由你说了算")
                        }
                        Switch(
                            checked = state.overrideEnabled,
                            onCheckedChange = viewModel::onOverrideEnabledChange
                        )
                    }
                    // 仅开关打开时渲染（条件为 false 时根本不进组合树）。
                    // range 用当前 totalWeeks：改周数后上界立刻跟随；越界值由 onTotalWeeksChange 的 coerceIn 兜住。
                    if (state.overrideEnabled) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("当前是", fontSize = 13.sp)
                            NumberStepper(
                                value = state.overrideWeek,
                                range = 1..state.totalWeeks,
                                suffix = " 周",
                                onValueChange = viewModel::onOverrideWeekChange
                            )
                        }
                    }
                }
            }

            VerticalSpacer(20)

            // ---------- 作息 ----------
            // 作息默认只读、点「修改」才可编辑：本页高频动作是看日期/周次，
            // 12 行可点时间块在滚动页里易误触且误改不易察觉，加一道闸门。
            // 编辑改动只存 ViewModel 内存 draft，点底部「保存」才落库，与其它字段同一模型。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle("节次作息（第几节几点上）")
                TextButton(onClick = { editingPeriods = !editingPeriods }) {
                    Text(if (editingPeriods) "完成" else "修改", fontSize = 12.sp)
                }
            }
            CardShell {
                Column {
                    if (editingPeriods) {
                        HintText("点某一行改开始时间，点结束时间可单独改；改完记得点底部「保存」。")
                        VerticalSpacer(6)
                    }

                    // 不用 forEachIndexed：每行操作用 draft.section 定位（PeriodDraft 自带唯一编号），无需下标。
                    state.periods.forEach { draft ->
                        // 编辑态整行可点，打开开始时间选择器——用户脑中记的是该节的上课时间。
                        // 内层 TimeChip 位置更靠内、先消费点击，点结束胶囊不会误触外层。
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (editingPeriods) {
                                        Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { timePickerTarget = draft.section to true }
                                    } else {
                                        Modifier
                                    }
                                )
                                .padding(vertical = if (editingPeriods) 7.dp else 5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("第 ${draft.section} 节", fontSize = 13.sp)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (editingPeriods) {
                                    TimeChip(draft.startText) { timePickerTarget = draft.section to true }
                                    Text("—", fontSize = 12.sp)
                                    TimeChip(draft.endText) { timePickerTarget = draft.section to false }
                                } else {
                                    /** 只读态：纯文本，没有底色和点击区，扫一眼就走。 */
                                    Text(
                                        text = draft.startText,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "—",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = draft.endText,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    // 加/删一节只在编辑态出现：只读态"看得见点不了"别扭，可点又破坏只读承诺。
                    // 「删一节」只删最后一节：中间删会留断号（1、2、4），节次必须连续，见 removeLastPeriod。
                    if (editingPeriods) {
                        VerticalSpacer(6)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = viewModel::removeLastPeriod) {
                                Text("删一节", fontSize = 12.sp)
                            }
                            TextButton(onClick = viewModel::addPeriod) {
                                Text("＋ 加一节", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            VerticalSpacer(20)

            // ---------- 数据 ----------
            SectionTitle("数据")
            VerticalSpacer(6)
            CardShell {
                Column {
                    // 三个计数实时读自数据库（不是存下来的计数器），显示的一定是真实值，
                    // 也给用户"我的数据在哪"的确认。
                    HintText("课程 ${state.courseCount} 门 · 便签 ${state.noteCount} 条 · 待办 ${state.todoCount} 条")
                    VerticalSpacer(6)
                    // 文案刻意长：数据只在本机、无联网权限（可在系统设置验证）、卸载会清空需备份。
                    // 不提示会导致真实数据丢失，这条必须有。
                    HintText("所有数据只存在这台手机上（Room 数据库），app 没有申请联网权限。卸载会清空数据，请定期导出备份。")

                    VerticalSpacer(10)

                    // OutlinedButton：本页主操作是底部"保存"，备份低频，视觉低一档。
                    // enabled = !backupBusy 防重复点击同时跑两个读写同一文件的协程。
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val today = TimeUtils.epochDayToText(LocalDate.now().toEpochDay())
                                exportLauncher.launch("课程表备份-$today.json")
                            },
                            enabled = !state.backupBusy,
                            modifier = Modifier.weight(1f)
                        ) { Text("导出备份", fontSize = 13.sp) }

                        OutlinedButton(
                            onClick = {
                                // "*/*" 的理由见 restoreLauncher 的注释
                                restoreLauncher.launch(arrayOf("*/*"))
                            },
                            enabled = !state.backupBusy,
                            modifier = Modifier.weight(1f)
                        ) { Text("从备份恢复", fontSize = 13.sp) }
                    }
                    if (state.backupBusy) {
                        VerticalSpacer(6)
                        HintText("正在处理备份文件…")
                    }
                }
            }

            VerticalSpacer(20)

            // ---------- 关于 ----------
            // 不联网 app 的权限与数据去向写在用户找得到的地方，比 README 有用。
            // 版本号取 BuildConfig.VERSION_NAME（与 versionName 同源），发版只改一处，不会对不上。
            SectionTitle("关于")
            VerticalSpacer(6)
            CardShell {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "课程表",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "v${BuildConfig.VERSION_NAME}",
                            modifier = Modifier.padding(start = 8.dp).weight(1f),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    VerticalSpacer(6)
                    HintText("没有联网权限，也没有申请任何其他权限。所有数据只保存在这台手机的本地数据库里，卸载会一并删除。")
                    VerticalSpacer(4)
                    HintText("换手机或重装前，用上方的「导出备份」存一份文件，到新设备再「从备份恢复」即可。")
                }
            }

            VerticalSpacer(20)
            // 页面主操作，用实心 Button（M3 约定一页通常只有一个实心按钮）。
            Button(
                onClick = viewModel::save,
                modifier = Modifier.fillMaxWidth()
            ) { Text("保存") }
            VerticalSpacer(32)
        }
    }

    // initialEpochDay 传 startEpochDay（最后成功解析的日期）而非 startDateText：
    // 两者故意不同步（编辑期宽松、保存时严格），文本可能是 "abc" 这类垃圾，
    // 用 startEpochDay 才能正常弹出并定位到上次的有效日期。
    if (showDatePicker) {
        DatePickerDialogM3(
            initialEpochDay = state.startEpochDay,
            onDismiss = { showDatePicker = false },
            onPicked = { epochDay ->
                viewModel.onStartDatePicked(epochDay)
                // 先写数据后关弹窗：onStartDatePicked 是同步内存操作，不会失败。
                showDatePicker = false
            }
        )
    }

    // 覆盖式导入的唯一一道闸门：写清文件名、会被替换的数据量、不可撤销。
    // 确认按钮用 error 色是破坏性操作的 Material 约定。
    pendingRestoreUri?.let { uri ->
        val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "所选文件"
        AlertDialog(
            onDismissRequest = { pendingRestoreUri = null },
            title = { Text("用备份覆盖当前数据？") },
            text = {
                Text(
                    "文件：$fileName\n\n" +
                        "当前的 ${state.courseCount} 门课程、${state.noteCount} 条便签、" +
                        "${state.todoCount} 条待办会被全部替换，且不能撤销。",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // 先关弹窗再启动导入：导入是异步的，弹窗留着会挡住结果提示
                        pendingRestoreUri = null
                        viewModel.importBackup(uri)
                    }
                ) {
                    Text("覆盖导入", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestoreUri = null }) { Text("取消") }
            }
        )
    }

    // 弹窗是异步的：确认前 state 可能已变，这里按 section 重查 draft 而非闭包捕获旧值。
    // 用 firstOrNull：该节若已被删（点过删一节）返回 null 而非抛异常。
    timePickerTarget?.let { (section, isStart) ->
        val draft = state.periods.firstOrNull { it.section == section }
        val initialText = if (isStart) draft?.startText else draft?.endText
        TimePickerDialogM3(
            // 解析失败（节已删或文本非法）兜底成 480（08:00）：手输文本可能是垃圾，不能强解。
            initialMinutes = initialText?.let { TimeUtils.textToMinutes(it) } ?: 480,
            onDismiss = { timePickerTarget = null },
            onPicked = { minutes ->
                viewModel.onPeriodTimePicked(section, isStart, minutes)
                timePickerTarget = null
            }
        )
    }
}

/**
 * 可点击的时间标签。
 * Modifier 链顺序关键：clip 必须在 background 前，否则背景是直角矩形；
 * clickable 必须在 padding 前，让整个圆角色块可点而不只是文字。
 */
@Composable
private fun TimeChip(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
