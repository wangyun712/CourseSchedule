package com.courseschedule.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.courseschedule.data.entity.Note
import com.courseschedule.data.entity.Todo
import kotlinx.coroutines.flow.Flow

/** 便签 DAO。表在 P1 一次建全（避开 Room 的 migration），方法按界面需求逐步补充。 */
@Dao
interface NoteDao {

    /** 观察便签总数，设置页"数据"栏在用。 */
    @Query("SELECT COUNT(*) FROM note")
    fun observeCount(): Flow<Int>

    /** 插入一条便签，返回新 id。当前没有调用方，实际落库走 `upsert`。 */
    @Insert
    suspend fun insert(note: Note): Long

    /**
     * 便签列表主查询：置顶优先，再按最近修改倒序。
     * 排序写在 SQL 而非界面层：以后换成 `LIMIT` 分页，顺序仍然全局正确。
     */
    @Query("SELECT * FROM note ORDER BY pinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<Note>>

    /** 按 id 取一条，给编辑页做初始化（一次性读取）。 */
    @Query("SELECT * FROM note WHERE id = :id")
    suspend fun byId(id: Long): Note?

    /**
     * 保存便签（有 id 就覆盖，没有就插入）。
     * 用 `REPLACE` 是因为全部字段由编辑页一次性提交，整行覆盖正好对应语义；
     * REPLACE 是先删后插，但显式传了 id，新行 id 不变、不跳号，
     * 且便签表没有被任何表外键引用，先删不会连带删别的数据。
     * **如果以后有表引用 note，这里必须改成 `@Update` + `@Insert`。**
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: Note): Long

    /** 删除一条便签。物理删除，界面上有二次确认。 */
    @Query("DELETE FROM note WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 只改置顶标记，不动内容和 `updatedAt`：置顶不应刷新修改时间，否则排序会莫名变化。 */
    @Query("UPDATE note SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    // ---------- 以下三个方法只服务备份/恢复 ----------

    /** 读出全部便签给导出用。不加 `ORDER BY`：导出是数据快照，顺序由 JSON 数组承载。 */
    @Query("SELECT * FROM note")
    suspend fun getAll(): List<Note>

    /** 清空便签表。**只在恢复备份的事务里调用**，别的地方调用它等于删库。 */
    @Query("DELETE FROM note")
    suspend fun deleteAll()

    /** 批量插入。恢复时用，Room 会在一个事务里循环插入。 */
    @Insert
    suspend fun insertAll(notes: List<Note>)
}

/** 待办 DAO，方法集合与 `NoteDao` 一致。 */
@Dao
interface TodoDao {

    /** 观察待办总数（含已完成），设置页"数据"栏在用。 */
    @Query("SELECT COUNT(*) FROM todo")
    fun observeCount(): Flow<Int>

    /** 插入一条待办，返回新 id。当前没有调用方，实际落库走 `upsert`。 */
    @Insert
    suspend fun insert(todo: Todo): Long

    /**
     * 待办列表主查询。排序分四段，顺序不能乱：
     * 1. `done ASC`：未完成在前，已完成的沉底；
     * 2. `CASE WHEN dueAt IS NULL THEN 1 ELSE 0 END`：有截止日期的在前——直接 `dueAt ASC`
     *    会把 `NULL` 当最小值排到最前，"没截止日期的杂事"会顶掉最近要到期的作业；
     * 3. `dueAt ASC`：同有截止日期的，先到期在前；
     * 4. `id DESC`：同一时刻新增的，新的在前。
     */
    @Query(
        "SELECT * FROM todo ORDER BY done ASC, " +
            "CASE WHEN dueAt IS NULL THEN 1 ELSE 0 END, dueAt ASC, id DESC"
    )
    fun observeAll(): Flow<List<Todo>>

    /** 按 id 取一条，编辑页初始化用。 */
    @Query("SELECT * FROM todo WHERE id = :id")
    suspend fun byId(id: Long): Todo?

    /**
     * 保存待办（`id == 0` 插入，否则整行覆盖），`REPLACE` 理由同 `NoteDao.upsert`。
     * 注意整行覆盖会把没传的字段写成默认值：编辑必须先读出原对象再 `copy`
     * （见 `TodoEditViewModel`），否则 `remindAt` / `repeatRule` 会被悄悄清空。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(todo: Todo): Long

    /** 只改完成状态。列表页勾选走这里，不刷新其他字段。 */
    @Query("UPDATE todo SET done = :done WHERE id = :id")
    suspend fun setDone(id: Long, done: Boolean)

    /** 删除一条待办。物理删除，界面上有二次确认。 */
    @Query("DELETE FROM todo WHERE id = :id")
    suspend fun deleteById(id: Long)

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
