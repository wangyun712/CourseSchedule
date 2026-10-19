package com.courseschedule.feature.courseedit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.courseschedule.data.ScheduleRepository
import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.CourseSlot
import com.courseschedule.data.entity.WeekType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 节次的上限。
 *
 * 声明在**文件顶层**（不在类里），所以其他文件可以 `import` 它。
 * 目前 [com.courseschedule.feature.courseedit.CourseEditScreen] 的
 * 步进器和本文件落库前的校验都用它。
 *
 * 15 这个数字来自实际的作息上限——一天 24 小时，按每节 45 分钟 + 10 分钟课间算，
 * 撑死也就 20 节左右；15 已经远超正常大学的排课量（通常 12 节），
 * 又小到能保证 UI 上的步进器不至于点太多下。
 *
 * **改这个值要同时考虑两处**：这里的校验，和设置页里作息表能加到多少节。
 * 两者不一致会出现"作息有第 16 节，但课程没法占到第 16 节"这种尴尬。
 */
const val MAX_SECTION = 15

/**
 * **编辑态的**时间段草稿。
 *
 * ## 为什么需要它，而不是直接用 [CourseSlot]
 *
 * 两个理由，都很实际：
 *
 * 1. **[CourseSlot] 有它不该有的字段**。`id` 和 `courseId` 是数据库的概念，
 *    编辑中的草稿根本还没有 id（保存时才分配），硬要用 [CourseSlot]
 *    就得填一堆假的 `0L`，还容易在保存时忘了重写导致脏数据。
 *
 * 2. **将来 UI 可能需要只有界面才有的字段**。比如"这一条是不是刚加的、
 *    要不要高亮提示"。这类字段不该污染数据库实体。
 *
 * 拆开之后，"表单里的样子"和"数据库里的样子"就是两个东西，
 * 转换发生在**保存的那一刻**（见 [CourseEditViewModel.save]），
 * 那时候正好可以做数据纠正。
 *
 * ## 为什么它的字段和 [CourseSlot] 几乎一样
 *
 * 因为目前确实只差 id。**这是有意的**——不做多余的抽象。
 * 等两边真的开始分叉时，再让它长出独有的字段。
 * 现在这种"看起来像重复"是零成本的（`data class` 一行一个字段）。
 *
 * ## 所有字段都有默认值
 *
 * 所以能写 `SlotDraft()` 得到一个"周一第 1-2 节、每周、第 1-20 周"的
 * 空白草稿。点"＋ 添加"时就是这么建的（见 [CourseEditViewModel.addSlot]）。
 *
 * @property dayOfWeek 星期几，1-7。
 * @property startSection 起始节次。**不保证 <= [endSection]**——
 *   用户可能把步进器点到比结束节次大。这里不做约束，
 *   到保存时统一用 `minOf / maxOf` 纠正（见 [CourseEditViewModel.save]）。
 *   这样用户在编辑过程中不会遇到"点一下被弹回去"的困扰。
 * @property endSection 结束节次。同上。
 * @property startWeek 生效起始周。
 * @property endWeek 生效结束周。默认 20，但**新建时会跟随学期总周数**
 *   （见 [CourseEditViewModel.addSlot] 的 `SlotDraft(endWeek = it.totalWeeks)`）。
 * @property weekType 单双周。
 * @property roomOverride 地点覆盖，空串表示用课程默认地点。
 */
data class SlotDraft(
    val dayOfWeek: Int = 1,
    val startSection: Int = 1,
    val endSection: Int = 2,
    val startWeek: Int = 1,
    val endWeek: Int = 20,
    val weekType: WeekType = WeekType.ALL,
    val roomOverride: String = ""
)

