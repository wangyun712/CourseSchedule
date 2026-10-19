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
 *
 * ## `@Dao` 是什么
 *
 * 加了 `@Dao` 的 **interface**，Room 会在编译期生成它的实现类
 * （`SemesterDao_Impl`）。你只写方法签名和 SQL，不用写一行实现代码。
 * 生成的实现负责：把 SQL 参数绑定进去、把查询结果列映射成 Kotlin 对象
 * （靠的是实体上的 `@PrimaryKey` 和字段名匹配）、以及返回 `Flow` 时
 * 注册数据库变更监听。
 *
 * 正因为是生成代码，**SQL 写错会在编译期就报错**（而不是运行到那一行才崩）。
 * 表名、列名打错一个字母都过不了编译，这是 Room 相比裸 SQLite 最大的好处。
 *
 * ## 为什么方法名有的带 `suspend`、有的不带
 *
 * 这是最需要记住的一条规则：
 *
 * | 返回类型 | 要 `suspend` 吗 | 为什么 |
 * |---|---|---|
 * | `Flow<T>` | **不要** | 它只是"建好一条管道"就返回了，不阻塞任何线程 |
 * | 具体值（`Int` / `Semester?` / `Long`） | **要** | 真的要去读盘，必须能挂起 |
 *
 * 写成 `suspend fun observeCurrent(): Flow<Semester?>` 是错的（虽然有时能编译过，
 * 但调用方会莫名其妙地需要协程才能拿到一个 Flow），反之
 * `fun current(): Semester?` 则编译不过——Room 会直接报错告诉你
 * "这个方法要读数据库，必须加 suspend 或者返回 Flow"。
 */
@Dao
interface SemesterDao {

    /**
     * 观察当前学期，**响应式**：库里的学期数据一变，Flow 就发新值。
     *
     * `ORDER BY id LIMIT 1` 是"只支持单学期"这个设计的具体体现——
     * 表里理论上只有一行，取 id 最小的那个。
     * 排序是为了结果**确定性**：不加 ORDER BY 时 SQLite 不保证返回哪一行，
     * 万一以后插了第二行，行为会变得不可预测。
     *
     * @return 可空的 `Semester?`。首次启动时表是空的，会先发一个 `null`，
     *   等 [com.courseschedule.data.ScheduleRepository.ensureSemester] 写完
     *   再发一个真实值。**调用方必须处理 null 这一帧**。
     */
    @Query("SELECT * FROM semester ORDER BY id LIMIT 1")
    fun observeCurrent(): Flow<Semester?>

    /**
     * 一次性读当前学期，**非响应式**。
     *
     * ## 和 [observeCurrent] 的区别（这组对比最能说明 Flow 的意义）
     *
     * 两个方法的 SQL **一模一样**，只有返回类型不同，用途也完全不同：
     *
     * | | [observeCurrent] | [current] |
     * |---|---|---|
     * | 返回 | `Flow<Semester?>` | `Semester?` |
     * | 行为 | 持续观察，数据变就再发一次 | 取一次就结束 |
     * | 用在哪 | 界面要跟着数据自动刷新 | 判断"有没有学期"这种一次性检查 |
     * | 要 suspend 吗 | 不要 | 要 |
     *
     * 具体到这个项目：[com.courseschedule.data.ScheduleRepository.ensureSemester]
     * 开头用它做提前返回的判断（"已经有学期就不用建了"），
     * 这种场景不需要持续观察，用 Flow 反而是浪费。
     */
    @Query("SELECT * FROM semester ORDER BY id LIMIT 1")
    suspend fun current(): Semester?

    /**
     * 插入一个学期，返回数据库分配的自增 id。
     *
     * @Insert 会根据实体上的 `@PrimaryKey(autoGenerate = true)` 自动处理：
     * 传入对象的 `id` 是 `0L`（默认值）时生成新 id，非 0 时按指定 id 插入。
     *
     * 返回值必须用 `suspend` 接收（这里是 `Long`），因为
     * [com.courseschedule.data.ScheduleRepository.ensureSemester]
     * 需要拿到这个 id 去建默认作息的关联。
     */
    @Insert
    suspend fun insert(semester: Semester): Long

    /**
     * 按主键更新学期。
     *
     * `@Update` 生成的 SQL 是 `UPDATE semester SET <所有列> WHERE id = ?`，
     * 也就是说它**整行覆盖**，不是局部更新。所以调用时必须传一个字段完整的
     * [Semester] 对象——如果只填了一半字段就去 update，剩下的字段会被
     * 写成本类型的默认值（不是保留原值）。
     *
     * 项目里的做法是：ViewModel 里持有完整的 `SettingsUiState`，
     * 保存时用当前状态重建一个完整的 `Semester`，不走"读出来改一个字段再写回"。
     */
    @Update
    suspend fun update(semester: Semester)

    /**
     * 观察某个学期的节次作息，按节次号排序。
     *
     * `ORDER BY section` 很重要：周视图左侧的节次列是按这个顺序从上往下画的，
     * 顺序错了整列时间都会对不上号。
     */
    @Query("SELECT * FROM period_time WHERE semesterId = :semesterId ORDER BY section")
    fun observePeriods(semesterId: Long): Flow<List<PeriodTime>>

    /**
     * 数一下某个学期有多少节作息。**目前没有被调用**，是留着做数据校验的
     * （比如 P2 导入数据时，检查导入的作息条数和现有的是否一致）。
     *
     * 注意这个 SQL 里的 `:semesterId` 是**命名参数**——靠名字和方法参数对应，
     * 不靠位置。所以参数顺序可以随便调，写法比 `?1` 这种位置参数安全。
     */
    @Query("SELECT COUNT(*) FROM period_time WHERE semesterId = :semesterId")
    suspend fun periodCount(semesterId: Long): Int

    /**
     * 批量插入作息。
     *
     * 参数是 `List<PeriodTime>` 时，Room 生成的实现会**在一个事务里**
     * 循环执行 INSERT，比在 Kotlin 层 `forEach { insert(it) }` 快得多
     * （省掉了每次调用的跨层开销，也只提交一次事务）。
     *
     * 所以批量写数据时**优先用列表参数**，不要自己 for 循环调单条插入。
     */
    @Insert
    suspend fun insertPeriods(periods: List<PeriodTime>)

    /**
     * 删掉某个学期的全部作息。
     *
     * 只在 [com.courseschedule.data.ScheduleRepository.savePeriods]
     * 的"先删后插"里用，**不要单独调用它**——
     * 删完没插回去的话，课表左边的节次列就空了，
     * 而且因为学期还在，`ensureSemester` 不会重建作息，属于不可自愈的状态。
     */
    @Query("DELETE FROM period_time WHERE semesterId = :semesterId")
    suspend fun deletePeriods(semesterId: Long)
}
