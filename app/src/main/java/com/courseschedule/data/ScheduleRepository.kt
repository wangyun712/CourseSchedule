package com.courseschedule.data

import androidx.room.withTransaction
import com.courseschedule.core.WeekCalculator
import com.courseschedule.data.backup.BackupPayload
import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.CourseSlot
import com.courseschedule.data.entity.CourseWithSlots
import com.courseschedule.data.entity.PeriodTime
import com.courseschedule.data.entity.Semester
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * **单一数据源（Single Source of Truth）**。UI 层只跟这里打交道，
 * 不直接碰 DAO，更不直接碰 `AppDatabase`。
 *
 * ## 这一层为什么必须存在
 *
 * 如果让 ViewModel 直接调 DAO，很快会出现这些问题：
 *
 * 1. **业务规则散落**。"保存一门课"其实要跨 `course` 和 `course_slot` 两张表写，
 *    还得在事务里；如果每个界面各写一遍，迟早有一处忘了删旧时间段，留下脏数据。
 * 2. **换存储成本高**。哪天要加个内存缓存、或者把 CSV 导入也走同一套写库逻辑，
 *    没有这层就没地方插。
 * 3. **测试没法做**。有了这层，ViewModel 的测试可以塞一个假的 Repository，
 *    不用真的建数据库。
 *
 * 所以规矩是：**ViewModel 只认 Repository，不 import 任何 DAO**。
 * 你可以用 grep 验证这条规矩有没有被破坏：
 * ```
 * grep -rn "data.dao" app/src/main/java/com/courseschedule/feature/
 * # 应该没有任何输出
 * ```
 *
 * ## 为什么构造参数是 AppDatabase 而不是 Context
 *
 * Repository 不需要 Android 的任何东西，只需要一个数据库句柄。
 * 拿到 `Context` 会让人忍不住去做 `Toast`、读资源这类界面操作，
 * 这层就烂了。数据库实例由 [com.courseschedule.ScheduleApp.AppContainer] 建好后传进来。
 *
 * ## 读用 Flow、写用 suspend，这是硬规矩
 *
 * - **读**一律返回 `Flow<T>`：数据库一变，订阅方自动收到新值。
 *   UI 层因此完全不需要"手动刷新"的逻辑。
 * - **写**一律是 `suspend fun`：写库是 I/O，必须切到后台线程、不能阻塞界面。
 *   用 `suspend` 而不是回调，是为了让调用方能在协程里顺序地写代码。
 *
 * @param db 数据库实例。整个 app 只有一份，由 `AppContainer` 持有。
 */
class ScheduleRepository(private val db: AppDatabase) {

    /**
     * 四个 DAO 只在这里取一次并缓存成字段。
     * 因为 `AppDatabase.xxxDao()` 返回的是同一个实例（Room 内部缓存过），
     * 反复调用不会创建新对象，这里缓存只是为了让下面的方法体短一点。
     */
    private val semesterDao = db.semesterDao()
    private val courseDao = db.courseDao()
    private val noteDao = db.noteDao()
    private val todoDao = db.todoDao()

    // ---------- 读（Flow，数据一变 UI 自动刷新，不需要手动 notify） ----------

    /**
     * 观察当前学期。
     *
     * @return `Flow<Semester?>`——**可空**，因为首次启动、`ensureSemester()`
     *   还没跑完的那一瞬间，表里是空的。
     *   调用方必须先处理 null，见
     *   [com.courseschedule.feature.schedule.ScheduleViewModel] 里
     *   `if (sem == null) return@combine ScheduleUiState(loading = true)`。
     *
     * 为什么返回 `Flow` 而不是 `suspend fun`：设置页改了学期名或开学日期后，
     * 课表页要跟着变，用 Flow 就不需要任何跨页面的通知机制。
     */
    fun observeSemester(): Flow<Semester?> = semesterDao.observeCurrent()

    /**
     * 观察某个学期的节次作息。左侧"第 n 节 08:00"那一列的数据来源。
     *
     * @param semesterId 要观察哪个学期。虽然现在只有一个学期，
     *   但接口上仍然要求传——把"哪个学期"写死在 DAO 里的话，
     *   以后支持多学期就得改所有调用点。
     */
    fun observePeriods(semesterId: Long): Flow<List<PeriodTime>> =
        semesterDao.observePeriods(semesterId)

