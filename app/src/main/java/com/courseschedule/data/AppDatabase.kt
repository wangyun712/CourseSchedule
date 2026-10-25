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
 * 数据库定义。六张表全部在 version=1 建好（部分表当时还没有界面），为的是避开 migration；
 * 真要改表时：对照 `app/schemas/` 导出的旧版 JSON 写 Migration、version+1、
 * 在 `ScheduleApp` 注册 addMigrations 并装旧版本设备实测升级。
 * `exportSchema` 必须保持开启（导出 JSON 是写 migration 的唯一依据）；
 * 表结构一改 version 就要 +1，否则 Room 运行期校验失败直接抛异常。
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

    /** 学期 + 节次作息的读写入口。 */
    abstract fun semesterDao(): SemesterDao

    /** 课程 + 时间段的读写入口。 */
    abstract fun courseDao(): CourseDao

    /** 便签的读写入口。 */
    abstract fun noteDao(): NoteDao

    /** 待办的读写入口。 */
    abstract fun todoDao(): TodoDao
}
