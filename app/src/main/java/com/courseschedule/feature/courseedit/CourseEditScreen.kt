package com.courseschedule.feature.courseedit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.courseschedule.data.entity.WeekType
import com.courseschedule.ui.CardShell
import com.courseschedule.ui.ChoiceChips
import com.courseschedule.ui.ColorDots
import com.courseschedule.ui.HintText
import com.courseschedule.ui.NumberStepper
import com.courseschedule.ui.SectionTitle
import com.courseschedule.ui.VerticalSpacer
import com.courseschedule.ui.appViewModel

/**
 * 加课 / 改课页：基础信息 + 颜色 + 任意多个时间段的表单。
 * 新增（`0L`）和编辑共用这套表单，只做少量条件分支，避免把表单代码复制两份。
 *
 * @param courseId 要编辑的课程 id，`0L` 表示新增，由导航参数解出。
 * @param presetDay 从课表格子点进来时预填的星期几（1-7）。
 * @param presetSection 预填的起始节次。
 * @param onBack 保存成功、删除成功或"没找到"点返回时回到上一页。
 */
@Composable
fun CourseEditScreen(
    courseId: Long,
    presetDay: Int,
    presetSection: Int,
    onBack: () -> Unit
) {
    // appViewModel 内部是 ViewModelFactory 封装，把参数闭包进构造。
    // ViewModel 只在首次创建时构造、之后复用缓存实例，所以编辑路由必须带上 courseId：
    // 换一门课就是新的导航入口、新的 ViewModel。
    val viewModel = appViewModel { CourseEditViewModel(it.repository, courseId, presetDay, presetSection) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 删除确认弹窗的开关。这个状态丢了不造成数据损失，放界面层即可；表单内容才需要放 ViewModel 存活重建。
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // finished 是一次性事件：key 变 true 时执行一次 onBack，之后重组 key 不变不会重跑，避免连续返回多页。
    // 导航是副作用，必须在 LaunchedEffect 里做，不能在组合期间直接调 onBack()。
    LaunchedEffect(state.finished) {
        if (state.finished) onBack()
    }

    // 两种"页面未就绪"的情况用提前返回处理，主界面代码不必嵌套在 else 里。
    when {
        state.loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return
        }

        // 显示"没找到"而不是空表单：空表单会让用户以为在编辑，填完保存却新建了一门课。
        state.notFound -> {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("没找到这门课", modifier = Modifier.padding(24.dp))
                TextButton(onClick = onBack) { Text("返回") }
            }
            return
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 顶部操作栏：返回 / 标题 / 保存。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("返回") }
            Text(
                text = if (state.isNew) "新增课程" else "编辑课程",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            TextButton(onClick = viewModel::save) {
                Text("保存", color = MaterialTheme.colorScheme.primary)
            }
        }

        // 错误提示：有 error 才渲染这行红字，无错误整块跳过。
        state.error?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        // 表单主体可滚动。weight(1f) 拿走剩余高度，小屏上表单能滚而顶部按钮常驻；
        // padding 放 verticalScroll 之后，否则滚动区域会把内边距也包进去。
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            SectionTitle("基本信息")
            // VerticalSpacer 是项目自带的占位组件，比把间距混进 padding 更直观。
            VerticalSpacer(8)
            // 课程名是唯一必填项，保存时校验；备注允许多行，所以不开 singleLine。
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::onNameChange,
                label = { Text("课程名（必填）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            VerticalSpacer(8)
            OutlinedTextField(
                value = state.teacher,
                onValueChange = viewModel::onTeacherChange,
                label = { Text("教师") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            VerticalSpacer(8)
            OutlinedTextField(
                value = state.location,
                onValueChange = viewModel::onLocationChange,
                label = { Text("默认地点") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            VerticalSpacer(8)
            OutlinedTextField(
                value = state.note,
                onValueChange = viewModel::onNoteChange,
                label = { Text("备注") },
                modifier = Modifier.fillMaxWidth()
            )
            VerticalSpacer(12)
            SectionTitle("课表颜色")
            VerticalSpacer(8)
            ColorDots(selectedIndex = state.colorIndex, onSelect = viewModel::onColorChange)

            VerticalSpacer(20)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle("时间段（一门课可以有多个）")
                TextButton(onClick = viewModel::addSlot) { Text("＋ 添加", fontSize = 13.sp) }
            }
            VerticalSpacer(4)

            // 把下标闭包进 onSlotChange 定位到具体草稿；界面只描述"怎么改"，数据只在 ViewModel 改。
            state.slots.forEachIndexed { index, slot ->
                SlotCard(
                    index = index,
                    slot = slot,
                    totalWeeks = state.totalWeeks,
                    onSlotChange = { transform -> viewModel.onSlotChange(index, transform) },
                    onRemove = { viewModel.removeSlot(index) }
                )
                VerticalSpacer(8)
            }

            // 删除按钮只在编辑模式出现。物理删除不可撤销，先弹确认框，确认这一步放在界面层。
            if (!state.isNew) {
                VerticalSpacer(12)
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("删除这门课", color = MaterialTheme.colorScheme.error) }
            }
            // 底部留白，避免按钮贴着手势导航条容易误触。
            VerticalSpacer(32)
        }
    }

    // 删除确认弹窗：文案带课程名并提示时间段会级联删除（course_slot 为 ON DELETE CASCADE），减少误删。
    // 它是浮层、不参与布局，所以直接放在函数末尾。
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除课程") },
            text = { Text("「${state.name}」的所有时间段会一起删掉，确定？") },
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

/**
 * 一个时间段的编辑卡片，在列表里渲染 N 次。
 *
 * @param index 仅用于显示"时间段 N"；数据定位靠调用处把下标闭包进 onSlotChange。
 * @param totalWeeks 学期总周数，给周次步进器定上界。
 * @param onSlotChange 接收一个"改法"（函数）而不是新值，加字段时组件和 ViewModel 都不用加方法。
 * @param onRemove 删掉这一条。允许删到 0 个时段，保存时由 save 校验拦截。
 */
@Composable
private fun SlotCard(
    index: Int,
    slot: SlotDraft,
    totalWeeks: Int,
    onSlotChange: ((SlotDraft) -> SlotDraft) -> Unit,
    onRemove: () -> Unit
) {
    CardShell {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                /** `index + 1` 因为下标从 0 开始，但用户看到的是"第 1 个"。 */
                Text("时间段 ${index + 1}", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                TextButton(onClick = onRemove) {
                    Text("删除", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }

            HintText("星期")
            // ChoiceChips 是泛型组件，不关心 Int 的含义；下面选单双周复用同一组件。
            ChoiceChips(
                options = (1..7).toList(),
                selected = slot.dayOfWeek,
                labelOf = { dayOfWeekShort(it) },
                onSelect = { day -> onSlotChange { it.copy(dayOfWeek = day) } }
            )

            VerticalSpacer(6)
            HintText("节次")
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 起止节次双向联动：调大起始时把结束顶上去、调小结束时把起始拉下来，
                // 界面上不会出现"起始 > 结束"。联动只是体验优化，save 里的 minOf/maxOf 纠正才是数据正确性的保证。
                NumberStepper(
                    value = slot.startSection,
                    range = 1..MAX_SECTION,
                    onValueChange = { value ->
                        onSlotChange {
                            it.copy(
                                startSection = value,
                                endSection = if (it.endSection < value) value else it.endSection
                            )
                        }
                    }
                )
                Text("—", fontSize = 13.sp)
                NumberStepper(
                    value = slot.endSection,
                    range = 1..MAX_SECTION,
                    onValueChange = { value ->
                        onSlotChange {
                            it.copy(
                                endSection = value,
                                startSection = if (it.startSection > value) value else it.startSection
                            )
                        }
                    }
                )
                Text("节", fontSize = 13.sp)
            }

            VerticalSpacer(6)
            HintText("周次范围")
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 周次联动与节次相同，只是上界换成一学期总周数。
                NumberStepper(
                    value = slot.startWeek,
                    range = 1..totalWeeks,
                    suffix = " 周",
                    onValueChange = { value ->
                        onSlotChange {
                            it.copy(
                                startWeek = value,
                                endWeek = if (it.endWeek < value) value else it.endWeek
                            )
                        }
                    }
                )
                Text("—", fontSize = 13.sp)
                NumberStepper(
                    value = slot.endWeek,
                    range = 1..totalWeeks,
                    suffix = " 周",
                    onValueChange = { value ->
                        onSlotChange {
                            it.copy(
                                endWeek = value,
                                startWeek = if (it.startWeek > value) value else it.startWeek
                            )
                        }
                    }
                )
            }

            VerticalSpacer(6)
            HintText("单双周")
            // 与上面选星期是同一泛型组件的复用，只换了 labelOf。
            ChoiceChips(
                options = WeekType.entries.toList(),
                selected = slot.weekType,
                labelOf = { weekTypeLabel(it) },
                onSelect = { type -> onSlotChange { it.copy(weekType = type) } }
            )

            VerticalSpacer(6)
            // 地点覆盖允许留空，课表渲染时由 PlacedCourse 的 ifBlank 回退到课程默认地点。
            OutlinedTextField(
                value = slot.roomOverride,
                onValueChange = { value -> onSlotChange { it.copy(roomOverride = value) } },
                label = { Text("地点覆盖（留空用课程默认地点）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 星期数字 → 单字简称（选项卡空间小）。
 * 与课表页的 `weekDayName` 故意不复用：那边返回"周一"（表头用），这边返回"一"，文案需求不同。
 * coerceIn 是下标保护，越界时夹回合法范围而不是崩。
 */
private fun dayOfWeekShort(day: Int): String =
    listOf("一", "二", "三", "四", "五", "六", "日")[(day - 1).coerceIn(0, 6)]

/**
 * `WeekType` → 中文标签。文案属于 UI 层，不放数据层；
 * when 不带 else，新增枚举值时这里编译报错兜底，防止漏掉文案。
 */
private fun weekTypeLabel(type: WeekType): String = when (type) {
    WeekType.ALL -> "每周"
    WeekType.ODD -> "单周"
    WeekType.EVEN -> "双周"
}