    /**
     * 观察某个学期的所有课程，**每条课程自带它的全部时间段**。
     *
     * 这是周视图的主数据源。返回 [CourseWithSlots] 而不是裸的 `Course`，
     * 是为了让 UI 一次拿到完整信息，不用二次查询（N+1 问题，
     * 详见 [CourseWithSlots] 的注释）。
     *
     * 列表按课程名排序（排序在 SQL 里，见
     * [com.courseschedule.data.dao.CourseDao.observeCourses]），
     * 这样课程数量变化时顺序不会乱跳。
     */
    fun observeCourses(semesterId: Long): Flow<List<CourseWithSlots>> =
        courseDao.observeCourses(semesterId)

    /**
     * 观察便签条数。P1 阶段**只有"便签"占位页在用**，目的是证明
     * `note` 表真的能读能写，不是画饼。
     *
     * P3 做了便签界面之后，这个是"首页角标显示待办数量"之类的现成接口。
     */
    fun observeNoteCount(): Flow<Int> = noteDao.observeCount()

    /** 观察待办条数。用途同 [observeNoteCount]。 */
    fun observeTodoCount(): Flow<Int> = todoDao.observeCount()

    /** 观察当前学期有多少门课。设置页"数据"那一栏在用。 */
    fun observeCourseCount(): Flow<Int> = courseDao.observeCourseCount()

    /**
     * 按 id 取一门课和它的时间段，**一次性读取**（不是 Flow）。
     *
     * 用在 [com.courseschedule.feature.courseedit.CourseEditViewModel] 的初始化里。
     *
     * ## 这里为什么刻意不用 Flow
     *
     * 编辑页是表单，用户正在里面打字。如果这里返回 Flow，
     * 数据库一有变化就会把用户正在输入的文本框内容**覆盖掉**。
     * 所以编辑页的策略是"进来读一次，之后就完全由用户掌控，
     * 只有点保存时才写回数据库"。这是编辑型界面的通用做法。
     *
     * @return 找不到返回 `null`（比如课程被别的入口删掉了）。
     *   调用方要处理，见 `CourseEditViewModel` 里的 `notFound` 分支。
     */
    suspend fun courseWithSlots(courseId: Long): CourseWithSlots? =
        courseDao.courseWithSlots(courseId)

    // ---------- 写 ----------

    /**
     * 确保有一个学期。**首次启动时建一个默认学期 + 一套默认作息**，
     * 保证用户装完打开就能看到东西，而不是对着空白课表发呆。
     *
     * 调用时机：[com.courseschedule.feature.schedule.ScheduleViewModel] 和
     * [com.courseschedule.feature.settings.SettingsViewModel] 的 `init` 里各调一次。
     * 两处都调是安全的——函数开头就检查了"已经有学期就直接返回"。
     *
     * ## 为什么开头要查一次，而不是全靠事务里的判断
     *
     * `db.withTransaction` 有开销（要开 WAL 事务、可能等锁）。
     * 正常情况下（第二次以后启动）只需一次廉价的 SELECT 就能提前退出。
     *
     * ## 为什么整个插入要包在 withTransaction 里
     *
     * 因为要写两张表（`semester` + 12 条 `period_time`）。
     * 如果不包事务、中途失败了，会留下"有学期但没有作息"的半截数据——
     * 那时课表左侧的节次列会是空白，而且 `ensureSemester` 第二次进来
     * 发现"已经有学期了"就直接返回，**永远不会自愈**。
     * 事务保证要么全成功、要么全回滚。
     *
     * ## 为什么开学日期取"本周一"而不是"今天"
     *
     * 如果今天是周三，`startEpochDay` 取周三的话，
     * 整学期的周次会全部偏两天（周一的课会算到第 2 周去）。
     * 所以用 [WeekCalculator.mondayOf] 对齐到周一。
     * 这只是个合理的默认值，用户可以在设置页改成真实开学日期。
     */
    suspend fun ensureSemester() {
        if (semesterDao.current() != null) return
        db.withTransaction {
            val startMonday = WeekCalculator.mondayOf(LocalDate.now().toEpochDay())
            val semesterId = semesterDao.insert(
                Semester(
                    name = "本学期",
                    startEpochDay = startMonday,
                    totalWeeks = DEFAULT_TOTAL_WEEKS
                )
            )
            semesterDao.insertPeriods(defaultPeriods(semesterId))
        }
    }

