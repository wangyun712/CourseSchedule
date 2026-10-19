package com.courseschedule.feature.settings

import android.widget.Toast
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.courseschedule.core.TimeUtils
import com.courseschedule.ui.CardShell
import com.courseschedule.ui.DatePickerDialogM3
import com.courseschedule.ui.HintText
import com.courseschedule.ui.NumberStepper
import com.courseschedule.ui.SectionTitle
import com.courseschedule.ui.TimePickerDialogM3
import com.courseschedule.ui.VerticalSpacer
import com.courseschedule.ui.appViewModel

/**
 * 设置页：学期信息 + 节次作息 + 数据概览。
 *
 * ## 这个页面有两种不同的反馈方式，注意区分
 *
 * - **校验失败、保存成功** → 走 Toast（[android.widget.Toast]），弹完就走；
 * - **表单内容** → 直接显示在输入框里，随打随变。
 *
 * 为什么校验失败也用 Toast 而不是像加课页那样在顶部显示一行红字？
 * 因为这个页面的"保存"按钮在最底下，而红字会出现在顶部——
 * 用户点了保存之后**眼睛还在底部**，看不到顶部的红字。
 * Toast 弹在屏幕中下方，正好在他的视线里。
 *
 * 这是个具体的可用性判断，不是什么通用规则。
 * 加课页之所以用红字，是因为它的错误（课程名为空）就发生在顶部那几个输入框附近。
 *
 * @param onBack 返回上一页。
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val viewModel = appViewModel { SettingsViewModel(it) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    /**
     * 取当前的 `Context`（Android 里访问系统能力、资源、弹窗都要它）。
     *
     * ## 为什么用 `LocalContext.current` 而不是把 context 当参数传下来
     *
     * Compose 里获取 `Context` 的标准方式就是这个。
     * `LocalContext` 是一个 `CompositionLocal`——可以理解成"沿着组合树
     * 向下自动传递的隐式参数"。它由最顶层（`setContent` 那一层）提供，
     * 下面任何组件都能取到，不用一路手动传。
     *
     * ## 这里有个容易踩的坑
     *
     * `LocalContext.current` 拿到的是**当前 Activity 的 Context**。
     * 如果把它存进一个长生命周期的对象（比如 ViewModel、单例），
     * 就会阻止这个 Activity 被回收 —— 这就是经典的 **Context 泄漏**。
     *
     * 正确做法是**只在 Composable 作用域内临时使用**。
     * 这里它只被下面那个 Toast 用，没有逃逸到任何地方，所以是安全的。
     *
     * 顺便注意 [com.courseschedule.ui.VmFactory] 里的取法是
     * `context.applicationContext` 而不是 `context` ——
     * 因为 Repository 是长生命周期的，必须拿 Application 级的 Context
     * （它和进程同寿，不会泄漏）。
     */
    val context = LocalContext.current

    /**
     * "选择日期"弹窗的开关。
     *
     * 和加课页的删除确认弹窗一样，这是纯展示状态，丢了无所谓，
     * 所以放在界面里用 `remember` 而不是放进 ViewModel。
     */
    var showDatePicker by remember { mutableStateOf(false) }

    /**
     * 当前正在用时间选择器编辑哪一节。
     *
     * ## 为什么用一个 `Pair` 而不是两个变量
     *
     * 因为"哪一节"和"是开始还是结束"必须**同时确定或同时为 null**。
     * 拆成两个变量就可能出现
     * `editingSection = 3, editingIsStart = null` 这种不一致的中间态。
     * 用 `Pair<Int, Boolean>?` 一个可空对象，要么全有要么全无，
     * 状态空间从 4 种降到 3 种，少了一整类 bug。
     *
     * 注释 `// Pair<节次, 是不是开始时间>` 就是在说明 `Boolean` 的含义——
     * 这正是前面 [SettingsViewModel.onPeriodTimePicked] 里
     * 提到的"布尔参数看不出含义"问题的**局部缓解办法**：
     * 在声明处写清楚，比在每个调用点猜要强。
     *
     * `mutableStateOf<Pair<Int, Boolean>?>(null)` 显式写了泛型参数，
     * 因为初始值是 `null`，不加泛型编译器推不出 `Pair` 那部分类型。
     */
    var timePickerTarget by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }

    /**
     * 把 ViewModel 的 `message` 弹成 Toast。
     *
     * ## 这段是"一次性事件"处理的标准模板
     *
     * ```
     * LaunchedEffect(state.message) {
     *     state.message?.let { message ->
     *         Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
     *         viewModel.consumeMessage()   // ← 弹完立刻清掉
     *     }
     * }
     * ```
     *
     * 三步缺一不可：
     *
     * 1. **`LaunchedEffect` 而不是直接调用**：弹 Toast 是副作用
     *    （它要跟系统服务打交道），不能写在组合过程里。
     * 2. **`state.message` 作为 key**：只在 message 变化时执行。
     *    如果随便写个 key，或者干脆没有 key（`LaunchedEffect(Unit)`），
     *    新的提示就不会弹了。
     * 3. **`consumeMessage()` 必须调**：清掉 message 让它变回 null。
     *    这样下次同一个提示（比如连点两次保存都成功）能再弹一次——
     *    因为 `null → "已保存"` 这个变化又发生了一次。
     *
     * 如果不调 `consumeMessage`，`message` 会一直停在"已保存"，
     * 用户再点保存时 key 没变化（还是同一个字符串），Toast 就不弹了。
     * **"点了没反应"是最难查的一类 bug。**
     *
     * ## 为什么用 `LENGTH_SHORT` 而不是自己控制时长
     *
     * Toast 的时长只有 `SHORT`(2s) 和 `LENGTH_LONG`(3.5s) 两个选项，
     * 不能自定义（这是 Android 的设计限制，防止应用用超长 Toast 骚扰用户）。
     * 这里的提示都是"已保存""格式不对"这种短句，SHORT 够用。
     */
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
        /**
         * 顶部栏：返回 + 标题。
         *
         * 注意这里的排列是"左对齐"（默认的 `Arrangement.Start`），
         * 和加课页那个 `SpaceBetween` 的三栏布局不同——
         * 因为这一页没有一个常驻的"保存"按钮（保存按钮在表单最底部），
         * 所以不需要把两侧撑开。
         */
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("返回") }
            Text("设置", fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }

        /**
         * 表单区，可滚动。`weight(1f)` 拿走剩余高度，
         * 让顶部栏固定、内容可滚。理由同加课页。
         */
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // ---------- 学期 ----------
            /**
             * 那两行 `// ---------- 学期 ----------` 是**分区注释**。
             *
             * 这一页有三块内容（学期 / 作息 / 数据），每块都挺长，
             * 用这种横线注释把边界画出来，找代码时能直接跳。
             * 这类注释在长函数里很有价值——比空行有效得多。
             *
             * 注意这是**普通行注释 `//` 而不是文档注释 `/** */`**：
             * 它描述的是"代码怎么分段"，不是"这个 API 是干什么的"，
             * 不需要进文档。项目里两种注释的这个分工是明确的。
             */
            SectionTitle("学期")
            VerticalSpacer(6)
            /**
             * 用 [CardShell] 把这一组输入框包起来，形成视觉分组。
             * 三个分区各用一张卡片，用户一眼能看出"这三行是一件事"。
             */
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
                    /**
                     * 开学日期。这是一个**手动输入框**，不是只读的展示。
                     *
                     * 为什么要允许手动输入，而不只提供日期选择器：
                     * 用户可能手上有"2026-09-01"这个精确日期要填，
                     * 用选择器翻到 9 月要点好几次。允许直接粘贴更快。
                     *
                     * 代价是必须处理非法输入 —— 所以
                     * [SettingsViewModel.onStartDateChange] 只改文本不改值，
                     * 真正解析在保存时做，失败会弹 Toast 提示格式。
                     *
                     * 标签里的"（第 1 周的周一）"是**必要的说明**：
                     * 这个字段的含义不是"开学典礼那天"，而是
                     * "第 1 周的周一"。填错了整学期的周次都会偏。
                     */
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
                        /**
                         * `androidx.compose.foundation.layout.PaddingValues` 这里
                         * 写了**完整包名**而不是加 import。原因是：
                         * 这个文件里有一个同名冲突的风险，而且只用到一次，
                         * 写全名比多一行 import 更省事。
                         *
                         * 严格说这是**代码风格上的小瑕疵**——
                         * 项目里其他地方的 `PaddingValues`（比如课表页）都是
                         * 正常 import 的。这里统一一下会更好，
                         * 但因为它完全合法、也不影响理解，先留着。
                         */
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
                        /**
                         * 范围写死 1..30。为什么不是 1..[Semester.totalWeeks]——
                         * 因为**这个组件就是在改 totalWeeks 本身**，
                         * 用它自己的值当范围会自相矛盾。
                         *
                         * 30 是个务实的上界：一学期超过 30 周已经不现实了，
                         * 但也不能设太小（有些学校含考试周能到 22 周）。
                         */
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
                        /**
                         * 左边是说明文字，用 `weight(1f)` 占住剩余宽度，
                         * 保证右边的 `Switch` 被推到最右。
                         *
                         * 如果不用 weight，`SpaceBetween` 也能把它们分开，
                         * 但文字长了之后可能把开关挤出去。
                         * `weight(1f)` 是"文字优先占位、开关固定宽度"的表达。
                         */
                        Column(Modifier.weight(1f)) {
                            Text("手动指定当前周", fontSize = 13.sp)
                            /**
                             * 这句说明文字**必须写**。
                             *
                             * "手动指定当前周"这七个字对用户来说完全不可解——
                             * 什么叫"当前周"？为什么要手动指定？
                             * 下面这句直接给出使用场景（假期调课、补课）
                             * 和效果（完全由你说了算），用户才知道该不该开。
                             *
                             * **凡是界面上有"开关"的地方，都要有一句话
                             * 说明"开了会发生什么"。** 开关本身不传达语义。
                             */
                            HintText("假期调课、补课时用；开启后第 N 周完全由你说了算")
                        }
                        Switch(
                            checked = state.overrideEnabled,
                            onCheckedChange = viewModel::onOverrideEnabledChange
                        )
                    }
                    /**
                     * "当前是第几周"的步进器**只在开关打开时显示**。
                     *
                     * 这是 Compose 条件渲染的直接用法：`if` 包住的组件
                     * 在条件为 false 时**根本不进入组合树、不参与布局**，
                     * 所以下面那个 `VerticalSpacer(6)` 在有它的时候才占位。
                     *
                     * ## 注意 `range = 1..state.totalWeeks`
                     *
                     * 这里用的是**当前状态里的总周数**，而不是写死的 30。
                     * 所以用户在下面把总周数从 20 改成 10 之后，
                     * 这个步进器的上界会立刻变成 10。
                     *
                     * 而 [SettingsViewModel.onTotalWeeksChange] 里那句
                     * `overrideWeek = it.overrideWeek.coerceIn(1, value)`
                     * 保证"改周数时这个值不会越界"——
                     * 界面限制新输入，ViewModel 修正已有值，两边配合。
                     */
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle("节次作息（第几节几点上）")
                /**
                 * 两个操作按钮并排。它们的顺序是"删一节"在前、"加一节"在后，
                 * 和直觉一致（减少在左、增加在右）。
                 *
                 * 注意"删一节"**不是删中间任意一节**，而是删最后一节——
                 * 理由见 [SettingsViewModel.removeLastPeriod] 的注释。
                 * 按钮文字用"删一节"而不是"删最后一节"，
                 * 因为后者太长会挤掉标题；如果用户点了发现删的是最后一节，
                 * 也符合预期（他通常就是加多了想撤销）。
                 */
                Row {
                    TextButton(onClick = viewModel::removeLastPeriod) { Text("删一节", fontSize = 12.sp) }
                    TextButton(onClick = viewModel::addPeriod) { Text("＋ 加一节", fontSize = 12.sp) }
                }
            }
            CardShell {
                Column {
                    /**
                     * 遍历渲染 12 行（默认）。每行是"第 N 节 [08:00] — [08:45]"。
                     *
                     * 这里**不用 `forEachIndexed`**，因为不需要下标——
                     * 每行操作时用的是 `draft.section` 而不是位置。
                     * 这和加课页那边不同（那边必须用下标，因为 SlotDraft 没有 id）。
                     * **用什么定位元素，取决于数据里有没有天然的唯一标识。**
                     */
                    state.periods.forEach { draft ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("第 ${draft.section} 节", fontSize = 13.sp)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                /**
                                 * ## 这里就是 `timePickerTarget` 被赋值的地方
                                 *
                                 * `draft.section to true` 用的是 Kotlin 的 `to`
                                 * 中缀函数，造一个 `Pair(section, true)`。
                                 * `true` 表示"正在编辑开始时间"。
                                 *
                                 * 因为是 `Pair<Int, Boolean>?` 类型，
                                 * 下面取值时会做**解构**：`val (section, isStart) = it`。
                                 * 这就是用 Pair 的额外好处——能直接拆开，
                可以读成"取出那一对"。
                                 */
                                TimeChip(draft.startText) { timePickerTarget = draft.section to true }
                                Text("—", fontSize = 12.sp)
                                TimeChip(draft.endText) { timePickerTarget = draft.section to false }
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
                    /**
                     * 三个计数都是从数据库实时读来的（不是存在某处的计数器），
                     * 所以它显示的一定是真实值。
                     *
                     * 这一栏的另一个作用是**给用户一个"我的数据在哪"的确认**。
                     * 一个不联网的 app，"数据存在哪、会不会丢"是用户最关心的，
                     * 与其藏在帮助文档里，不如直接放在设置里说清楚。
                     */
                    HintText("课程 ${state.courseCount} 门 · 便签 ${state.noteCount} 条 · 待办 ${state.todoCount} 条")
                    VerticalSpacer(6)
                    /**
                     * 这段说明文字是有意写这么长的。它在回答三个问题：
                     *
                     * 1. **数据在哪** —— "只存在这台手机上（Room 数据库）"；
                     * 2. **隐私** —— "app 没有申请联网权限"
                     *    （这条是本项目的核心卖点，值得明说。用户可以在
                     *    系统设置里验证，见 `AndroidManifest.xml`）；
                     * 3. **风险提示** —— "卸载会清空数据"，并且给出缓解方案
                     *    （P2 的备份功能）。
                     *
                     * 第 3 条尤其重要：**不提示用户会导致真实的数据丢失**。
                     * 一个课程表 app 让用户重填一整学期 20 门课的时间，
                     * 是很难接受的体验。
                     */
                    HintText("所有数据只存在这台手机上（Room 数据库），app 没有申请联网权限。卸载会清空数据，请在 P2 的备份功能上线后定期导出。")
                }
            }

            VerticalSpacer(20)
            /**
             * 保存按钮。用 `Button`（实心）而不是 `TextButton`/`OutlinedButton`，
             * 因为它是这个页面的**主操作**——视觉上要最突出。
             * Material 3 的层级约定就是：一个页面通常只有一个实心按钮，
             * 就是那个"主要动作"。
             *
             * 加课页那边的"保存"是在顶部用 `TextButton`，
             * 因为加课页的布局更紧凑，顶部工具栏的样式本身就在强调它。
             */
            Button(
                onClick = viewModel::save,
                modifier = Modifier.fillMaxWidth()
            ) { Text("保存") }
            VerticalSpacer(32)
        }
    }

    /**
     * 日期选择弹窗。条件渲染，只有 `showDatePicker` 为 true 时才存在。
     *
     * `initialEpochDay = state.startEpochDay` 传的是**最后一次成功解析的日期**，
     * 而不是 `state.startDateText`。
     *
     * 这个区别重要：如果用户手输了 `"abc"` 然后点"选择日期"，
     * 传文本进去会解析失败（`startEpochDay` 是上次的有效值，
     * 而文本是垃圾）。用 `startEpochDay` 就能正常弹出、定位到上次的日期上。
     */
    if (showDatePicker) {
        DatePickerDialogM3(
            initialEpochDay = state.startEpochDay,
            onDismiss = { showDatePicker = false },
            onPicked = { epochDay ->
                viewModel.onStartDatePicked(epochDay)
                /**
                 * 选完立刻关掉弹窗。**顺序是先写数据、后关弹窗**，
                 * 和加课页那个删除确认相反（那边是先关后删）。
                 *
                 * 为什么这里反着来：因为 `onStartDatePicked` 是同步的
                 * （只改内存状态），不会失败，先做后做都一样。
                 * 而那边是异步删除，先关弹窗能让用户立刻看到反馈。
                 * **同步操作随便什么顺序，异步操作要按"用户看到什么"来定顺序。**
                 */
                showDatePicker = false
            }
        )
    }

    /**
     * 时间选择弹窗。
     *
     * ## `timePickerTarget?.let { (section, isStart) -> ... }` 这一行解释了 Pair 的价值
     *
     * `let` 里对 `Pair` 做了**解构**，直接拿到 `section` 和 `isStart`
     * 两个有名字的变量，而不是 `it.first` / `it.second`。
     *
     * 对比一下不用 Pair 的写法：得开两个 `var`、再判断
     * "两个都非空才弹"、弹完两个都清空。用 Pair 一个 `?.let` 就完了。
     *
     * ## 为什么要在 `let` 里重新查一次 `draft`
     *
     * `state.periods.firstOrNull { it.section == section }` ——
     * 因为**弹窗是异步的**：用户点开选择器之后、确认之前，
     * `state` 理论上可能已经变了（虽然当前 UI 到不了）。
     * 重新查一次而不是闭包捕获，保证用的是最新数据。
     *
     * `firstOrNull` 而不是 `first`——如果那一节已经被删了
     * （用户点了删一节），这里会返回 null 而不是抛异常。
     */
    timePickerTarget?.let { (section, isStart) ->
        val draft = state.periods.firstOrNull { it.section == section }
        val initialText = if (isStart) draft?.startText else draft?.endText
        TimePickerDialogM3(
            /**
             * `initialText?.let { TimeUtils.textToMinutes(it) } ?: 480`
             *
             * 读作：如果那一节还在、把它的时间文本解析成分钟数；
             * 任何一步失败（那一节没了，或者文本非法）就兜底成 480（08:00）。
             *
             * 注意**这里必须能处理解析失败**——因为用户手输的时间文本
             * 完全可能是垃圾（`"abc"`）。如果这里直接 `!!`，
             * 用户输错一次再点时间标签就会崩。
             *
             * `TimeUtils.textToMinutes` 返回 `Int?` 的设计（而不是抛异常）
             * 在**这个调用点**体现出了价值。
             */
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
 * 一个可点击的时间标签，比如 `[08:00]`。
 *
 * ## 为什么不直接用 Material 的组件
 *
 * Material 3 没有"可点击的小标签"这种现成控件
 * （`Chip` 偏大、`Button` 偏重）。用 `Box` + `Modifier`
 * 拼一个只要 6 行，而且样式完全可控。
 *
 * **这是 Compose 的一个基本能力：绝大多数"视觉元素"都可以
 * 用 `Box` + `Modifier` 拼出来，不需要等官方或第三方控件。**
 * 前面对课程色块的 `Box` + `background` 也是同一个思路。
 *
 * ## `Modifier` 链的顺序在这里非常关键
 *
 * ```
 * .clip(RoundedCornerShape(8.dp))     // ① 先把形状定成圆角矩形
 * .background(...)                    // ② 在圆角形状内填色
 * .clickable(onClick = onClick)       // ③ 可点击区域 = 上面的圆角矩形
 * .padding(horizontal = 10.dp, ...)   // ④ 内容的内边距（不影响点击区域）
 * ```
 *
 * 为什么 `clip` 必须在 `background` 之前：`background` 会铺满
 * **它当前拿到的形状**，没有先 `clip` 的话会是一个直角矩形，
 * 圆角就白设了。
 *
 * 为什么 `clickable` 在 `padding` 之前：这样**整个圆角色块都可点**，
 * 而不只是里面的文字。如果反过来，用户得精确点到文字才行——
 * 在手机上这是很糟的体验（点不中）。
 *
 * ## `clickable(onClick = onClick)` 这个写法
 *
 * 也可以写成 `.clickable { onClick() }`。用命名参数直接传函数引用
 * 少一层 lambda，而且意图更明确（"这个参数就是点击回调"）。
 * 两种写法在 Compose 里都很常见。
 *
 * @param text 要显示的时间文本，如 `"08:00"`。
 * @param onClick 点击时触发，用来打开时间选择器。
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
