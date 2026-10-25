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
 * 课程与时间段的读写接口，周视图、加课页、设置页的课程计数都走它。
 * 读写分离规矩：返回 `Flow` 的方法不加 `suspend`（管道，不阻塞）；
 * 返回具体值的方法必须加 `suspend`（真的读盘）。详细原因见 `SemesterDao` 的类注释。
 */
@Dao
interface CourseDao {

    /**
     * 观察某学期的全部课程（每条带全部时间段），周视图的核心查询。
     *
     * `@Transaction` 必需：`CourseWithSlots` 含 `@Relation`，Room 内部要执行两条 SQL
     * （一条查课程、一条按课程 id 批量查时间段），放进同一事务才能看到同一数据快照，
     * 否则可能查出"旧课程 + 新时间段"对不上号的结果。
     * 排序用 `name` 而非 `id`：加新课只插到字母附近，列表整体顺序稳定。
     */
    @Transaction
    @Query("SELECT * FROM course WHERE semesterId = :semesterId ORDER BY name")
    fun observeCourses(semesterId: Long): Flow<List<CourseWithSlots>>

    /**
     * 按 id 取一门课和它的时间段，一次性读取，加课页初始化用；找不到返回 `null`。
     * 必须用 `suspend` 返回具体值而非 `Flow`：返回 Flow 的话数据库一变就会覆盖
     * 用户正在输入的表单（详见 `ScheduleRepository.courseWithSlots`）。
     * 同样需要 `@Transaction`（`CourseWithSlots` 含 `@Relation`）。
     */
    @Transaction
    @Query("SELECT * FROM course WHERE id = :courseId")
    suspend fun courseWithSlots(courseId: Long): CourseWithSlots?

    /** 插入一门课程，返回数据库分配的 id，供 `ScheduleRepository.saveCourse` 给时间段填 `courseId`。 */
    @Insert
    suspend fun insertCourse(course: Course): Long

    /** 按主键整行覆盖更新，必须传字段完整的对象，理由同 `SemesterDao.update`。 */
    @Update
    suspend fun updateCourse(course: Course)

    /**
     * 删掉一门课。时间段不用手动删：`course_slot` 外键声明了 `onDelete = CASCADE`，
     * SQLite 自动连带删除，这是数据库层的保证。
     */
    @Query("DELETE FROM course WHERE id = :courseId")
    suspend fun deleteCourse(courseId: Long)

    /**
     * 批量插入时间段。列表参数时 Room 会自动包在一个事务里循环插入，
     * 比逐条调用快得多，批量写永远优先用这种形式。
     * 与 `deleteSlots` 配合构成 `ScheduleRepository.saveCourse` 的"先删后插"。
     */
    @Insert
    suspend fun insertSlots(slots: List<CourseSlot>)

    /**
     * 删掉某门课的全部时间段，只与 `insertSlots` 配套构成"先删后插"。
     * 不要单独调用：删完没插回去，课程还在库里但课表上一个色块都没有，用户会以为数据丢了。
     */
    @Query("DELETE FROM course_slot WHERE courseId = :courseId")
    suspend fun deleteSlots(courseId: Long)

    /**
     * 课程总数，设置页"数据"栏在用。没有 `WHERE semesterId`，是全表计数：
     * 当前单学期设计下等价，以后支持多学期时需补条件。
     */
    @Query("SELECT COUNT(*) FROM course")
    fun observeCourseCount(): Flow<Int>

    // ---------- 以下两个方法只服务恢复备份 ----------

    /**
     * 清空全部时间段。恢复备份是整库替换，删除顺序严格按"子表 → 父表"：
     * 先 `course_slot`、再 `course`、最后 `semester`，不依赖级联删除是否开启。
     */
    @Query("DELETE FROM course_slot")
    suspend fun deleteAllSlots()

    /** 清空全部课程。必须在 [deleteAllSlots] 之后调用。 */
    @Query("DELETE FROM course")
    suspend fun deleteAllCourses()

    /**
     * 一次性读出某学期的全部课程 + 时间段，给导出备份用。SQL 与 `observeCourses` 相同。
     * 不复用 Flow 再 `.first()`：导出跑在 `withTransaction` 里，同一事务里只用 suspend 查询，
     * 避免混用 Flow 查询的另一套执行器导致锁等待。
     */
    @Transaction
    @Query("SELECT * FROM course WHERE semesterId = :semesterId ORDER BY name")
    suspend fun coursesWithSlotsOnce(semesterId: Long): List<CourseWithSlots>
}