/**
 * 加课 / 改课界面的状态。**整个表单的内容都在这里面**。
 *
 * ## 为什么文本框的内容也要放进状态（这是 Compose 的关键点）
 *
 * 在 Qt 里，`QLineEdit` 自己存着自己那串文字，你要读的时候
 * `text()` 一下就行。**Compose 的 `TextField` 不是这样**——
 * 它是个"受控组件"：显示什么完全由你传进去的 `value` 决定。
 *
 * 所以这里必须有 `name` / `teacher` / `location` / `note` 四个字段。
 * 用户每敲一个字符，`onValueChange` 回调触发 → 更新状态 →
 * 状态变化 → 整个界面重组 → `TextField` 拿到新 `value`。
 *
 * 看起来绕了一大圈，但带来一个巨大的好处：**状态在你手里**。
 * 所以"保存"时能直接读 `_uiState.value` 拿到全部内容，
 * 不需要去问每个控件"你现在是什么值"。校验、脏数据纠正、
 * 甚至"退出时提示未保存"，都变成纯数据的操作。
 *
 * @property loading 是否还在把已有数据读进表单。true 时界面显示转圈。
 * @property notFound true 表示"要编辑的课不存在"（可能被别的入口删了）。
 *   界面会显示"没找到这门课"而不是一个空表单——
 *   后者更糟，用户填完点保存会**新建**一门课，而他以为在改旧的。
 * @property semesterId 这门课属于哪个学期。新建时从当前学期取。
 * @property courseId 正在编辑的课程 id。**`0L` 表示新建**，
 *   这个约定贯穿整个项目（[com.courseschedule.data.ScheduleRepository.saveCourse]
 *   也是靠它区分插入还是更新）。
 * @property isNew 是否新建。等于 `courseId == 0L`，但单独存一份是为了界面好读
 *   （标题要在"新增课程"/"编辑课程"之间切换）。
 * @property totalWeeks 学期总周数。用来给"周次范围"的步进器定上下界，
 *   以及新建时间段时把默认结束周设成总周数。
 * @property name / teacher / location / note 四个文本框的内容。
 *   注意 `name` 是唯一必填的，保存时会校验。
 * @property colorIndex 选中的颜色下标（0-7）。
 * @property slots **这门课的全部时间段草稿**。
 *   用 `List<SlotDraft>` 而不是"3 个固定字段"，是数据模型拆表决策在 UI 上的直接体现：
 *   用户点"＋ 添加"就是往这个列表里加一项，点"删除"就是去掉一项。
 *   界面上渲染成几张卡片（见 [SlotCard]），每张卡片操作列表里的一个元素。
 * @property error 要显示的错误提示，`null` 表示没有错误。
 *   **用可空的字符串而不是一个布尔标志**，因为错误信息本身要显示给用户，
 *   多存一份字符串比"标志 + 另外找文案"简单。
 *   注意它和 [consumeError] 的配合——错误显示完要清掉，
 *   否则用户改好之后再点保存，同一句话会莫名再闪一次。
 * @property finished 保存或删除完成。界面靠它触发返回上一页
 *   （见 [com.courseschedule.feature.courseedit.CourseEditScreen] 里的 `LaunchedEffect`）。
 *
 *   ## 为什么不让 ViewModel 直接调导航
 *
 *   因为 ViewModel **不该知道导航的存在**。它只负责说"我干完了"，
 *   至于干完之后是返回上一页、还是跳到详情页，那是界面的事。
 *   这样 ViewModel 可以在任何宿主里复用（甚至放进一个对话框）。
 */
data class CourseEditUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val semesterId: Long = 0L,
    val courseId: Long = 0L,
    val isNew: Boolean = true,
    val totalWeeks: Int = 20,
    val name: String = "",
    val teacher: String = "",
    val location: String = "",
    val colorIndex: Int = 0,
    val note: String = "",
    val slots: List<SlotDraft> = emptyList(),
    val error: String? = null,
    /** 保存或删除完成后置 true，界面据此返回 */
    val finished: Boolean = false
)

/**
 * 加课 / 改课页的 ViewModel。
 *
 * ## 和 [com.courseschedule.feature.schedule.ScheduleViewModel] 的关键区别
 *
 * 课表页是**响应式**的（用的 `combine` + 数据库 `Flow`，数据一变就重算）；
 * 加课页是**命令式**的（用的是 `MutableStateFlow`，只有用户操作才改）。
 *
 * 为什么不一样？因为**用户正在输入的表单不能被数据库覆盖**。
 * 课表页的数据永远是"数据库说了算"；加课页的数据在打开那一刻之后就
 * "用户说了算"，数据库只提供初始值。这是编辑型界面的通用规律。
 *
 * @param repository 数据来源。
 * @param courseId 要编辑的课程 id。**`0L` 表示新建**。
 * @param presetDay 从课表格子点进来时预填的星期几。
 *   用户在周三那一列点空白，进到加课页"星期"就应该已经选中周三。
 *   从别的入口进来时传 1（周一）。
 * @param presetSection 预填的起始节次。目前界面总是传 1，
 *   保留参数是为了以后做"精确点到第几节"。
 */