    /**
     * 更新学期信息（名称、开学日期、总周数、手动周次）。
     *
     * 注意是 `update` 不是"先删后插"——[Semester] 的 `id` 被
     * `course` 和 `period_time` 两张表当外键引用着，
     * 如果删掉再插会拿到一个新 id，所有课程和作息的关联就断了
     * （CASCADE 会把它们一并删掉，实际表现是"改个学期名，课全没了"）。
     */
    suspend fun saveSemester(semester: Semester) = semesterDao.update(semester)

    /**
     * 整表替换某个学期的节次作息。
     *
     * ## 为什么用"先删后插"而不是逐条 diff
     *
     * 用户在设置页可以随意加点、删点、改时间。如果做增量更新，
     * 要算清楚"哪几条新增、哪几条改了、哪几条删了"，还得维护 id 映射，
     * 逻辑量翻三倍，而且极易出错。
     *
     * 而作息表总共就十几条，整表替换在一个事务里跑完没有任何性能问题。
     * 这是典型的**用数据量换代码复杂度**——只要数据量可控，就选简单那个。
     *
     * ## 一个重要的副作用：节次会被重新编号
     *
     * 注意 `periods` 里的 `section` 是按**列表下标 + 1** 重算的
     * （在 [com.courseschedule.feature.settings.SettingsViewModel.save] 里做的）。
     * 所以如果用户删掉中间的第 3 节，后面的第 4 节会变成第 3 节。
     *
     * 这个行为是刻意的：课表里的[CourseSlot] 存的是"第几节"这个序号，
     * 而不是某个具体时间。作息表必须保持序号连续，课表才不会出现空档。
     * 副作用是"删掉第 3 节"等于把所有后面的节号整体前移，
     * 课表上的课看起来会往前挪——这是符合直觉的（作息变了，课自然跟着变）。
     *
     * @param semesterId 要替换哪个学期的作息。
     * @param periods 新的完整列表。传入项的 `id` 和 `semesterId` 会被忽略并重写
     *   （`copy(id = 0L, semesterId = semesterId)`），
     *   调用方只需要关心 `section / startMinute / endMinute`。
     */
    suspend fun savePeriods(semesterId: Long, periods: List<PeriodTime>) {
        db.withTransaction {
            semesterDao.deletePeriods(semesterId)
            semesterDao.insertPeriods(periods.map { it.copy(id = 0L, semesterId = semesterId) })
        }
    }

    /**
     * 保存一门课和它的全部时间段，**在一个事务里完成**。
     *
     * 这是本类最重要的写方法。加课页点"保存"时走的就是这里。
     *
     * ## 为什么时间段也用"先删后插"
     *
     * 编辑界面里用户可以随意增删时间段、改任意字段。如果做增量 diff，
     * 得算出新增/修改/删除三类，还要维护"界面上第 2 个时间段对应数据库里哪个 id"。
     * 而时间段总量最多十几条，整表替换的开销可以忽略。
     * 和 [savePeriods] 是同一个思路：**数据量换代码复杂度**。
     *
     * UI 因此只需要提交"最终长什么样"，完全不用管中间过程，
     * 这也是 `CourseEditViewModel` 里能只用一份 `List<SlotDraft>` 表达状态的前提。
     *
     * ## 事务为什么必须包住全部三步
     *
     * 三步是：插/改课程 → 删旧时间段 → 插新时间段。
     * 如果不包事务，在"删完旧的、还没插新的"这一刻崩溃或断电，
     * 结果就是**一门课的所有时间段凭空消失**——课还在课表里，
     * 但一个格子都不显示，用户会以为数据丢了。
     *
     * ## `course.id == 0L` 这个判断的含义
     *
     * 0L 是"这门课还没入库"的约定值（[Course] 的 `autoGenerate` 见到 0
     * 会分配一个新 id）。所以：
     * - `id == 0L` → 新增，走 `insertCourse`，它返回数据库分配的新 id；
     * - `id != 0L` → 编辑已有课程，走 `updateCourse`，id 保持原样。
     *
     * @param course 课程本体。新增时 `id` 应传 0L。
     * @param slots 这门课的全部时间段。`id` 和 `courseId` 会被忽略并重写。
     *   传空列表表示"这门课暂时没有任何时间段"——这是合法的，
     *   课还在但课表上不显示。界面上有校验会拦，但这里不假设调用方一定校验过。
     *
     * @return 这门课在数据库里的 id。新增时是刚分配的，编辑时就是传入的 id。
     *   目前调用方都没用这个返回值，留着是为了 P2 的 CSV 导入能串起来用。
     */
    suspend fun saveCourse(course: Course, slots: List<CourseSlot>): Long = db.withTransaction {
        val courseId = if (course.id == 0L) {
            courseDao.insertCourse(course)
        } else {
            courseDao.updateCourse(course)
            course.id
        }
        courseDao.deleteSlots(courseId)
        if (slots.isNotEmpty()) {
            courseDao.insertSlots(slots.map { it.copy(id = 0L, courseId = courseId) })
        }
        courseId
    }

