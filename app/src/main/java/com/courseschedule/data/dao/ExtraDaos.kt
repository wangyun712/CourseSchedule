package com.courseschedule.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.courseschedule.data.entity.Note
import com.courseschedule.data.entity.Todo
import kotlinx.coroutines.flow.Flow

/**
 * 便签与待办的两个 DAO。
 *
 * ## 为什么现在只有这么点方法
 *
 * 表在 P1 就建好了（理由见 [com.courseschedule.data.entity.Note] 的注释——
 * 避开 Room 的 migration），但界面要到 P3 / P4 才做。
 * 所以这里目前只声明了**够用最小集合**：
 *
 * - `observeCount()`：给占位页用，**证明这两张表确实能读**，不是画饼。
 * - `insert()`：证明能写。虽然没有界面调用它，但有了它，
 *   P3 / P4 只需要在这个接口里继续加方法，不用动数据库结构。
 *
 * ## 为什么不"一次把 P3/P4 要用的方法都写完"
 *
 * 因为没写界面的代码就是没验证过的代码。现在按想象中的需求写一堆
 * `queryByDateRange`、`togglePinned` 之类的方法，等到真做界面时
 * 大概率发现签名不对、要全改，而且每多一个方法就多一份编译期的 SQL 校验成本。
 *
 * 现在这个状态是"结构定型、接口最小"——数据库不用再动，接口随时能加。
 */
@Dao
interface NoteDao {

    /**
     * 观察便签总数。
     *
     * 用 `Flow<Int>` 而不是 `suspend fun`：条数变化时占位页要自动更新。
     * P3 做了便签列表之后，这个也能直接拿来做"首页角标"或"全部/置顶"页签的计数。
     */
    @Query("SELECT COUNT(*) FROM note")
    fun observeCount(): Flow<Int>

    /**
     * 插入一条便签，返回新 id。
     *
     * 现在没有界面调用它。留着的作用是证明 `note` 表可写，
     * 并且让 P3 有个直接的落地点。
     */
    @Insert
    suspend fun insert(note: Note): Long

    // ---------- 以下三个方法只服务备份/恢复 ----------

    /**
     * 读出全部便签，**给导出用**。
     *
     * 不加 `ORDER BY`：导出的是数据快照，顺序由 JSON 数组本身承载，
     * 导入时也不会依赖顺序。真正需要排序的是列表界面（P3），
     * 那是另一条查询，不要为了复用把排序硬塞进这里。
     */
    @Query("SELECT * FROM note")
    suspend fun getAll(): List<Note>

    /** 清空便签表。**只在恢复备份的事务里调用**，别的地方调用它等于删库。 */
    @Query("DELETE FROM note")
    suspend fun deleteAll()

    /** 批量插入。恢复时用，Room 会在一个事务里循环插入。 */
    @Insert
    suspend fun insertAll(notes: List<Note>)
}

/**
 * 待办 DAO。方法集合和 [NoteDao] 一致，理由也相同——见上面的说明。
 */
@Dao
interface TodoDao {

    /**
     * 观察待办总数。
     *
     * 注意它数的是**全部待办（含已完成）**。P4 做界面时大概率还需要一个
     * "只看未完成"的计数，那时候在 SQL 里加 `WHERE done = 0` 再加一个方法即可，
     * 不影响这个。
     */
    @Query("SELECT COUNT(*) FROM todo")
    fun observeCount(): Flow<Int>

    /**
     * 插入一条待办，返回新 id。
     *
     * P4 的提醒功能会需要这个返回值——拿到 id 之后才能把
     * `AlarmManager` 的闹钟和这条待办关联起来，
     * 取消或修改待办时才找得回要撤销的那个闹钟。
     * （注意 `PendingIntent` 需要用请求码做区分，所以这个 id 会用到。）
     */
    @Insert
    suspend fun insert(todo: Todo): Long

    // ---------- 以下三个方法只服务备份/恢复 ----------

    /** 读出全部待办，给导出用。排序理由同 [NoteDao.getAll]。 */
    @Query("SELECT * FROM todo")
    suspend fun getAll(): List<Todo>

    /** 清空待办表。只在恢复备份的事务里调用。 */
    @Query("DELETE FROM todo")
    suspend fun deleteAll()

    /** 批量插入。恢复时用。 */
    @Insert
    suspend fun insertAll(todos: List<Todo>)
}