class CourseEditViewModel(
    private val repository: ScheduleRepository,
    private val courseId: Long,
    private val presetDay: Int,
    private val presetSection: Int
) : ViewModel() {

    /**
     * 内部的**可变**状态流。注意前面有下划线，这是 Kotlin 社区的通用命名约定：
     * **`_xxx` 是私有的可写版本，`xxx` 是公开的只读版本。**
     *
     * @Suppress 说明：`private val` 完全可以被外部修改（Kotlin 没有 C++ 的
     * `const` 友元那种精细控制），所以"私有"是靠**约定**而不是靠语言强制的。
     * 下划线就是这个约定的可视化标记：看到 `_uiState.value = ...`
     * 就知道"这里在改状态"，而外部只能读 [uiState]。
     */
    private val _uiState = MutableStateFlow(CourseEditUiState())

    /**
     * 对外暴露的**只读**视图。
     *
     * `asStateFlow()` 返回的是一个**包装后的只读引用**——
     * 界面拿到的对象上根本没有 `value` 的 setter，所以**编译期**就写不进去。
     * 这比"文档里写一句请不要改"可靠得多。
     *
     * 注意它返回的还是**同一个底层流**，所以界面订阅它和订阅 `_uiState` 效果一样，
     * 只是拿不到写权限。
     */
    val uiState: StateFlow<CourseEditUiState> = _uiState.asStateFlow()

    /**
     * 初始化：把已有数据（或空白草稿）读进表单。
     *
     * ## 为什么用 `first()` 而不是 `collect`
     *
     * `repository.observeSemester()` 返回的是 `Flow`（会持续发值）。
     * `.first()` 是它的一个**终止操作**：取第一个值，然后**取消订阅**。
     *
     * 这里刻意只要第一个值。如果写成 `collect { ... }`，
     * 用户在设置页改了学期名（另一个入口，虽然当前 UI 到不了），
     * 表单里的内容会被重新写一遍——正在输入的东西就没了。
     *
     * 所以编辑页的策略是：**进来读一次，之后完全由用户掌控，
     * 只有点保存时才写回数据库。**
     *
     * ## 三条分支
     *
     * 1. **没有学期** → 标 `notFound`。理论上进不来（课表页已经保证有学期了），
     *    但防御性地处理一下，避免后面 `semester.id` 崩空指针。
     * 2. **新建（courseId == 0L）** → 建一个预填了日期和节次的空白时间段，
     *    然后 `return@launch` 提前退出。
     * 3. **编辑已有课程** → 读出课程和时间段，映射成草稿填进表单。
     *
     * ## `return@launch` 是什么意思
     *
     * 带标签的返回。`launch { }` 里的 lambda 没法用普通的 `return`
     * （那会试图从 [init] 返回，编译器不允许），所以用 `return@launch`
     * 表示"从这里跳出这个 lambda"。
     * 同理 [CourseEditViewModel.onSlotChange] 之类的地方会用到 `@update`。
     * 标签名就是被调函数的名字。
     */
    init {
        viewModelScope.launch {
            // 一次性读取，之后表单由用户自己掌握（不跟着数据库流刷新，否则正在输入的内容会被覆盖）
            val semester = repository.observeSemester().first()
            if (semester == null) {
                _uiState.update { it.copy(loading = false, notFound = true) }
                return@launch
            }

            if (courseId == 0L) {
                /**
                 * 新建：把预设的日期和节次夹到合法范围。
                 *
                 * 为什么要夹：这些值来自导航参数（URL 形式的字符串），
                 * 理论上可能是任何数字。如果不夹，用户可以构造一个
                 * `day=99` 的链接进来，然后在界面上看到一个"星期 99"的选中态。
                 */
                val day = presetDay.coerceIn(1, 7)
                val start = presetSection.coerceIn(1, MAX_SECTION)
                /**
                 * `it.copy(...)` 是 [kotlinx.coroutines.flow.update] 里 lambda 的
                 * 参数 `it`，代表**当前状态**。返回值是"新状态"。
                 *
                 * 这是 Kotlin 里更新状态的唯一正确姿势：
                 * **不修改旧对象，而是造一个新对象**。
                 * 原因见第 2 课：Compose 靠比较新旧对象是否相等来判断要不要重绘，
                 * 原地改字段的话新旧是同一个对象，界面永远不刷新。
                 *
                 * `_uiState.update { }` 相比手写 `_uiState.value = ...`
                 * 的好处是**原子**：高并发下不会丢更新。
                 */
                _uiState.update {
                    it.copy(
                        loading = false,
                        semesterId = semester.id,
                        isNew = true,
                        totalWeeks = semester.totalWeeks,
                        slots = listOf(
                            SlotDraft(
                                dayOfWeek = day,
                                startSection = start,
                                /**
                                 * 结束节次默认比开始多一节，但不能超过上限。
                                 * `coerceAtMost(MAX_SECTION)` 防止
                                 * "开始节次是第 15 节，结束节次变成第 16 节"。
                                 */
                                endSection = (start + 1).coerceAtMost(MAX_SECTION),
                                /**
                                 * 默认的生效范围是"整个学期"。
                                 * 绝大多数课都是这样的，用户不用改。
                                 */
                                endWeek = semester.totalWeeks
                            )
                        )
                    )
                }
                return@launch
            }

            val existing = repository.courseWithSlots(courseId)
            if (existing == null) {
                _uiState.update { it.copy(loading = false, notFound = true) }
                return@launch
            }
            /**
             * 编辑已有课程：把 [CourseSlot] 逐条映射成 [SlotDraft]。
             *
             * 注意这里**逐字段手写**而不是用某种自动映射：
             * - Kotlin 没有内置的对象映射（那要靠反射或第三方库）；
             * - 手写的好处是**编译期就能发现实体加了字段而这里漏了**——
             *   如果是运行时反射映射，漏字段会静默地变成默认值，
             *   表现是"改了东西保存后丢了"，极难查。
             *
             * 这段代码看起来啰嗦，但它是**唯一一处**做这个转换的地方
             * （反向转换在 [save] 里，也是唯一一处）。两个方向各自集中，
             * 改实体时只要改这两个文件就能保证不漏。
             */
            _uiState.update {
                it.copy(
                    loading = false,
                    semesterId = existing.course.semesterId,
                    courseId = existing.course.id,
                    isNew = false,
                    totalWeeks = semester.totalWeeks,
                    name = existing.course.name,
                    teacher = existing.course.teacher,
                    location = existing.course.location,
                    colorIndex = existing.course.colorIndex,
                    note = existing.course.note,
                    slots = existing.slots.map { slot ->
                        SlotDraft(
                            dayOfWeek = slot.dayOfWeek,
                            startSection = slot.startSection,
                            endSection = slot.endSection,
                            startWeek = slot.startWeek,
                            endWeek = slot.endWeek,
                            weekType = slot.weekType,
                            roomOverride = slot.roomOverride
                        )
                    }
                )
            }
        }
    }

    /**
     * 改课程名。**同时清空错误提示**，因为这时候显示"课程名不能为空"
     * 已经过时了——用户正在解决这个问题。不清掉的话，他会看到
     * 自己已经在修改了、红字还挂在那儿，以为没生效。
     */
    fun onNameChange(value: String) = _uiState.update { it.copy(name = value, error = null) }

    /** 改教师。不需要清错——教师不是必填项，它变了不会让已有错误失效。 */
    fun onTeacherChange(value: String) = _uiState.update { it.copy(teacher = value) }

    /** 改默认地点。 */
    fun onLocationChange(value: String) = _uiState.update { it.copy(location = value) }

    /** 改备注。 */
    fun onNoteChange(value: String) = _uiState.update { it.copy(note = value) }

    /**
     * 改课程颜色。
     *
     * @param index 颜色下标（0-7）。这里**不校验范围**——
     *   [com.courseschedule.ui.theme.CoursePalette.colorOf] 会做取模，
     *   所以越界也不会崩，最坏情况是颜色和界面上选中的不一致。
     */
    fun onColorChange(index: Int) = _uiState.update { it.copy(colorIndex = index) }

    /**
     * 改一个时间段的某个字段。
     *
     * ## 这个"传函数"的设计为什么比"传字段名"好
     *
     * 参数是 `transform: (SlotDraft) -> SlotDraft`，也就是"给一个旧草稿、
     * 返回一个新草稿"。调用方这样用（见 [CourseEditScreen]）：
     * ```
     * onSlotChange { it.copy(dayOfWeek = day) }
     * ```
     *
     * 对比另一种写法——给每个字段开一个方法
     * （`onSlotDayChange` / `onSlotStartSectionChange` / ...），
     * 一共 6 个字段就要写 6 个方法，而且每加一个字段就要加一个方法。
     *
     * 传函数之后，**ViewModel 只需要一个方法**，新增字段时 ViewModel 完全不用动，
     * 只改界面。这就是 Kotlin 把 lambda 当一等公民带来的实际收益。
     *
     * ## 为什么用 `mapIndexed` 而不是 `map { it.copy(...) }`
     *
     * 因为要**只改指定下标的那一个**，其他保持不变。`mapIndexed` 在 lambda 里
     * 能拿到下标 `i`，于是可以写 `if (i == index) transform(slot) else slot`。
     *
     * 这里用下标而不是 id 来定位，是因为草稿**还没有 id**——
     * 这正是 [SlotDraft] 独立于 [CourseSlot] 的代价之一。
     * 界面上 `forEachIndexed` 拿到的也是下标，两边对得上。
     *
     * 代价是"界面上的第 2 个卡片"和"列表里的下标 1"必须严格对应，
     * 所以删除时段用的是 [removeSlot]，不能中间插入。
     *
     * ## 错误提示也一起清掉
     *
     * 因为"至少需要一个时间段"这个错误，用户一改时间段就可能已经解决了。
     */
    fun onSlotChange(index: Int, transform: (SlotDraft) -> SlotDraft) {
        _uiState.update { state ->
            state.copy(
                slots = state.slots.mapIndexed { i, slot ->
                    if (i == index) transform(slot) else slot
                },
                error = null
            )
        }
    }

    /**
     * 加一个时间段。
     *
     * `it.slots + SlotDraft(...)` 用的是 Kotlin 的 `List` 加号运算符——
     * **返回一个新列表**，原列表不动。等价于 C++ 的
     * `auto next = slots; next.push_back(...);` 但不需要手动拷贝。
     *
     * 注意 `endWeek = it.totalWeeks`：新时段的生效范围默认到学期末，
     * 而不是写死的 20。这样学期只有 16 周时，新课不会莫名其妙
     * 在"第 17-20 周"还显示着。
     *
     * 加完**不清 error**：因为加一个时段不会让"课程名不能为空"这类错误消失，
     * 清掉反而会让红字闪一下又出现。
     */
    fun addSlot() = _uiState.update {
        it.copy(slots = it.slots + SlotDraft(endWeek = it.totalWeeks))
    }

    /**
     * 删掉第 [index] 个时间段。
     *
     * `filterIndexed { i, _ -> i != index }` 是"留下所有下标不等于 index 的"，
     * 效果就是删掉那一个。这里的 `_` 表示"这个参数我不关心"
     * （下标之外的第二个参数是元素本身）。
     *
     * 界面上**允许删到 0 个**（不在这里拦），因为用户可能是想
     * 先删完再重新加一批。真正的拦截在 [save] 里：
     * 保存时 0 个时间段会报"至少需要一个时间段"。
     * 这个设计让用户在编辑过程中有完全的自由，只在最后一道关口校验。
     */
    fun removeSlot(index: Int) = _uiState.update {
        it.copy(slots = it.slots.filterIndexed { i, _ -> i != index })
    }

    /**
     * 保存。**这是整个编辑流程的收口**，所有数据纠正都在这里做。
     *
     * ## 分两段：先校验（同步），再写库（异步）
     *
     * 校验放在 `viewModelScope.launch` **外面**，因为它不涉及 I/O，
     * 是纯内存判断，同步做完就行。这样出错时不需要启动协程，
     * 代码路径更短、也更好读。
     *
     * 写库必须放进 `launch`，因为它是 `suspend` 函数（会等磁盘 I/O）。
     *
     * ## 校验只做两件事
     *
     * 1. 课程名非空（`isBlank` 而不是 `isNotEmpty`——`"   "` 也算空）；
     * 2. 至少要有一个时间段。
     *
     * **刻意不做**的校验：节次范围、周次范围、时间段重复。
     * 因为界面上已经用 [NumberStepper] 把上下界锁死了
     * （传了 `range = 1..MAX_SECTION`），用户根本输不出越界值。
     * 在这里再判一遍属于重复。
     *
     * 但**下面那段 `minOf / maxOf` 还是必须的**，因为它处理的不是
     * "越界"而是"顺序颠倒"——这个步进器拦不住。详见下面的注释。
     *
     * ## 为什么校验失败就 `return`（而不是抛异常或弹对话框）
     *
     * 因为错误信息的展示统一走 [CourseEditUiState.error]，
     * 界面顶部有一行红字专门显示它。一个入口、一种表现形式。
     */
    fun save() {
        /**
         * `_uiState.value` 是同步读当前状态。在 ViewModel 内部调 `.value`
         * 是正常且推荐的（`StateFlow` 保证有当前值）。
         * 先存成局部变量 `state`，是因为下面要用它十几次，
         * 每次 `_uiState.value.xxx` 读起来太啰嗦。
         *
         * 而且这样还顺带保证了一致性：这一整段用的是**同一份快照**，
         * 不会在中间被别的协程改掉一半。
         */
        val state = _uiState.value
        if (state.name.isBlank()) {
            _uiState.update { it.copy(error = "课程名不能为空") }
            return
        }
        if (state.slots.isEmpty()) {
            _uiState.update { it.copy(error = "至少需要一个时间段") }
            return
        }

        viewModelScope.launch {
            /**
             * 组装课程实体。
             *
             * `id = state.courseId`：新建时是 `0L`，
             * [com.courseschedule.data.ScheduleRepository.saveCourse]
             * 靠它区分插入还是更新。
             *
             * **所有文本字段都 `.trim()` 了一次**。用户很容易在
             * 前后带上空格（尤其是从别处复制粘贴），
             * 不 trim 的话"高数"和"高数 "是两门课，排序时还会跑到最前面。
             * 注意在**落库前**trim，而不是每个 `onXxxChange` 里 trim——
             * 后者会让用户在输入过程中没法打空格。
             */
            val course = Course(
                id = state.courseId,
                semesterId = state.semesterId,
                name = state.name.trim(),
                teacher = state.teacher.trim(),
                location = state.location.trim(),
                colorIndex = state.colorIndex,
                note = state.note.trim()
            )
            /**
             * 组装时间段实体。**这里是全部数据纠正逻辑所在**。
             *
             * ## 纠正一：起止节次可能颠倒
             *
             * 用户在界面上可以先把结束节次调到第 2 节，再把开始节次调到第 5 节——
             * 步进器允许这么干（它只管范围不管顺序）。
             * 这样草稿里就是 `start=5, end=2`，直接用会算出负高度，课块会画错。
             *
             * `minOf / maxOf` 不是"报错"而是"**静默纠正**"：
             * 用户得到的结果是"第 2-5 节"，也就是他大概想要的那个区间。
             * 对这种"顺序颠倒但意图明确"的情况，纠正比报错友好——
             * 报错会让用户不知道点错了哪里。
             *
             * 界面上其实也做了联动（改开始节次时会顺带顶起结束节次），
             * 但这里**仍然要纠一次**：因为界面只覆盖了步进器那条路径，
             * 万一以后加了"直接输入数字"，或者有人绕过界面调用，
             * 这里是最后一道闸门。**数据层的正确性不能依赖 UI 的行为**。
             *
             * ## 纠正二：这里补了一个界面上没做的 clamp
             *
             * 注意 `startSection` / `endSection` 的 `coerceIn(1, MAX_SECTION)`：
             * 界面上的步进器有范围限制，但预设值来自导航参数，
             * 而导航参数是字符串。这里夹一道，保证任何来源的数据都干净。
             *
             * ## 周次的纠正
             *
             * `startWeek` 用 `coerceAtLeast(1)`（不能小于 1），
             * `endWeek` 用 `maxOf`（不能小于 startWeek）。
             * 注意 `endWeek` **没有上界**——理论上可以超过学期总周数。
             * 这是可接受的：[com.courseschedule.core.WeekCalculator.isSlotActive]
             * 里 `week > slot.endWeek` 的判断会让超出部分的周次永远不会显示，
             * 行为是"这段超出了但无害"，不需要额外拦。
             */
            val slots = state.slots.map { draft ->
                CourseSlot(
                    courseId = state.courseId,
                    dayOfWeek = draft.dayOfWeek.coerceIn(1, 7),
                    // 用户可能把开始节次点到比结束节次大，落库前统一纠正，避免脏数据
                    startSection = minOf(draft.startSection, draft.endSection).coerceIn(1, MAX_SECTION),
                    endSection = maxOf(draft.startSection, draft.endSection).coerceIn(1, MAX_SECTION),
                    startWeek = minOf(draft.startWeek, draft.endWeek).coerceAtLeast(1),
                    endWeek = maxOf(draft.startWeek, draft.endWeek),
                    weekType = draft.weekType,
                    roomOverride = draft.roomOverride.trim()
                )
            }
            /**
             * 跨两张表的事务写入。`repository.saveCourse` 里包了
             * `withTransaction`，所以这里不用自己开事务。
             *
             * **注意 `courseId` 没有回填到 state 里**，因为写完就
             * 立刻标 `finished = true` 让界面返回了，没必要。
             * 如果以后要改成"保存后停留在编辑页"，这里就得补一句
             * `_uiState.update { it.copy(courseId = newId, isNew = false) }`。
             */
            repository.saveCourse(course, slots)
            _uiState.update { it.copy(finished = true) }
        }
    }

    /**
     * 删除这门课。**不可撤销**。
     *
     * ## 新建状态下的"删除"就是取消
     *
     * 如果 [CourseEditUiState.isNew] 为 true，说明这门课还没入库，
     * 没有任何东西可删。这时把 `finished` 置 true 让界面返回，
     * 效果就是"取消"。
     *
     * 为什么不干脆禁用按钮：因为用户点了"新增课程"进来后想退出，
     * 第一反应就是找"删除"或者左上角的返回。这里让它"看起来能用、
     * 行为等于取消"比让它灰着更好——灰着的按钮会让用户去找别的出口。
     * （不过编辑页左上角本来就有"返回"，所以这条路径主要是兜底。）
     *
     * ## 二次确认在哪里做的
     *
     * **不在 ViewModel 里**，在
     * [com.courseschedule.feature.courseedit.CourseEditScreen] 的
     * `AlertDialog`。因为"要不要弹确认框"是界面决策，不是业务逻辑。
     * ViewModel 的 `delete()` 被调用就意味着"用户已经确认了"。
     *
     * 这个分工的好处是：如果以后要在别的地方（比如课表页长按删除）
     * 复用这个删除能力，那边可以自己设计确认方式，不用迁就这里的弹窗。
     */
    fun delete() {
        val state = _uiState.value
        if (state.isNew) {
            _uiState.update { it.copy(finished = true) }
            return
        }
        viewModelScope.launch {
            repository.deleteCourse(state.courseId)
            _uiState.update { it.copy(finished = true) }
        }
    }

    /**
     * 清掉错误提示。
     *
     * ## 这个方法是给"需要消费一次性事件"的场景准备的
     *
     * 目前 [CourseEditScreen] 是把 error 当成普通状态直接显示红字的，
     * 所以其实没有调用它。留着它是为了以后如果改成用 Toast 或
     * Snackbar 显示错误——那两种控件是"弹一下就走"的，
     * 必须有人告诉状态"我已经展示过了，可以清掉了"，
     * 否则重组一次就再弹一次。
     *
     * 项目里 [com.courseschedule.feature.settings.SettingsViewModel]
     * 的 `consumeMessage()` 就是这种模式，那边的界面确实用的是 Toast。
     */
    fun consumeError() = _uiState.update { it.copy(error = null) }
}