    /**
     * 删除一门课。
     *
     * **不需要手动删它的时间段**：`course_slot` 上声明了
     * `onDelete = CASCADE`，SQLite 会把关联的时间段一起删掉。
     *
     * 注意这个删除是**物理删除、无法撤销**。界面上有二次确认弹窗
     * （见 [com.courseschedule.feature.courseedit.CourseEditScreen]），
     * 那是唯一的保护。P2 的备份功能上线后，误删才有真正的兜底。
     */
    suspend fun deleteCourse(courseId: Long) = courseDao.deleteCourse(courseId)

    // ---------- 备份 / 恢复 ----------

    /**
     * 把当前全部数据读成一个快照，供导出。[BackupManager] 调用。
     *
     * 整个读取包在一个事务里：**导出的必须是同一时刻的数据**。
     * 不加事务的话，读到一半用户刚好加了门课，导出的文件里就会
     * 出现"时间段的 courseId 指向一门还没写进文件的课"——
     * 而这种文件在导入时会被校验拦下来，等于用户白导一次。
     *
     * @throws IllegalStateException 学期还没建出来。正常路径走不到
     *   （两个 ViewModel 的 init 都会先 `ensureSemester()`），
     *   但如果真发生了，宁可抛错也不要导出一个没有学期的空文件。
     */
    suspend fun exportBackup(): BackupPayload = db.withTransaction {
        val semester = semesterDao.current()
            ?: error("还没有学期，无法导出备份")

        val withSlots = courseDao.coursesWithSlotsOnce(semester.id)
        BackupPayload(
            semester = semester,
            courses = withSlots.map { it.course },
            slots = withSlots.flatMap { it.slots },
            periods = semesterDao.periodsOnce(semester.id),
            notes = noteDao.getAll(),
            todos = todoDao.getAll()
        )
    }

