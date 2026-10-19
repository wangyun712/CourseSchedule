package com.courseschedule.data.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * 单双周类型。
 *
 * 存库时用 [value] 转成 Int（见 [com.courseschedule.data.Converters]），
 * 而不是让 Room 直接存枚举的名字字符串。原因：
 *
 * - 数字占空间小、比较快；
 * - 更重要的是**稳定**。假如哪天把 `ODD` 改名成 `SINGLE`，存名字的方案会让
 *   所有老用户的单周课全部读不出来（或读出空值）；存数字则改名不影响数据。
 *   所以 [value] 的数值**一旦发布就不能改**，只能往后追加新的枚举项。
 *
 * 注意枚举项顺序决定了 `WeekType.entries` 的顺序，
 * 而加课页的"单双周"选项组就是直接拿 `WeekType.entries` 渲染的
 * （见 [com.courseschedule.feature.courseedit.CourseEditScreen]），
 * 所以调整这里的顺序会直接改变界面上的排列顺序。
 */
enum class WeekType(val value: Int) {
    /** 每周都上。绝大多数课程是这个。 */
    ALL(0),

    /** 单周上（第 1、3、5… 周）。 */
    ODD(1),

    /** 双周上（第 2、4、6… 周）。 */
    EVEN(2);

    companion object {
        /**
         * 从数据库里的 Int 还原成枚举。
         *
         * **查不到时返回 [ALL] 而不是抛异常**，这是刻意的容错：
         * 万一以后有人手改了数据库、或者遇到某个更早版本写入的未知值，
         * 结果会是"这门课每周都上"，而不是整个 app 崩溃、或者这门课凭空消失。
         * 数据越界时宁可表现得宽松一点，也不要让用户打不开课表。
         */
        fun from(value: Int): WeekType = entries.firstOrNull { it.value == value } ?: ALL
    }
}

/**
 * 学期。**当前周的计算基准**：第 1 周的周一 = [startEpochDay]。
 *
 * 虽然叫"学期"，但整个 app 目前只支持**一个学期**（表里正常只有一行）。
 * 保留这张表、而不是把这些字段塞进 `SharedPreferences`，原因有两个：
 *
 * 1. 节次作息表 [PeriodTime] 需要绑一个学期，有主键才好建外键；
 * 2. P2 之后如果要支持"多学期存档 / 切换"，只需允许插入多行 + 加一个"当前学期"标记，
 *    不用改数据模型，也不用写 migration 搬数据。
 *
 * @property id 主键，自增。`0L` 是"还没入库"的约定值
 *   （Room 的 `autoGenerate` 见到 0 就自己分配一个新 id）。
 * @property name 显示用名字，如 `"2025-2026 秋"`。首次启动默认建的是 `"本学期"`。
 * @property startEpochDay 第 1 周周一的 epochDay。**必须对齐到周一**，
 *   由 [com.courseschedule.core.WeekCalculator.mondayOf] 在建学期时保证。
 *   存的是"天"不是"毫秒"，理由见 [com.courseschedule.core.WeekCalculator] 的类注释。
 * @property totalWeeks 学期总周数，默认 20。周次计算的封顶值。
 * @property weekOverride 手动指定"当前是第几周"，`null` 表示按 [startEpochDay] 自动算。
 *   用于假期调课——那种情况下学校的实际周次和"开学日期 + 7 天"算出来的对不上，
 *   而直接改开学日期会连带把整学期的周次全改错，所以单独开一个覆盖字段。
 */
