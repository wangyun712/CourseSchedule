package com.courseschedule.data

import androidx.room.withTransaction
import com.courseschedule.core.WeekCalculator
import com.courseschedule.data.backup.BackupPayload
import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.CourseSlot
import com.courseschedule.data.entity.CourseWithSlots
import com.courseschedule.data.entity.Note
import com.courseschedule.data.entity.PeriodTime
import com.courseschedule.data.entity.Semester
import com.courseschedule.data.entity.Todo
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * 单一数据源：UI 层只经这里读写数据库，不直接碰 DAO 和 `AppDatabase`。
 * 规矩：ViewModel 只认 Repository、不 import 任何 DAO（可 grep 验证）。
 * 只依赖 `AppDatabase`、不持有 Context，数据库实例由 `AppContainer` 传入。
 * 硬规矩：读一律返回 Flow，数据一变订阅方自动收到新值，UI 无需手动刷新；
 * 写一律 suspend，写库是 I/O，不能阻塞主线程。
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

    // ---------- 读（Flow） ----------

    /**
     * 观察当前学期。可能为 null：首次启动 `ensureSemester()` 尚未完成时表里是空的，
     * 调用方必须先处理 null。用 Flow 而非一次性读取，是为了设置页改了学期后
     * 课表页自动跟着变，不需要跨页面通知机制。
     */
    fun observeSemester(): Flow<Semester?> = semesterDao.observeCurrent()

    /**
     * 观察某学期的节次作息，左侧"第 n 节 08:00"一列的数据来源。
     * 现在虽只有一个学期仍要求传 `semesterId`：把学期写死的话，
     * 以后支持多学期就得改所有调用点。
     */
    fun observePeriods(semesterId: Long): Flow<List<PeriodTime>> =
        semesterDao.observePeriods(semesterId)

    /**
     * 观察某学期全部课程，每条自带全部时间段，周视图的主数据源。
     * 用 `CourseWithSlots` 而非裸 `Course`，UI 一次拿全、避免二次查询（N+1）。
     * 按课程名排序在 DAO 的 SQL 里完成，课程数量变化时顺序不乱跳。
     */
    fun observeCourses(semesterId: Long): Flow<List<CourseWithSlots>> =
        courseDao.observeCourses(semesterId)

    /** 观察便签条数。设置页"数据"一栏在用。 */
    fun observeNoteCount(): Flow<Int> = noteDao.observeCount()

    /** 观察待办条数。用途同 [observeNoteCount]。 */
    fun observeTodoCount(): Flow<Int> = todoDao.observeCount()

    /**
     * 观察全部便签，置顶在前、其余按最近修改倒序（排序在 DAO 的 SQL 里做）。
     * 这里只转发、不要再排一次，否则改顺序要动两处且容易互相矛盾。
     */
    fun observeNotes(): Flow<List<Note>> = noteDao.observeAll()

    /** 按 id 取一条便签，一次性读取。编辑页初始化用（理由同 [courseWithSlots]）。 */
    suspend fun note(id: Long): Note? = noteDao.byId(id)

    /** 观察当前学期有多少门课。设置页"数据"那一栏在用。 */
    fun observeCourseCount(): Flow<Int> = courseDao.observeCourseCount()

    /**
     * 按 id 取一门课及其全部时间段，一次性读取，用于 `CourseEditViewModel` 初始化。
     * 刻意不用 Flow：编辑页是表单，订阅变化会把用户正在输入的内容覆盖掉，
     * 只在进页读一次、点保存时写回。找不到返回 null（如课程已被其他入口删除），
     * 调用方需处理 `notFound` 分支。
     */
    suspend fun courseWithSlots(courseId: Long): CourseWithSlots? =
        courseDao.courseWithSlots(courseId)

    // ---------- 写 ----------

    /**
     * 确保有学期可用：首次启动时建默认学期 + 一套默认作息。`ScheduleViewModel` 与
     * `SettingsViewModel` 的 init 都会调它，开头已有学期直接返回，重复调用安全。
     * 先查一次再进事务是为正常启动省掉事务开销；事务必须包住"插学期 + 插 12 条作息"，
     * 否则中途失败留下"有学期没作息"的半截数据且永不自愈。开学日期取本周一而非今天，周次才不随星期偏移（仅默认值，设置页可改）。
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
     * 必须用 update 而不能换成先删后插：`Semester.id` 被 `course`、`period_time`
     * 当外键引用，删掉再插会拿到新 id，CASCADE 会把课程和作息一并删光。
     */
    suspend fun saveSemester(semester: Semester) = semesterDao.update(semester)

    /**
     * 整表替换某学期的节次作息。用"先删后插"不做 diff：作息总共十几条，增量更新要算
     * 增/改/删三类还得维护 id 映射，用数据量换复杂度。副作用：`section` 按列表下标
     * +1 重编（调用方 `SettingsViewModel.save` 做），删中间一节则后面节号整体前移、课
     * 跟着前移——刻意为之（`CourseSlot` 存节次序号）；传入项 `id`、`semesterId` 被忽略重写。
     */
    suspend fun savePeriods(semesterId: Long, periods: List<PeriodTime>) {
        db.withTransaction {
            semesterDao.deletePeriods(semesterId)
            semesterDao.insertPeriods(periods.map { it.copy(id = 0L, semesterId = semesterId) })
        }
    }

    /**
     * 保存一门课及其全部时间段，加课/编辑共用，本类最重要的写方法。
     * `course.id == 0L` 表示新建（走 insert 拿新 id），否则走 update，返回课程 id。
     * 时间段用"先删后插"不做 diff（同 `savePeriods`，数据量换复杂度），UI 只需提交
     * 最终形态。必须包在同一事务里：插课程 → 删旧时间段 → 插新时间段，三步不原子的
     * 话，"删完旧的还没插新的"时崩溃会表现为课还在、格子全空，看起来像丢数据。
     * `slots` 的 `id`、`courseId` 被忽略重写；空列表合法（课保留但课表上不显示）。
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
     * 删除一门课（物理删除、不可撤销，界面有二次确认弹窗）。
     * 不用手动删时间段：`course_slot` 声明了 `onDelete = CASCADE`，会跟着一起删。
     */
    suspend fun deleteCourse(courseId: Long) = courseDao.deleteCourse(courseId)

    // ---------- 便签 ----------

    /**
     * 保存一条便签，`id == 0L` 是新建，否则覆盖已有那条。
     * `updatedAt` 必须由数据层赋值：修改时间属于数据规则而非界面职责，
     * 让界面传的话换个入口就会漏赋、排序立刻乱。
     * trim 在落库前做而非输入时裁（输入时裁会让用户打不出空格）：粘贴常带首尾换行，
     * 不裁会让列表出现大片空白行。
     */
    suspend fun saveNote(note: Note): Long = noteDao.upsert(
        note.copy(
            title = note.title.trim(),
            content = note.content.trim(),
            updatedAt = System.currentTimeMillis()
        )
    )

    /** 删除一条便签。物理删除，界面上有二次确认。 */
    suspend fun deleteNote(noteId: Long) = noteDao.deleteById(noteId)

    /**
     * 切换置顶。参数传目标值而非由界面取反：界面的 `Note` 可能已被
     * 后台刷新弄成旧值，取反会把过期状态写回去。
     */
    suspend fun setNotePinned(noteId: Long, pinned: Boolean) = noteDao.setPinned(noteId, pinned)

    // ---------- 待办 ----------

    /**
     * 观察全部待办，顺序：未完成在前 → 有截止日期且近的在前 → id 大的在前（SQL 里排好）。
     * 不在这里做"未完成/已完成"拆分——那是界面分组需求，ViewModel 一次 filter 即可。
     */
    fun observeTodos(): Flow<List<Todo>> = todoDao.observeAll()

    /** 按 id 取一条待办，一次性读取。编辑页初始化用。 */
    suspend fun todo(id: Long): Todo? = todoDao.byId(id)

    /**
     * 保存一条待办，`id == 0L` 是新建。不像 `saveNote` 会赋 `updatedAt`：
     * 待办表没有修改时间字段，`dueAt`/`remindAt` 由调用方原样带回。
     * 标题 trim 理由同 `saveNote`。
     */
    suspend fun saveTodo(todo: Todo): Long = todoDao.upsert(todo.copy(title = todo.title.trim()))

    /** 勾选 / 取消勾选。 */
    suspend fun setTodoDone(todoId: Long, done: Boolean) = todoDao.setDone(todoId, done)

    /** 删除一条待办。物理删除，界面上有二次确认。 */
    suspend fun deleteTodo(todoId: Long) = todoDao.deleteById(todoId)

    // ---------- 备份 / 恢复 ----------

    /**
     * 把当前全部数据读成快照供导出，`BackupManager` 调用。
     * 读取必须包在同一事务里，保证是同一时刻的数据：否则读到一半有写入，
     * 外键会指向没写进文件的记录，导入校验会拦下整个文件。学期未建时抛错（正常不会发生）。
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
     * 用备份数据整体替换当前数据：导入 = 回到导出那一刻，不做合并（合并要处理
     * id 冲突、同名课程等一大堆判断，真实场景就是换机/重装）。删除严格按"子表 → 父表"
     * （course_slot → course → period_time → semester），不依赖 CASCADE 或外键开关
     * 状态；整个方法在一个事务里，中途失败全部回滚。
     * 插入时全部重编 id（不依赖 `sqlite_sequence` 状态），要维护"旧课程 id → 新 id"
     * 映射，时间段的 `courseId`、待办的软关联都按它改写。
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

    companion object {
        /** 默认学期周数。国内高校一学期通常 16-20 周，取 20 是上界。 */
        const val DEFAULT_TOTAL_WEEKS = 20

        /**
         * 默认作息：上午 4 节 + 下午 4 节 + 晚上 4 节，共 12 节，值为(起始分钟, 结束分钟)。
         * 右侧行尾注释的时间是人肉换算的（480 分钟 = 08:00），改数字务必同步改注释。
         * 这里只存区间，`section` 由 `defaultPeriods` 按下标编；仅默认值，设置页可增删改。
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

        /** 把 [DEFAULT_PERIOD_RANGES] 转成实体列表，`section` 按下标从 1 开始编号。 */
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