    /**
     * 用备份数据**整体替换**当前数据。
     *
     * ## 为什么是"清空再重建"而不是合并
     *
     * 合并要处理 id 冲突、同名课程、时段重复，是一大堆判断，
     * 而且很难让用户理解合并后的结果。真实场景是"换手机 / 重装"，
     * 覆盖就够，语义也清楚：**导入 = 回到导出那一刻**。
     *
     * ## 为什么要重编 id（而不是保留原 id）
     *
     * 清空之后原来的 id 都能用，保留也行。但重编更稳：
     * 不依赖 `sqlite_sequence` 的状态，也不会和"万一没删干净"的残留撞车。
     * 代价是课程 id 变了，所以要维护一张 `旧 id → 新 id` 的映射表，
     * 时间段和待办的 `courseId` 都要跟着改。
     *
     * ## 删除顺序
     *
     * 严格按"子表 → 父表"：course_slot → course → period_time → semester。
     * 不依赖 `ON DELETE CASCADE` 是否开启，也不依赖外键约束的开关状态。
     *
     * 整个方法是一个事务：中途任何一步失败都会全部回滚，
     * 用户原来的数据一条不少。
     */
    suspend fun restoreBackup(payload: BackupPayload) = db.withTransaction {
        courseDao.deleteAllSlots()
        courseDao.deleteAllCourses()
        semesterDao.deleteAllPeriods()
        semesterDao.deleteAllSemesters()
        noteDao.deleteAll()
        todoDao.deleteAll()

        val newSemesterId = semesterDao.insert(payload.semester.copy(id = 0L))

        // 作息：section 按原样保留（文件里已经是连续的 1..N）
        semesterDao.insertPeriods(
            payload.periods.map { it.copy(id = 0L, semesterId = newSemesterId) }
        )

        // 课程：先建映射，再插时间段，否则时间段的 courseId 没有落点
        val idMap = HashMap<Long, Long>(payload.courses.size)
        payload.courses.forEach { course ->
            val newId = courseDao.insertCourse(course.copy(id = 0L, semesterId = newSemesterId))
            idMap[course.id] = newId
        }
        if (payload.slots.isNotEmpty()) {
            courseDao.insertSlots(
                payload.slots.map { it.copy(id = 0L, courseId = idMap.getValue(it.courseId)) }
            )
        }

        if (payload.notes.isNotEmpty()) {
            noteDao.insertAll(payload.notes.map { it.copy(id = 0L) })
        }
        if (payload.todos.isNotEmpty()) {
            // courseId 是软关联，映射不到就置空——课程被删过，待办还要留着
            todoDao.insertAll(
                payload.todos.map { it.copy(id = 0L, courseId = it.courseId?.let(idMap::get)) }
            )
        }
    }

    /**
     * 与数据库无关的常量。放在 `companion object` 里，
     * 调用方式是 `ScheduleRepository.DEFAULT_TOTAL_WEEKS`（不需要实例）。
     *
     * `const val` 是 Kotlin 的**编译期常量**，等价于 C++ 的
     * `static constexpr`——编译时直接把值内联到使用处，没有运行时开销。
     */
    companion object {
        /** 默认学期周数。国内高校一学期通常 16-20 周，取 20 是上界。 */
        const val DEFAULT_TOTAL_WEEKS = 20

        /**
         * 默认作息：上午 4 节 + 下午 4 节 + 晚上 4 节，共 12 节。
         *
         * 这里用的是 [Pair]（`480 to 525` 是 `Pair(480, 525)` 的简写）
         * 而不是直接建 [PeriodTime] 对象，因为这些数据在建表前就知道 `semesterId`
         * 了，但 `section` 需要靠下标算，[defaultPeriods] 里一次性转换更清楚。
         *
         * 右边注释里的时间是人肉换算出来的（480 分钟 = 8 小时 = 08:00），
         * 改这里的数字时**务必同步改注释**，否则以后没人看得懂。
         *
         * 这只是默认值，用户可以在设置页自由增删改。
         */
        private val DEFAULT_PERIOD_RANGES = listOf(
            480 to 525,   // 1  08:00 - 08:45
            535 to 580,   // 2  08:55 - 09:40
            600 to 645,   // 3  10:00 - 10:45
            655 to 700,   // 4  10:55 - 11:40
            840 to 885,   // 5  14:00 - 14:45
            895 to 940,   // 6  14:55 - 15:40
            960 to 1005,  // 7  16:00 - 16:45
            1015 to 1060, // 8  16:55 - 17:40
            1140 to 1185, // 9  19:00 - 19:45
            1195 to 1240, // 10 19:55 - 20:40
            1250 to 1295, // 11 20:50 - 21:35
            1305 to 1350  // 12 21:45 - 22:30
        )

        /**
         * 把 [DEFAULT_PERIOD_RANGES] 转成实体列表，顺便按 `mapIndexed`
         * 的下标把 `section` 编成 1、2、3…（从 1 开始，所以是 `index + 1`）。
         *
         * 那个 `(start, end)` 是 Kotlin 的**解构声明**：
         * `Pair` 能被自动拆成两个变量，等价于 C++ 的
         * `auto [start, end] = ranges[i];`（结构化绑定）。
         */
        private fun defaultPeriods(semesterId: Long): List<PeriodTime> =
            DEFAULT_PERIOD_RANGES.mapIndexed { index, (start, end) ->
                PeriodTime(
                    semesterId = semesterId,
                    section = index + 1,
                    startMinute = start,
                    endMinute = end
                )
            }
    }
}
