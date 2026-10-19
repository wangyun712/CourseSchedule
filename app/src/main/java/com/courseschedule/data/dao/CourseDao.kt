package com.courseschedule.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.CourseSlot
import com.courseschedule.data.entity.CourseWithSlots
import kotlinx.coroutines.flow.Flow

/**
 * 课程与时间段的读写接口。
 *
 * 这是数据层最"忙"的一个 DAO——周视图、加课页、设置页的课程计数都走它。
 *
 * ## 贯穿本接口的一条规矩：读写分离的写法不同
 *
 * - 要返回 `Flow<...>` 的方法**不加** `suspend`（返回的是管道，不阻塞）；
 * - 要返回具体值的方法**必须加** `suspend`（真的读盘）。
 *
 * 详细原因见 [SemesterDao] 的类注释。
 */
@Dao
interface CourseDao {

    /**
     * 观察某个学期下的所有课程，**每条课程带上它的全部时间段**。
     *
     * 这是整个 app 最核心的一条查询。周视图每次刷新都靠它。
     *
     * ## 为什么标了 `@Transaction`，以及它解决什么问题
     *
     * 返回类型 [CourseWithSlots] 里有一个 `@Relation` 列表字段。
     * Room 为了填这个列表，内部要执行**两条 SQL**：
     * 一条查课程、一条按课程 id 批量查时间段。
     *
     * 如果不标 `@Transaction`，这两条 SQL 之间可能被别的写操作插入
     * （比如用户在这几十微秒里加了一门课），结果就是
     * "查到了课程的旧快照 + 时间段的新快照"，两者对不上号——
     * 典型表现是某门课的时间段凭空多出来或少了一条，而且很难复现。
     *
     * `@Transaction` 把两条 SQL 放进同一个数据库事务，
     * 保证它们看到的是**同一个数据快照**。
     *
     * ## 为什么 `ORDER BY name` 而不是 `ORDER BY id`
     *
     * 按 id 排的话，新增的课总在最后，用户每次都会看到课程列表顺序变化。
     * 按名字排则相对稳定——加一门"线性代数"只会插到 L 字母附近，
     * 不会让整个列表重排。
     *
     * @param semesterId 只看哪个学期的课。虽然现在只有一个学期，
     *   但接口上仍然要传，避免以后支持多学期时改所有调用点。
     */
    @Transaction
    @Query("SELECT * FROM course WHERE semesterId = :semesterId ORDER BY name")
    fun observeCourses(semesterId: Long): Flow<List<CourseWithSlots>>

    /**
     * 按 id 取一门课和它的时间段，**一次性读取**。
     *
     * 用在加课页初始化：进来读一次把表单填好，之后完全由用户掌控。
     * 用 `suspend` + 具体返回值而不是 `Flow`，是刻意的——
     * 如果这里返回 Flow，数据库一有变化就会覆盖用户正在输入的文本框。
     * 理由详见 [com.courseschedule.data.ScheduleRepository.courseWithSlots]。
     *
     * 同样需要 `@Transaction`（[CourseWithSlots] 里有 `@Relation`）。
     *
     * @return 找不到返回 `null`。加课页会显示"没找到这门课"。
     */
    @Transaction
    @Query("SELECT * FROM course WHERE id = :courseId")
    suspend fun courseWithSlots(courseId: Long): CourseWithSlots?

    /**
     * 插入一门课程，返回数据库分配的 id。
     *
     * 返回值是 `Long` 而不是 `Unit`，因为
     * [com.courseschedule.data.ScheduleRepository.saveCourse] 需要拿它
     * 去给时间段表填 `courseId`。
     */
    @Insert
    suspend fun insertCourse(course: Course): Long

    /**
     * 按主键更新课程。**整行覆盖**，所以要传字段完整的对象
     * （理由同 [SemesterDao.update]，别只填要改的那个字段）。
     */
    @Update
    suspend fun updateCourse(course: Course)

    /**
     * 删掉一门课。
     *
     * **不用手动删它的时间段**——`course_slot` 表上的外键声明了
     * `onDelete = CASCADE`，SQLite 会自动连带删除。
     * 这是数据库层的保证，不依赖应用代码记得做这件事。
     */
    @Query("DELETE FROM course WHERE id = :courseId")
    suspend fun deleteCourse(courseId: Long)

    /**
     * 批量插入时间段。
     *
     * 列表参数时 Room 会在**一个事务里**循环插入，比 Kotlin 层
     * `forEach { insertSlot(it) }` 快得多。所以永远优先用批量形式。
     *
     * 配合 [deleteSlots] 使用，构成 [com.courseschedule.data.ScheduleRepository.saveCourse]
     * 里的"先删后插"。
     */
    @Insert
    suspend fun insertSlots(slots: List<CourseSlot>)

    /**
     * 删掉某门课的全部时间段。配合 [insertSlots] 构成"先删后插"。
     *
     * **不要单独调用它**——删完没插回去，那门课就会在课表上完全不可见
     * （课程还在数据库里，但一个色块都没有），用户会以为数据丢了。
     */
    @Query("DELETE FROM course_slot WHERE courseId = :courseId")
    suspend fun deleteSlots(courseId: Long)

    /**
     * 数一下总共有多少门课。设置页"数据"那一栏在用。
     *
     * 注意这里**没有 `WHERE semesterId = ...`**，是全表计数。
     * 在当前"只有一个学期"的设计下两者等价；
     * 如果以后支持多学期，这里需要补上学期的条件。
     */
    @Query("SELECT COUNT(*) FROM course")
    fun observeCourseCount(): Flow<Int>

    // ---------- 以下两个方法只服务恢复备份 ----------

    /**
     * 清空全部时间段。
     *
     * 恢复备份是整库替换，所以这里是全表删。**顺序很重要**：
     * 先删 `course_slot`、再删 `course`、最后删 `semester`，
     * 严格按"子表 → 父表"来，不依赖级联删除是否开启。
     */
    @Query("DELETE FROM course_slot")
    suspend fun deleteAllSlots()

    /** 清空全部课程。必须在 [deleteAllSlots] 之后调用。 */
    @Query("DELETE FROM course")
    suspend fun deleteAllCourses()

    /**
     * 一次性读出某学期的全部课程 + 时间段，**给导出备份用**。
     *
     * 和 [observeCourses] 的 SQL 一样，只是返回具体值而不是 Flow。
     * 为什么不复用 Flow 再 `.first()`：导出跑在 `withTransaction` 里，
     * 而 Flow 的查询走的是另一个执行器，在事务里混用两套调度器
     * 属于自找麻烦（极端情况下会锁等待）。**同一个事务里只用 suspend 查询。**
     */
    @Transaction
    @Query("SELECT * FROM course WHERE semesterId = :semesterId ORDER BY name")
    suspend fun coursesWithSlotsOnce(semesterId: Long): List<CourseWithSlots>
}
