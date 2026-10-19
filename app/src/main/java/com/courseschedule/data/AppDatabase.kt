package com.courseschedule.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.courseschedule.data.dao.CourseDao
import com.courseschedule.data.dao.NoteDao
import com.courseschedule.data.dao.SemesterDao
import com.courseschedule.data.dao.TodoDao
import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.CourseSlot
import com.courseschedule.data.entity.Note
import com.courseschedule.data.entity.PeriodTime
import com.courseschedule.data.entity.Semester
import com.courseschedule.data.entity.Todo

/**
 * 数据库定义。Room 会根据这个类**在编译期生成** `AppDatabase_Impl`，
 * 里面是真正的 SQL 建表语句和 DAO 实现。
 *
 * 这个类是 `abstract` 的、方法也是 `abstract` 的，因为**实现全部由注解处理器生成**。
 * 你在这里只负责三件事：
 * 1. 声明有哪些表（[entities]）；
 * 2. 声明数据库版本号（[version]）；
 * 3. 暴露 DAO 的获取入口（下面四个 `abstract fun`）。
 *
 * ## 六张表一次性建好（本项目一个关键的取舍）
 *
 * 便签 [Note] 和待办 [Todo] 在 P1 阶段**不写任何数据、也没有界面**，
 * 但表已经建在这里了。这么做是为了避开 Room 的 `Migration`。
 *
 * 加表的正常流程是：改实体 → 把 `version` 从 1 改成 2 → 写一个
 * `Migration(1, 2)` 告诉 Room "从旧库升到新库要执行哪些 SQL"。
 * 这一步**没有任何编译期检查**，写错了只有用户手机上升级时才会崩，
 * 而且崩在启动阶段，连界面都进不去。这是 Android 上最经典的事故来源之一。
 *
 * 现在 version = 1 就把六张表建全，P3 / P4 加功能时只需要写 DAO 和界面，
 * **一次 migration 都不用写**。代价是几十行代码和几个空表（不占存储空间）。
 *
 * ## 什么时候真的需要写 migration
 *
 * 给已有表**加字段**或**改字段类型**时。那种改动躲不掉，那时候：
 * 1. 去 `app/schemas/com.courseschedule.data.AppDatabase/1.json`
 *    看当前的表结构（这就是 [exportSchema] 存在的意义）；
 * 2. 把 `version` 加 1；
 * 3. 在 [com.courseschedule.ScheduleApp] 的 `databaseBuilder` 上
 *    `.addMigrations(MIGRATION_1_2)`；
 * 4. **务必在一台装着旧版本的设备上实测升级**，别只测全新安装。
 *
 * @property version 数据库版本号。**任何表结构的改动都必须让它 +1**，
 *   否则 Room 在运行期发现实体定义和已建好的表不一致时会抛
 *   `IllegalStateException: Room cannot verify the data integrity`。
 * @property exportSchema 是否把 schema 导出成 JSON。
 *   **不要关掉它**。打开之后，每次编译 Room 会把当前表结构写到
 *   `app/schemas/com.courseschedule.data.AppDatabase/<版本号>.json`。
 *   这个文件的作用是"未来写 migration 时的唯一依据"——
 *   没有它，你只能靠回忆去猜老版本长什么样。它是应该提交到版本库的。
 *
 * ## 关于下面那个 @TypeConverters
 *
 * 它注册在**类级别**（而不是字段级别），意思是：本项目所有实体里出现的
 * [com.courseschedule.data.entity.WeekType] 字段，都自动走
 * [Converters] 里那对函数，不用在每个字段上单独标注。
 */
@Database(
    entities = [
        Semester::class,
        Course::class,
        CourseSlot::class,
        PeriodTime::class,
        Note::class,
        Todo::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    /**
     * 学期 + 节次作息的读写入口。
     *
     * 这些方法都是 `abstract` 且**只声明不实现**，Room 会生成具体实现。
     * 每次调用返回的是同一个实例（Room 内部做了缓存），
     * 所以不用担心"反复调用创建对象"的性能问题，
     * 但也**不要**把它们保存成字段之外的长生命周期引用以免意外持有 Context。
     */
    abstract fun semesterDao(): SemesterDao

    /** 课程 + 时间段的读写入口。 */
    abstract fun courseDao(): CourseDao

    /** 便签的读写入口。P1 只用到计数，P3 补全。 */
    abstract fun noteDao(): NoteDao

    /** 待办的读写入口。P1 只用到计数，P4 补全。 */
    abstract fun todoDao(): TodoDao
}