@Entity(tableName = "semester")
data class Semester(
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
 * 课程本体。**注意：这里不含任何时间信息**，时间全部落在 [CourseSlot] 上。
 *
 * ## 为什么把课程和时间段拆成两张表（本项目最重要的一个数据模型决策）
 *
 * 真实课表里一门课常常有多个时段。比如"高等数学"可能是：
 * - 周一 1-2 节，单周，A 楼 201
 * - 周三 5-6 节，双周，B 楼 305
 *
 * 如果按"一行一门课"建表，就得多开 `day1 / section1 / week1 / room1`、
 * `day2 / section2 / ...` 这样的列。这样做的三个问题：
 *
 * 1. **列数固定**：最多支持 3 个时段就得开 3 组列，第 4 个时段加不进去；
 * 2. **查询丑陋**：周视图要拿"所有课的所有时段"，得写好几段 SELECT 再 UNION 起来；
 * 3. **字段全空**：只有 1 个时段的课，后面几组列全是 NULL，白占空间。
 *
 * 拆成 1:n 之后，每个时段一行，加时段就是加行，查询是纯粹的 `WHERE dayOfWeek = ?`。
 * 代价是"保存一门课"要跨两张表写——用 `withTransaction` 包一下就行，
 * 见 [com.courseschedule.data.ScheduleRepository.saveCourse]。
 *
 * @property semesterId 外键指向 [Semester]。`onDelete = CASCADE` 表示删学期时
 *   下面的课程一起删掉，避免留下"孤儿课程"。
 * @property name 课程名。必填，界面上会校验非空。
 * @property teacher 任课教师。允许空。
 * @property location 默认上课地点。允许空。
 *   具体某个时段可以用 [CourseSlot.roomOverride] 覆盖它
 *   （典型场景：理论课在 A 楼、实验课在 B 楼，同一门课两个教室）。
 * @property colorIndex 课表上色块的颜色索引，取值 0..7。
 *   存"索引"而不是"颜色值"的好处是：以后换配色方案时，老数据跟着一起换，
 *   不用改数据库。取色见 [com.courseschedule.ui.theme.CoursePalette.colorOf]。
 * @property note 备注。允许空。
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
    /**
     * 给外键列建索引。**这不是可选项。**
     *
     * Room 检测到"外键列上没有索引"时会给出编译期警告
     * （`column references a foreign key but it is not part of an index`）。
     * 原因是外键约束在父行被删或被改时，数据库需要反查所有子行——
     * 没有索引就是全表扫描。现在数据量小感觉不到，
     * 但课程多起来后"删一门课"会明显变慢。
     */
    indices = [Index("semesterId")]
)
data class Course(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val semesterId: Long,
    val name: String,
    val teacher: String = "",
    val location: String = "",
    val colorIndex: Int = 0,
    val note: String = ""
)

/**
 * 课程时间段——课表上的一个"格子"（或纵向连起来的几格）。
 *
 * 课程与时间段是 **1 : n**：一门课可以有多条 [CourseSlot]。
 * 周视图真正渲染的就是这张表：每条记录对应屏幕上的一个色块。
 *
 * ## 为什么"起止周"和"单双周"要分开存
 *
 * 这两个信息表达的是**不同维度**，合并会丢表达能力：
 *
 * - `startWeek..endWeek` 管"在这学期的一段区间内"（比如"后半学期才开课"）；
 * - [weekType] 管"在这个区间内，每隔一周才上一次"。
 *
 * 组合起来才能表达"第 9-16 周的单周上课"这种真实排课。
 * 如果只存一个 `weeks: List<Int>`，就得为"隔一周上一次"写 8 个数字，
 * 而且用户改总周数时没办法跟着调整。
 *
 * 代价是无法表达"第 3、7、11 周上课"这种**不规则的离散周次**。
 * 真遇到了再加一张 `slot_week` 关联表即可，属于纯增量改动，不动现有数据。
 *
 * @property courseId 外键指向 [Course]。[Course] 被删时这条一起删（CASCADE），
 *   所以删课程不需要手动清时间段表。
 * @property dayOfWeek `1 = 周一` … `7 = 周日`（ISO-8601）。
 *   与周视图从左到右的列顺序一致，也与
 *   [com.courseschedule.core.WeekCalculator.dayOfWeek] 的取值域一致。
 *   写入前界面上会 `coerceIn(1, 7)` 夹一次。
 * @property startSection 起始节次，从 1 开始。上界是
 *   [com.courseschedule.feature.courseedit.MAX_SECTION]（15）。
 * @property endSection 结束节次，**必须 >= [startSection]**。
 *   界面上用步进器联动保证（改起始节次时会顺带把结束节次顶上去），
 *   落库前 ViewModel 里还会用 `minOf / maxOf` 再纠一次，双保险。
 * @property startWeek 生效起始周，默认 1。
 * @property endWeek 生效结束周，默认 20（跟随学期总周数）。
 * @property weekType 单双周属性，默认 [WeekType.ALL]。
 * @property roomOverride 只覆盖**这一条时段**的上课地点。
 *   **空串表示"用课程默认地点"**，不是"没有地点"。
 *   取用时要 `ifBlank` 回退到 [Course.location]，
 *   见 [com.courseschedule.feature.schedule.PlacedCourse.room]。
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
    val endSection: Int,
    val startWeek: Int = 1,
    val endWeek: Int = 20,
    val weekType: WeekType = WeekType.ALL,
    /** 非空时覆盖课程默认地点（理论课/实验课不同教室） */
    val roomOverride: String = ""
)

