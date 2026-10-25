package com.courseschedule.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.courseschedule.data.entity.PeriodTime
import com.courseschedule.data.entity.Semester
import kotlinx.coroutines.flow.Flow

/**
 * 学期与节次作息的读写接口。
 * 读写分离规矩（全部 DAO 通用）：返回 `Flow` 的方法不加 `suspend`——Flow 只是建好管道
 * 就返回，不阻塞；返回具体值的方法必须加 `suspend`——真的要读盘，不加时 Room 编译报错。
 */
@Dao
interface SemesterDao {

    /**
     * 观察当前学期（响应式）。`ORDER BY id LIMIT 1` 对应"只支持单学期"的设计，排序保证结果确定。
     * 首次启动表为空会先发 `null`，等 `ScheduleRepository.ensureSemester` 写完再发真实值，
     * 调用方必须处理 null 这一帧。
     */
    @Query("SELECT * FROM semester ORDER BY id LIMIT 1")
    fun observeCurrent(): Flow<Semester?>

    /**
     * 一次性读当前学期，非响应式。SQL 与 `observeCurrent` 相同，但取一次就结束。
     * 适合"有没有学期"这类一次性判断（如 `ensureSemester` 的提前返回），用 Flow 反而是浪费。
     */
    @Query("SELECT * FROM semester ORDER BY id LIMIT 1")
    suspend fun current(): Semester?

    /**
     * 插入一个学期，返回自增 id。
     * 实体 `id` 为默认 `0L` 时生成新 id，非 0 时按指定 id 插入；返回的 id 供 `ensureSemester` 建默认作息。
     */
    @Insert
    suspend fun insert(semester: Semester): Long

    /**
     * 按主键更新学期。`@Update` 是整行覆盖而非局部更新：只填一半字段，其余字段会被
     * 写成默认值（不是保留原值）。项目做法是保存时用完整 UI 状态重建整个 `Semester`。
     */
    @Update
    suspend fun update(semester: Semester)

    /**
     * 观察某学期的节次作息。`ORDER BY section` 对应周视图左侧节次列的绘制顺序，
     * 顺序错了整列时间都对不上号。
     */
    @Query("SELECT * FROM period_time WHERE semesterId = :semesterId ORDER BY section")
    fun observePeriods(semesterId: Long): Flow<List<PeriodTime>>

    /** 某学期的作息条数。目前没有被调用，留着做导入数据时的校验。 */
    @Query("SELECT COUNT(*) FROM period_time WHERE semesterId = :semesterId")
    suspend fun periodCount(semesterId: Long): Int

    /**
     * 批量插入作息。列表参数时 Room 自动包在一个事务里循环插入，
     * 比逐条调用快得多，批量写优先用这种形式。
     */
    @Insert
    suspend fun insertPeriods(periods: List<PeriodTime>)

    /**
     * 删掉某学期的全部作息，只在 `savePeriods` 的"先删后插"里用，不要单独调用：
     * 删完没插回去，节次列就空了，且学期还在、`ensureSemester` 不会重建作息，不可自愈。
     */
    @Query("DELETE FROM period_time WHERE semesterId = :semesterId")
    suspend fun deletePeriods(semesterId: Long)

    // ---------- 以下两个方法只服务恢复备份 ----------

    /**
     * 清空全部作息（不区分学期）。恢复备份是整库替换，不能用需要 `semesterId` 的 `deletePeriods`。
     */
    @Query("DELETE FROM period_time")
    suspend fun deleteAllPeriods()

    /**
     * 清空学期表。必须在 `CourseDao.deleteAllCourses` 之后调用：恢复备份的删除顺序
     * 严格按"子表 → 父表"，不依赖级联删除是否开启。
     */
    @Query("DELETE FROM semester")
    suspend fun deleteAllSemesters()

    /**
     * 一次性读出某学期的作息，**给导出备份用**。
     * 不用 [observePeriods] 的理由同 [CourseDao.coursesWithSlotsOnce]。
     */
    @Query("SELECT * FROM period_time WHERE semesterId = :semesterId ORDER BY section")
    suspend fun periodsOnce(semesterId: Long): List<PeriodTime>
}
