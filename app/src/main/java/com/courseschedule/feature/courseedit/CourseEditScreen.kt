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
 * 加课 / 改课页。一个表单：基础信息 + 颜色 + 任意多个时间段。
 *
 * ## 这个页面同时承担"新增"和"编辑"两种模式
 *
 * 靠 [CourseEditViewModel] 的 `courseId` 区分：`0L` 是新增，其他是编辑。
 * 所以标题要在"新增课程"/"编辑课程"之间切换，删除按钮只在编辑模式显示。
 *
 * 为什么不拆成两个页面：两者 95% 的界面是同一套表单，
 * 拆开会把这份表单代码复制两份，改一个字段要改两处。
 * **共用表单 + 少量条件分支**在这里明显更划算。
 *
 * @param courseId 要编辑的课程 id，`0L` 表示新增。
 *   由 [com.courseschedule.MainActivity] 从导航参数里解出来。
 * @param presetDay 从课表格子点进来时预填的星期几（1-7）。
 * @param presetSection 预填的起始节次。
 * @param onBack 返回上一页。保存成功、删除成功、"没找到"点返回都走它。
 */
@Composable
fun CourseEditScreen(
    courseId: Long,
    presetDay: Int,
    presetSection: Int,
    onBack: () -> Unit
) {
    /**
     * 注意这里把三个参数都传给了 ViewModel。
     *
     * ## ViewModel 拿构造函数参数有个坑，这里正好绕开了
     *
     * Android 官方的 `ViewModel` 默认只能无参构造（因为框架要负责重建它）。
     * 要传参数得用 `ViewModelProvider.Factory`。
     * 本项目的 [appViewModel] 就是干这个的——它内部用了
     * `viewModelFactory { initializer { ... } }`，把参数闭包进去。
     *
     * 但要注意一个**实际影响**：ViewModel 只在第一次创建时构造，
     * 之后重建（转屏）会拿**缓存的实例**。
     * 所以如果 `courseId` 变了（从课表页跳到另一门课），
     * 必须保证这是一次"新的导航"、会创建新的 ViewModel。
     * 本项目靠的是导航路由参数不同 → 新的 NavBackStackEntry → 新的 ViewModel。
     * 这也是为什么 [com.courseschedule.MainActivity] 里
     * 编辑路由带上了 `courseId` 作为路径参数。
     */
    val viewModel = appViewModel { CourseEditViewModel(it, courseId, presetDay, presetSection) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    /**
     * 删除确认弹窗的显示状态。
     *
     * ## 为什么它用 `remember` 存在界面里，而不是放进 ViewModel
     *
     * 因为它**不需要在界面重建后存活**。用户弹出确认框、转个屏，
     * 弹窗关掉是完全合理的行为（他重新点一下就行）。
     *
     * 判断标准是：**这个状态丢了会不会造成数据损失或明显困扰？**
     * - "用户填了一半的课程名" → 丢了很烦 → 必须放 ViewModel；
     * - "确认框开着没" → 丢了无所谓 → 放界面里更简单。
     *
     * 另一个理由是：ViewModel 里只该放"业务状态"，
     * 而"某个弹窗开着"纯粹是展示细节。
     *
     * ## `var ... by remember { mutableStateOf(false) }` 是标准三件套
     *
     * 1. `mutableStateOf(false)` 创建一个 Compose 能观察的**可变状态**，
     *    初始值 false；
     * 2. `remember { }` 让它在**重组之间保持**——不 remember 的话，
     *    每次重组都会新建一个 `false`，弹窗永远打不开；
     * 3. `by` 是委托，让它能像普通 `var` 一样读写。
     *
     * 合起来：「一个会触发重绘、且能在重组间存活的局部变量」。
     * **`remember` 只能在 `@Composable` 里用**，这是它和 ViewModel 字段的主要区别。
     */
    var showDeleteConfirm by remember { mutableStateOf(false) }

    /**
     * 监听 `finished`，为 true 时返回上一页。
     *
     * ## 为什么必须用 `LaunchedEffect` 而不是直接写 `if (...) onBack()`
     *
     * 因为**导航是副作用**，不能直接在组合过程中做。
     * `@Composable` 函数体理论上应该只"描述界面"，
     * 在里面调 `onBack()` 会导致"重组期间修改状态"，
     * Compose 会抛异常或产生难以预测的循环重组。
     *
     * `LaunchedEffect` 的正确语义是：**当 key 变化时，执行一次这段副作用**。
     * 这里的 key 是 `state.finished`：
     * - 初始 false → 不执行；
     * - 变成 true → 执行一次，调 `onBack()`；
     * - 之后如果重组但 `finished` 还是 true → **不会重复执行**
     *   （因为 key 没变）。
     *
     * 这个"key 不变就不重跑"的行为很关键：如果每次重组都调
     * `onBack()`，会连续弹出好几页。
     *
     * ## LaunchedEffect 和 remember 的关系
     *
     * 它内部也是一个协程作用域，随这个 Composable 离开组合而被取消——
     * 所以长期任务（比如轮询）也应该写在这里，退出页面会自动停。
     */
    LaunchedEffect(state.finished) {
        if (state.finished) onBack()
    }

    /**
     * ## 两种"页面还没准备好"的情况，都用 `when` + `return` 处理
     *
     * `when { }` 不带参数时是"逐个判断条件，命中第一个为 true 的分支"，
     * 相当于 C++ 里一串 `if / else if`。
     *
     * 为什么每一支里都写 `return`：因为这是**提前返回模式**——
     * 把"异常情况"处理完就退出，让下面那段真正的主界面代码
     * 不用缩进在任何 `if` 里面。这比 `if (loading) { ... } else { ... }`
     * 套一大层的可读性好得多，尤其是主界面有 100 行的时候。
     *
     * 注意 `when` 的两个分支**都不写 `else`**——因为下面还有代码，
     * 如果写成 `else` 反而会让人以为"不加载就是没找到"。
     */
    when {
        state.loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return
        }

        /**
         * `notFound` 是一个**真实的异常情况**，值得单独一个界面：
         * 用户可能从课表点进一门课，而这门课在另一个入口被删了
         * （或者数据被外部改过）。
         *
         * 显示"没找到"而不是"一个空表单"很重要——空表单会让用户
         * 以为在编辑，填完点保存却**新建了一门课**，这才是真正的问题。
         */
        state.notFound -> {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("没找到这门课", modifier = Modifier.padding(24.dp))
                TextButton(onClick = onBack) { Text("返回") }
            }
            return
        }
    }

    Column(Modifier.fillMaxSize()) {
        /**
         * 顶部操作栏：返回 / 标题 / 保存。
         *
         * 用 `SpaceBetween` 把三个元素摊开成"左中右"。
         * 注意标题其实不完全居中（左右两个按钮宽度不同），
         * 但视觉上够用了——要做严格居中得用 `Box` 叠放两个 `Row`，
         * 不值得为这点差异加复杂度。
         */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("返回") }
            Text(
                /**
                 * 标题随模式切换。`if` 表达式在 Kotlin 里是**有返回值的**，
                 * 所以可以直接赋给 `text` 参数——不需要写成
                 * `String title; if (...) title = ...; else title = ...;`
                 */
                text = if (state.isNew) "新增课程" else "编辑课程",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            /**
             * `viewModel::save` 是**方法引用**，等价于 `{ viewModel.save() }`。
             * 对应 C++ 的 `&ViewModel::save` 配 `std::bind`，
             * 或者 C# 的方法组转换。Kotlin 里写法最短。
             *
             * 用方法引用而不是 lambda 的好处是很小的（可读性），
             * 但如果方法名能直接表达意图，用它比写 `{ viewModel.save() }` 更干净。
             */
            TextButton(onClick = viewModel::save) {
                Text("保存", color = MaterialTheme.colorScheme.primary)
            }
        }

        /**
         * 错误提示。
         *
         * `state.error?.let { message -> ... }` 是"安全调用 + 作用域函数"，
         * 读作：**如果有错误，就执行这段渲染**；没有就整块跳过。
         *
         * 这里 `let` 里没有用 `it` 而是显式命名成 `message`，
         * 因为 `it` 在这种"唯一参数是要显示的内容"的场景下不够清楚。
         */
        state.error?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        /**
         * 表单主体，可以上下滚动。
         *
         * `weight(1f)` 拿走顶部操作栏和错误提示之外的剩余高度，
         * 这样小屏手机上表单能滚，而"保存"按钮永远在顶部可见。
         *
         * `verticalScroll(rememberScrollState())` 是 Compose 里最简单
         * 的滚动方案。`rememberScrollState()` 必须在 remember 里，
         * 否则每次重组滚动位置都会归零。
         *
         * 注意**它和 `weight` 的顺序**：
         * `weight` 是布局类修饰符在前，`verticalScroll` 也是布局类，
         * `padding` 放最后。如果顺序反了（比如 padding 在最前），
         * 滚动区域会比预期大一圈，内容能滚到内边距里面去。
         */
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            SectionTitle("基本信息")
            /**
             * [VerticalSpacer] 是本项目自己写的占位组件（Compose 没有
             * 内置的"间隙"组件）。也可以直接用 `Modifier.padding`，
             * 但那样会把间距和内边距混在一起，用显式的 Spacer 更容易调整。
             */
            VerticalSpacer(8)
            /**
             * ## 受控文本框：Compose 和 Qt 最大的手感差别之一
             *
             * `OutlinedTextField` 的显示内容**完全由 `value` 决定**。
             * 用户敲一个字符 → `onValueChange` 带上新字符串触发 →
             * ViewModel 更新状态 → 重组 → 这里拿到新的 `value` → 显示出来。
             *
             * 对比 Qt 的 `QLineEdit`：控件自己存文字，你 `text()` 去读。
             * Compose 这边**状态在你手里**，所以：
             * - 想限制输入（只允许数字、只允许大写）只要在 `onValueChange`
             *   里把收到的值过滤一遍再存；
             * - 想程序化改内容（清空、预填）只要改状态。
             *
             * 代价是**每次敲键都会触发一次重组**。对单行文本框来说开销可以忽略；
             * 但如果表单有几百个控件、且每次重组都很重，就会卡——
             * 那时候要把状态"下沉"到更小的组件里，让重组范围变小。
             *
             * `singleLine = true` 禁止换行和回车。课程名、教师这类短文本都该开。
             * 注意"备注"那一项**没开** `singleLine`，因为它允许写多行。
             */
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
            /**
             * 颜色选择。这是一个**无状态组件**——它自己不存"选中了哪个"，
             * 全靠外面传 `selectedIndex` 进来、`onSelect` 报出去。
             *
             * 这种"状态完全由父级持有"的组件叫**受控组件**，是 Compose 的推荐写法。
             * 好处是父级（ViewModel）始终知道选中了什么，保存时直接读就行，
             * 不用去问子组件。
             */
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

            /**
             * ## `forEachIndexed` + 传 lambda 的完整链路
             *
             * 这一段是 Kotlin 函数式风格和 Compose 状态管理结合得比较紧密的地方，
             * 值得逐层看一遍：
             *
             * ```
             * state.slots.forEachIndexed { index, slot ->   // 遍历草稿列表
             *     SlotCard(
             *         onSlotChange = { transform ->          // SlotCard 报出"我要改"
             *             viewModel.onSlotChange(index, transform)   // 带上是第几条
             *         },
             *         ...
             *     )
             * }
             * ```
             *
             * 到了 [SlotCard] 内部，用户点"周三"时是这样调用的：
             * `onSlotChange { it.copy(dayOfWeek = 3) }`
             *
             * 那个 `{ it.copy(...) }` 就是传到 ViewModel 的 `transform`。
             * 于是整个链路是：
             * **点周三 → 造一个"把 dayOfWeek 改成 3"的函数 → 传上去 →
             * ViewModel 用它生成新草稿 → 新状态 → 重组 → 界面更新**。
             *
             * 关键点在于：**界面从来不直接改数据，只描述"怎么改"**。
             * 数据永远只有 ViewModel 一个地方在改，这保证了状态不会被改乱。
             */
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

            /**
             * 删除按钮只在编辑已有课程时出现。
             * 新增模式下没有东西可删（那门课还不存在）。
             *
             * 注意它**没有**直接调 `viewModel.delete()`，而是先打开确认弹窗。
             * 物理删除无法撤销，这一步不能省。
             */
            if (!state.isNew) {
                VerticalSpacer(12)
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("删除这门课", color = MaterialTheme.colorScheme.error) }
            }
            /**
             * 底部留白 32dp。作用是让表单最后一项不要紧贴屏幕底边——
             * 不然在带手势导航的手机上，"删除这门课"按钮会离底部横条太近，
             * 容易误触。这是移动端布局的常见处理。
             */
            VerticalSpacer(32)
        }
    }

    /**
     * 删除确认弹窗。
     *
     * ## 为什么直接写在函数末尾，而不是包在 `if` 里的 `Column` 里
     *
     * 因为 `AlertDialog` 自己就是一个"浮层"——它不参与父容器的布局，
     * 而是画在整个界面之上（用的是 Android 的 `Dialog` 窗口）。
     * 所以它放在函数里的哪个位置无所谓，最后写最清楚。
     *
     * ## `text` 里带上课程名的原因
     *
     * "「高等数学」的所有时间段会一起删掉，确定？"比
     * "确定要删除吗？"有用得多——用户可能点错了课，
     * 看到课程名就能立刻发现。
     *
     * 顺便这句话也**明确提示了级联删除**：因为数据库上
     * `course_slot` 是 `ON DELETE CASCADE`，删课程会连带删掉时间段。
     * 用户知道这一点才不会以为"删了还能恢复时间段"。
     */
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除课程") },
            text = { Text("「${state.name}」的所有时间段会一起删掉，确定？") },
            confirmButton = {
                TextButton(onClick = {
                    /**
                     * 先关弹窗，再删。顺序不能反——
                     * 虽然这里不会出错（delete 是异步的），
                     * 但"先关界面再动数据"是更安全的习惯：
                     * 万一删除过程中出现问题，用户至少不会看着一个卡住的弹窗。
                     */
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
 * 一个时间段的编辑卡片。
 *
 * ## 为什么拆成独立组件
 *
 * 因为它在列表里被渲染 N 次。如果直接内联在 `forEachIndexed` 里，
 * 那段代码要缩进 5 层（Column → forEach → Card → Column → Row），
 * 可读性会很差。抽出来后主函数短了很多，而且这个组件能单独预览。
 *
 * ## 参数里的 `index` 有什么用
 *
 * 只用来显示"时间段 1 / 2 / 3"这个标题，**不用来定位数据**——
 * 定位靠的是把 `index` 闭包进 `onSlotChange`（在调用处做的）。
 *
 * 这样可以顺手做一个小加分项：等以后想做"最后一个时段不能删"时，
 * 这里能拿到 index 判断。目前没做（允许删到 0 个，
 * 由保存时的校验兜底）。
 *
 * @param index 在列表里的下标，从 0 开始。界面上显示的是 `index + 1`。
 * @param slot 这一条的草稿数据。
 * @param totalWeeks 学期总周数，用来给"周次范围"的步进器定上界。
 * @param onSlotChange **接收一个"改法"**，不是接收新值。
 *   这种"传函数"的设计让这个组件完全不需要知道"改哪个字段"，
 *   只需要在用户操作时描述意图。理由详见
 *   [CourseEditViewModel.onSlotChange]。
 * @param onRemove 删掉这一条。
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
            /**
             * `(1..7).toList()` —— `1..7` 在 Kotlin 里是 `IntRange`（区间），
             * 不是列表。`ChoiceChips` 要的是 `List<T>`，所以转一下。
             *
             * `selected = slot.dayOfWeek` 传的是**当前值**，
             * `labelOf` 负责把数字转成"一/二/三…"。
             * 这个设计的价值是 `ChoiceChips` 组件本身**不知道** `Int` 是什么含义，
             * 它只负责"从一堆 T 里选一个"。所以同一个组件在下面
             * 又被用来选 [WeekType]，完全不用改。
             */
            ChoiceChips(
                options = (1..7).toList(),
                selected = slot.dayOfWeek,
                labelOf = { dayOfWeekShort(it) },
                onSelect = { day -> onSlotChange { it.copy(dayOfWeek = day) } }
            )

            VerticalSpacer(6)
            HintText("节次")
            Row(verticalAlignment = Alignment.CenterVertically) {
                /**
                 * ## 起止节次的联动逻辑
                 *
                 * 用户把**起始**节次调大时，如果超过了当前的结束节次，
                 * 就把结束节次**一起顶上去**。
                 *
                 * ```
                 * startSection = value,
                 * endSection = if (it.endSection < value) value else it.endSection
                 * ```
                 *
                 * 为什么这么做：用户在界面上看到"第 5 节 — 第 2 节"
                 * 会觉得很困惑（虽然 [CourseEditViewModel.save] 里有兜底纠正）。
                 * 联动之后，他调起始节次时结束节次会跟着走，
                 * 界面上永远是"第 5 节 — 第 5 节"，语义清楚。
                 *
                 * ## 为什么反过来（把结束节次调小）也要联动
                 *
                 * 看第二个 `NumberStepper`：把**结束**节次调到比起始还小时，
                 * 把起始节次拉下来。两个方向都处理，界面上就不会出现
                 * "起始 > 结束"的显示。
                 *
                 * 注意这两处联动**只是界面友好**，[CourseEditViewModel.save]
                 * 里的 `minOf / maxOf` 才是数据正确性的保证。
                 * 两层都要有：界面这层让用户看得舒服，数据那层保证不出脏数据。
                 */
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
                /**
                 * 周次的联动逻辑和节次完全一样，只是范围上界换成了 [totalWeeks]
                 * （学期总周数），而不是固定的 [MAX_SECTION]。
                 *
                 * `suffix = " 周"` 让步进器显示成"3 周"而不是光秃秃的"3"，
                 * 这是 [com.courseschedule.ui.NumberStepper] 提供的可选参数。
                 */
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
            /**
             * 这里就是 [com.courseschedule.ui.ChoiceChips] 泛型复用的地方：
             * 上面选星期用的是 `Int`，这里选单双周用的是 [WeekType]，
             * 调用方式几乎一样，只有 `labelOf` 换了个函数。
             *
             * `WeekType.entries.toList()` —— `entries` 是 Kotlin 1.9 引入的
             * 枚举标准属性（老代码里是 `values()`）。
             * `entries` 返回的列表是**不可变的**，比 `values()` 更安全
             * （`values()` 每次调用都新建数组，改它不会影响枚举本身，
             * 但容易让人误以为能改）。
             *
             * 顺序就是 [WeekType] 里的声明顺序：每周、单周、双周。
             * 这个顺序刚好符合直觉，所以没做额外排序。
             */
            ChoiceChips(
                options = WeekType.entries.toList(),
                selected = slot.weekType,
                labelOf = { weekTypeLabel(it) },
                onSelect = { type -> onSlotChange { it.copy(weekType = type) } }
            )

            VerticalSpacer(6)
            /**
             * 地点覆盖。
             *
             * 标签里那句"留空用课程默认地点"很重要——用户看到两个
             * 地点输入框（上面"默认地点"、这里"地点覆盖"）会困惑
             * 该填哪个。标签直接说清优先级，省掉一段说明文字。
             *
             * 这个字段允许留空，所以不校验。
             * [com.courseschedule.feature.schedule.PlacedCourse.room]
             * 里的 `ifBlank` 负责回退。
             */
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
 * 星期数字 → 单字简称（用于选项卡，空间不够放"周一"两个字）。
 *
 * 和 [com.courseschedule.feature.schedule.ScheduleScreen] 里的
 * `weekDayName` 是同一类东西，但**故意不复用**：
 * 那个返回"周一/周二"（表头用），这个返回"一/二"（选项卡用）。
 * 两种文案需求不同，强行合并会变成"传个参数决定要不要加'周'字"，
 * 反而更绕。
 *
 * `(day - 1).coerceIn(0, 6)` 是下标保护，越界时夹到合法范围而不是崩。
 */
private fun dayOfWeekShort(day: Int): String =
    listOf("一", "二", "三", "四", "五", "六", "日")[(day - 1).coerceIn(0, 6)]

/**
 * [WeekType] → 中文标签。
 *
 * 用 `when` 而不是给枚举加个 `label` 属性，是因为：
 * **展示文案不该放在数据层**。[WeekType] 在 `data.entity` 包里，
 * 如果那里写中文，以后想加英文界面就得改数据层——
 * 而文案是"这个界面怎么显示"的问题，属于 UI 层。
 *
 * `when` 不带 `else` 分支也**能编译过**，因为编译器知道
 * `WeekType` 只有三个值、三种情况都覆盖了。
 * 这是 Kotlin 的**穷尽性检查**：以后有人给 [WeekType] 加第四个值，
 * 这里会立刻**编译报错**提醒他"你漏了一个文案"。
 *
 * 这个特性在 C++ 的 `switch` 上是没有的（要自己开 `-Wswitch` 警告），
 * 是 Kotlin 帮你在重构时兜底的一个很好的例子。
 */
private fun weekTypeLabel(type: WeekType): String = when (type) {
    WeekType.ALL -> "每周"
    WeekType.ODD -> "单周"
    WeekType.EVEN -> "双周"
}