/**
 * 节次作息表：第 n 节从几点上到几点。
 *
 * ## 为什么绑学期而不是全局一份（这是看过开源项目后改的设计）
 *
 * 最初想做成全局单例设置，后来意识到**不同学期的作息真的会变**：
 * 夏季学期下午可能 14:30 开始，冬季学期 14:00。
 * 做成全局的话，换学期时用户得手动重新改一遍作息；
 * 更麻烦的是改完之后，正在看着的老课表时间解释也跟着变了。
 *
 * 绑学期之后只需要多一个外键，但各学期互不干扰。
 * 代价是每条作息多占 8 字节——一个学期 12 条，多不到 100 字节，可忽略。
 *
 * ## 时间为什么存"分钟数"而不是 `"HH:mm"` 字符串
 *
 * 存 `480` 而不是 `"08:00"` 的好处：
 * - 判断"现在是不是在上课"是纯整数比较，不用先解析字符串；
 * - 排序天然正确（字符串排序会把 `"9:00"` 排在 `"10:00"` 后面）；
 * - 不涉及时区。
 *
 * 只在显示的时候用 [com.courseschedule.core.TimeUtils.minutesToText] 转一次。
 *
 * @property semesterId 外键指向 [Semester]，CASCADE 删除。
 * @property section 第几节，从 1 开始。
 *   **保存时是按列表下标重新编号的**（见
 *   [com.courseschedule.data.ScheduleRepository.savePeriods]），
 *   所以这个值永远连续，不会出现 1、2、4 这种跳号。
 * @property startMinute 开始时间，距 0 点的分钟数。`08:00` = 480。
 * @property endMinute 结束时间，必须 **> [startMinute]**
 *   （设置页保存前会校验，见 [com.courseschedule.feature.settings.SettingsViewModel.save]）。
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
    /** 第几节，从 1 开始 */
    val section: Int,
    /** 距 0 点的分钟数，如 08:00 = 480 */
    val startMinute: Int,
    val endMinute: Int
)

/**
 * 便签。**UI 在 P3 实现，P1 只建表。**
 *
 * ## 为什么 P1 就把表建好，而不是等 P3 再加
 *
 * Room 加表必须写 `Migration`，而 migration 是 Android 上最容易出事故的地方——
 * 用户升级 app 时如果 migration 写错，表现是**打开就崩**，
 * 而且是在别人的手机上崩，你很难复现。
 * 现在 version = 1 就把六张表一次建全，P3 只需要写 DAO 和界面，
 * **一次 migration 都不用写**。
 *
 * 代价是多几十行代码（空表不占存储空间）。
 * 这笔账算下来非常划算，尤其是这个 app 的用户就是你身边的人——
 * 出了 migration 事故你还得当面给他解释。
 *
 * @property title 标题。注意它和 [content] 都没有默认值（`title` 是无默认参数），
 *   构造时必须传。
 * @property content 正文，允许空。
 * @property colorIndex 便签底色索引，和课程配色共用
 *   [com.courseschedule.ui.theme.CoursePalette]。
 * @property pinned 是否置顶。列表排序时置顶的排在前面。
 * @property updatedAt 最后修改时间，**epochMillis**（注意这里和其他表不同，
 *   这里是毫秒不是天）。用来排序（最近改的排前面）。默认 0 只是为了让 P1 阶段能编译，
 *   P3 写入时一定会赋真实值。
 */
@Entity(tableName = "note")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val content: String = "",
    val colorIndex: Int = 0,
    val pinned: Boolean = false,
    val updatedAt: Long = 0L
)

