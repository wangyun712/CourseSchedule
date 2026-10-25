package com.courseschedule.data.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * 单双周类型。存库经 `Converters` 转成 Int 而不是枚举名：枚举改名不会毁掉老数据，
 * 因此 `value` 的数值一旦发布就不能改，只能追加新枚举项。
 * 枚举声明顺序即加课页"单双周"选项的排列顺序（见 `CourseEditScreen`）。
 */
enum class WeekType(val value: Int) {
    ALL(0),
    ODD(1),
    EVEN(2);

    companion object {
        /** 从 Int 还原枚举，查不到时返回 [ALL] 而不抛异常。 */
        fun from(value: Int): WeekType = entries.firstOrNull { it.value == value } ?: ALL
    }
}

/**
 * 学期。整个 app 当前只支持一学期（表里正常只有一行）。
 * `startEpochDay` 是第 1 周周一的 epochDay（存天不存毫秒），全部周次以它为基准，
 * 由 `WeekCalculator.mondayOf` 保证对齐到周一。
 * 保留整表而不塞进 SharedPreferences：节次作息要靠它建外键，将来支持多学期也不改模型。
 */
@Entity(tableName = "semester")
data class Semester(
    /** Room 的 autoGenerate 见 0 就分配新 id，0L 即"未入库"约定 */
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    /** 第 1 周周一的 epochDay（LocalDate.toEpochDay） */
    val startEpochDay: Long,
    val totalWeeks: Int = 20,
    /**
     * 手动覆盖"当前是第几周"，用于假期调课等场景。
     * null 表示按开学日期自动计算。
     */
    val weekOverride: Int? = null
)

/**
 * 课程本体，不含任何时间信息，时间全部落在 [CourseSlot] 上（1:n）。
 * 为什么拆成两张表：一门课常有多个时段，若"一行一门课"就得开 day1/section1/room1… 多组列，
 * 三个问题——列数固定（第 4 个时段加不进）、周视图要写多段 SELECT 再 UNION、
 * 只有 1 个时段的课其余列全是 NULL。拆表后加时段就是加行，查询是纯粹的 WHERE；
 * 代价是保存一门课要跨两表写，用事务包住即可，见 `ScheduleRepository.saveCourse`。
 */
@Entity(
    tableName = "course",
    foreignKeys = [
        ForeignKey(
            entity = Semester::class,
            parentColumns = ["id"],
            childColumns = ["semesterId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    // 外键列必须建索引：父行被删/改时需反查子行，没索引就是全表扫描（Room 也会给编译警告）
    indices = [Index("semesterId")]
)
data class Course(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val semesterId: Long,
    val name: String,
    val teacher: String = "",
    val location: String = "",
    /** 色块颜色索引 0..7，存索引而非颜色值，换配色方案时老数据自动跟随 */
    val colorIndex: Int = 0,
    val note: String = ""
)

/**
 * 课程时间段，即课表上的一个"格子"（或纵向相连的几格），与 [Course] 是 1:n，
 * 周视图渲染的就是本表，每条记录对应屏幕上一个色块。
 * 起止周与单双周分开存：前者表达"哪段区间开课"（如后半学期），后者表达"区间内隔周上"，
 * 组合才能表达"第 9-16 周单周上课"；不规则的离散周次暂不支持，需要时再加关联表。
 */
@Entity(
    tableName = "course_slot",
    foreignKeys = [
        ForeignKey(
            entity = Course::class,
            parentColumns = ["id"],
            childColumns = ["courseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("courseId")]
)
data class CourseSlot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val courseId: Long,
    /** 1 = 周一 … 7 = 周日 */
    val dayOfWeek: Int,
    val startSection: Int,
    /** 必须 >= startSection，界面联动与落库前各校验一次 */
    val endSection: Int,
    val startWeek: Int = 1,
    val endWeek: Int = 20,
    val weekType: WeekType = WeekType.ALL,
    /** 空串表示用课程默认地点，非空时覆盖（理论课/实验课不同教室） */
    val roomOverride: String = ""
)

/**
 * 节次作息表：第 n 节从几点到几点。绑学期而非全局一份，因为不同学期作息会变，
 * 各学期互不干扰。时间存距 0 点的分钟数（08:00 = 480）而不是 "HH:mm" 字符串：
 * 判断、排序是纯整数比较且不受时区影响，显示时才经 `TimeUtils.minutesToText` 转换。
 */
@Entity(
    tableName = "period_time",
    foreignKeys = [
        ForeignKey(
            entity = Semester::class,
            parentColumns = ["id"],
            childColumns = ["semesterId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("semesterId")]
)
data class PeriodTime(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val semesterId: Long,
    /** 第几节，从 1 开始；保存时按列表下标重编号，值永远连续 */
    val section: Int,
    /** 距 0 点的分钟数，如 08:00 = 480 */
    val startMinute: Int,
    /** 必须 > startMinute，设置页保存前校验 */
    val endMinute: Int
)

/**
 * 便签，界面在 `feature/note/`。
 * 表先于界面在 version=1 建好，为的是避开 migration，详见 `AppDatabase` 类注释。
 */
@Entity(tableName = "note")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val content: String = "",
    val colorIndex: Int = 0,
    val pinned: Boolean = false,
    /** 最后修改时间，epochMillis（注意是毫秒不是天），按最近修改排序 */
    val updatedAt: Long = 0L
)

/**
 * 待办 / 事务提醒，界面在 `feature/todo/`；表先建好的理由同 [Note]。
 *
 * `courseId` 指向 `Course.id` 但故意不加外键：待办与课程是参考而非从属关系，
 * 删了课后"高数作业"照样要交，待办不能跟着 CASCADE 一起消失，
 * 用 SET_NULL 又会拖累课程删除，所以只存 id。删课后它会指向不存在的课程，
 * 渲染时判存在性即可。这是有意的软关联，不是漏了约束。
 */
@Entity(tableName = "todo")
data class Todo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val note: String = "",
    /** 截止时间，epochMillis；可空而非用 0 表示"没有"，避免被当成 1970 年 */
    val dueAt: Long? = null,
    /** 提醒时间，epochMillis；提醒功能已推迟，保留字段以免将来改表 */
    val remindAt: Long? = null,
    /** 重复规则，P4 定义格式；空串表示不重复 */
    val repeatRule: String = "",
    val done: Boolean = false,
    val courseId: Long? = null
)

/**
 * 「课程 + 它的所有时间段」的查询结果容器，不是数据库表（没有 @Entity）。
 * 配合 `CourseDao.observeCourses` 上的 @Transaction 用两条 SQL 一次拿全，
 * 避免先查课程、再逐门查时段的 N+1 问题。
 * Room 保证 @Relation 集合永远非空：没时段的课这里是空列表而非 null，使用时无需判空。
 */
data class CourseWithSlots(
    @Embedded val course: Course,
    @Relation(parentColumn = "id", entityColumn = "courseId")
    val slots: List<CourseSlot>
)