/**
 * 待办 / 事务提醒。**UI 在 P4 实现，P1 只建表**（理由同 [Note]）。
 *
 * @property title 待办标题，必填。
 * @property note 补充说明，允许空。
 * @property dueAt 截止时间，epochMillis。`null` 表示没有截止时间。
 *   用可空类型而不是"0 表示没有"——`0` 在时间语义上等于 1970 年，
 *   排序或显示时很容易被漏判成"一个很早的时间"。可空类型让编译器强制你处理
 *   "没有截止时间"这种情况。
 * @property remindAt 提醒触发时间，epochMillis。P4 会给它挂
 *   `AlarmManager.setExactAndAllowWhileIdle`；
 *   注意 `AlarmManager` 的闹钟在设备重启后会全部丢失，
 *   所以 P4 还要加一个 `BOOT_COMPLETED` 的 `BroadcastReceiver` 来重新注册。
 * @property repeatRule 重复规则。**格式还没定**，空串表示不重复。
 *   计划用一个紧凑字符串编码（类似 `"WEEKLY:1,3,5"` 表示每周一三五），
 *   而不是定义 enum——因为重复规则天生是组合式的（每周几 × 每几天 × 截止日期），
 *   枚举会组合爆炸。在 P4 定下来之前，这个字段只存空串。
 * @property done 是否已完成。已完成的待办不再触发提醒。
 * @property courseId **软关联**，指向 [Course.id]，但**故意不加外键约束**。
 *
 *   ## 为什么这里不加外键
 *
 *   待办和课程是"参考关系"，不是"从属关系"。
 *   学生给"高数作业"建了个待办，后来把高数这门课从课表里删了——
 *   作业照样要交，待办不该跟着消失。
 *
 *   加了 `CASCADE` 会一起删；加 `SET_NULL` 则要在 [Course] 上加反向约束，
 *   连带影响课程表的删除性能。
 *
 *   所以这里只存一个 id，删课程后它会变成"指向不存在的课程"。
 *   渲染时用 `courseId?.let { ... }` 做存在性判断，取不到就只显示待办本身。
 *   这是**有意的弱引用，不是漏了约束**。
 */
@Entity(tableName = "todo")
data class Todo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val note: String = "",
    /** 截止时间，epochMillis */
    val dueAt: Long? = null,
    /** 提醒时间，epochMillis */
    val remindAt: Long? = null,
    /** 重复规则，P4 定义格式；空串表示不重复 */
    val repeatRule: String = "",
    val done: Boolean = false,
    val courseId: Long? = null
)

/**
 * 「课程 + 它的所有时间段」的**查询结果容器**。注意它**不是数据库表**——
 * 没有 `@Entity` 注解，只是给 Room 用来接收关联查询的结果。
 *
 * ## 为什么需要它（N+1 查询问题）
 *
 * 周视图需要"一次把所有课程和它们的时间段都拿到"。如果分两次查
 * （先查课程列表，再对每门课查一次它的时间段），就是经典的 **N+1 查询问题**：
 * 20 门课要发 21 条 SQL，每条都有跨进程调用开销。
 *
 * 这个类配合 [com.courseschedule.data.dao.CourseDao.observeCourses] 上的
 * `@Transaction`，让 Room 用 2 条 SQL 完成（1 条查课程 + 1 条按 id 批量查时间段），
 * 然后在这里帮你拼装成"每门课挂着它的时间段列表"的结构。
 *
 * @property course `@Embedded` 表示把 [Course] 的字段**平铺**进查询结果——
 *   SQL 里直接 `SELECT * FROM course` 即可，不用写 `course.name` 这种前缀。
 * @property slots `@Relation` 告诉 Room "用 [Course] 的 `id` 去匹配
 *   [CourseSlot] 的 `courseId`"，自动填充这个列表。
 *
 *   **某门课还没有任何时间段时，这里是空列表而不是 null**——
 *   Room 保证 `@Relation` 的集合类型永远非空，所以用的时候不用判 null，
 *   直接 `item.slots.filter { ... }` 就行。
 */
data class CourseWithSlots(
    @Embedded val course: Course,
    @Relation(parentColumn = "id", entityColumn = "courseId")
    val slots: List<CourseSlot>
)
