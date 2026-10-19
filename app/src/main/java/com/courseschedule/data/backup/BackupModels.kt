package com.courseschedule.data.backup

import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.CourseSlot
import com.courseschedule.data.entity.Note
import com.courseschedule.data.entity.PeriodTime
import com.courseschedule.data.entity.Semester
import com.courseschedule.data.entity.Todo
import com.courseschedule.data.entity.WeekType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException

/**
 * # 备份文件（JSON）的格式定义
 *
 * ## 为什么另写一套 DTO，而不是给实体类加 `@Serializable`
 *
 * 因为**备份格式和数据库结构是两件事，必须能独立演进**。
 *
 * 如果直接序列化 [Semester] 这些实体：
 * - 以后给 `Course` 加一个字段，老备份文件立刻读不了（缺字段）；
 * - 想把字段改个名，等于同时改了数据库列名和文件格式；
 * - 实体上的 @Entity 注解和 @Serializable 混在一起，谁都看不清哪个是为了谁。
 *
 * 写成 DTO 之后，数据库怎么变都行，只要 [SemesterDto.toEntity] 还能把字段对上，
 * 老文件就一直能读。代价是多写几十行赋值代码——这几十行换来的是
 * **用户手里的备份文件不会因为一次 schema 变更而报废**，非常划算。
 *
 * ## 字段全部给了默认值
 *
 * 这样 `schemaVersion` 小的老文件（缺字段）也能反序列化成功，
 * 缺的部分落到默认值上。这是 JSON 备份格式的基本容错：**读的时候宽松**，
 * 校验的时候严格（见 [BackupCodec.decode]）。
 */

/** 当前 app 能写出的最高格式版本。读到更高的版本 = 这份文件来自更新的 app。 */
const val BACKUP_SCHEMA_VERSION = 1

@Serializable
data class SemesterDto(
    val id: Long,
    val name: String,
    val startEpochDay: Long,
    val totalWeeks: Int = 20,
    val weekOverride: Int? = null
)

@Serializable
data class CourseDto(
    val id: Long,
    val semesterId: Long,
    val name: String,
    val teacher: String = "",
    val location: String = "",
    val colorIndex: Int = 0,
    val note: String = ""
)

@Serializable
data class SlotDto(
    val id: Long,
    val courseId: Long,
    val dayOfWeek: Int,
    val startSection: Int,
    val endSection: Int,
    val startWeek: Int = 1,
    val endWeek: Int = 20,
    /**
     * 单双周。**存 [WeekType.value] 的数字而不是枚举名**，理由和数据库那边一样：
     * 数字不会因为枚举改名而失效。0 每周 / 1 单周 / 2 双周。
     */
    val weekType: Int = 0,
    val roomOverride: String = ""
)

@Serializable
data class PeriodDto(
    val id: Long,
    val semesterId: Long,
    val section: Int,
    val startMinute: Int,
    val endMinute: Int
)

@Serializable
data class NoteDto(
    val id: Long,
    val title: String,
    val content: String = "",
    val colorIndex: Int = 0,
    val pinned: Boolean = false,
    val updatedAt: Long = 0L
)

@Serializable
data class TodoDto(
    val id: Long,
    val title: String,
    val note: String = "",
    val dueAt: Long? = null,
    val remindAt: Long? = null,
    val repeatRule: String = "",
    val done: Boolean = false,
    val courseId: Long? = null
)

/**
 * 一个备份文件的**根节点**。
 *
 * @property schemaVersion 格式版本。读到比自己大的值必须拒绝——
 *   那份文件里可能有本版本不认识的字段，硬读会静默丢数据。
 * @property exportedAt 导出时间，epochMillis。给人看的（"这是我上周导的"），
 *   程序逻辑不依赖它。
 * @property semester 学期。**必填**，没有学期的备份没有任何意义。
 * @property courses / slots / periods 课程相关三张表。
 * @property notes / todos 现在是空数组，但**格式里必须有**：
 *   等 P3/P4 做出界面后，用户手里只有这一版导出的文件，
 *   那时候再加字段，老文件就缺表了。
 */
@Serializable
data class BackupFile(
    val schemaVersion: Int = BACKUP_SCHEMA_VERSION,
    val exportedAt: Long = 0L,
    val semester: SemesterDto,
    val courses: List<CourseDto> = emptyList(),
    val slots: List<SlotDto> = emptyList(),
    val periods: List<PeriodDto> = emptyList(),
    val notes: List<NoteDto> = emptyList(),
    val todos: List<TodoDto> = emptyList()
)

/**
 * 备份的**内存形态**：直接是数据库实体。
 *
 * 这一层夹在"JSON DTO"和"Repository 的写库方法"之间。
 * Repository 只认实体（它不该知道 JSON 长什么样），
 * 编解码只认 DTO（它不该知道 Room 长什么样），这个类就是两者的接缝。
 */
data class BackupPayload(
    val semester: Semester,
    val courses: List<Course>,
    val slots: List<CourseSlot>,
    val periods: List<PeriodTime>,
    val notes: List<Note> = emptyList(),
    val todos: List<Todo> = emptyList()
)

/**
 * 备份失败的原因。**分类的意义是能给用户一句有用的话**。
 *
 * 笼统的"导入失败"没有任何帮助——用户不知道是该换个文件、
 * 还是该升级 app、还是数据本身坏了。
 */
class BackupException(
    val reason: Reason,
    cause: Throwable? = null
) : Exception(reason.name, cause) {

    enum class Reason {
        /** 读写文件失败：云盘掉线、权限被撤、磁盘满。可重试。 */
        IO,

        /** 不是 JSON，或者不是我们认识的 JSON（缺 semester 这类必填字段）。 */
        NOT_BACKUP,

        /** 文件来自更新版本的 app。只能提示用户升级。 */
        TOO_NEW,

        /** 文件大得不像备份（> 8MB），多半是选错文件了。 */
        TOO_LARGE,

        /** 是 JSON 但结构对不上：字段类型错、枚举值非法等。 */
        CORRUPTED,

        /** 结构对得上但内部关系断裂：时间段的 courseId 找不到对应课程。
         *  这种文件导进去会让课表"有课但显示不出来"，必须拒绝。 */
        INCONSISTENT
    }
}

/**
 * JSON 编解码。**纯 Kotlin，不碰 Android API**——所以能直接在 JVM 单测里跑。
 *
 * 这是本项目第一个有单元测试的数据层逻辑（见 `BackupCodecTest`）。
 * 备份是最不能出错的功能：它出错的表现是"用户以为有备份，其实导不回来"，
 * 而那时候原始数据往往已经没了。
 */
object BackupCodec {

    /**
     * `prettyPrint`：备份文件可能被用户用文本编辑器打开看，
     * 缩进过的 JSON 至少是能读的。体积代价在一个本地文件上无所谓。
     *
     * `ignoreUnknownKeys`：老版本 app 读到新版本文件里多出来的字段时，
     * 忽略而不是报错。真正该拒绝的情况由 [BackupFile.schemaVersion] 判断，
     * 不靠"有没有未知字段"来判断——那样太脆。
     *
     * `encodeDefaults`：把 `teacher = ""` 这种默认值也写出来。
     * 不写的话文件更小，但用户看的时候会疑惑"这门课的教师字段去哪了"。
     */
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(payload: BackupPayload): String = json.encodeToString(payload.toFile())

    /**
     * 解析并校验。
     *
     * 顺序是刻意的：**先反序列化（能不能读），再查版本（该不该读），最后查关系（读出来的对不对）**。
     * 版本检查放在反序列化之后，是因为 `schemaVersion` 本身就是文件里的一个字段，
     * 必须先读出来才知道。
     *
     * @throws BackupException 所有失败都走这一个异常类型，
     *   调用方按 [BackupException.reason] 决定提示文案。
     */
    fun decode(text: String): BackupPayload {
        val cleaned = text.removePrefix("\uFEFF").trim()
        if (cleaned.isEmpty()) throw BackupException(BackupException.Reason.NOT_BACKUP)

        val file = try {
            json.decodeFromString<BackupFile>(cleaned)
        } catch (e: SerializationException) {
            // 包括：不是 JSON、JSON 结构不匹配、必填字段缺失、类型不对
            throw BackupException(BackupException.Reason.NOT_BACKUP, e)
        } catch (e: IllegalArgumentException) {
            throw BackupException(BackupException.Reason.CORRUPTED, e)
        }

        if (file.schemaVersion > BACKUP_SCHEMA_VERSION) {
            throw BackupException(BackupException.Reason.TOO_NEW)
        }
        if (file.schemaVersion < 1) {
            throw BackupException(BackupException.Reason.CORRUPTED)
        }

        validate(file)
        return file.toPayload()
    }

    /**
     * 关系校验。**这一步不能省。**
     *
     * 反序列化只保证"能解析成对象"，不保证"这些对象之间讲得通"。
     * 一个手改过的、或者被别的程序生成的文件，完全可能通过 JSON 解析，
     * 但里面的时间段指向不存在的课程。导进去的表现是：
     * 课程列表里有 5 门课，课表上一格都没有——用户会以为数据丢了。
     *
     * 与其导进去变成这种状态，不如在门口就拒绝。
     */
    private fun validate(file: BackupFile) {
        fun reject(): Nothing = throw BackupException(BackupException.Reason.INCONSISTENT)

        val courseIds = file.courses.map { it.id }
        // 重复 id 会让 id 映射出错，直接拒绝
        if (courseIds.size != courseIds.toSet().size) reject()
        val knownCourses = courseIds.toSet()

        if (file.slots.any { it.courseId !in knownCourses }) reject()
        if (file.slots.any { it.dayOfWeek !in 1..7 }) reject()
        if (file.slots.any { it.startSection < 1 || it.endSection < it.startSection }) reject()
        if (file.slots.any { it.startWeek < 1 || it.endWeek < it.startWeek }) reject()
        if (file.slots.any { WeekType.entries.none { type -> type.value == it.weekType } }) reject()

        // 作息表不能为空：课表左侧的节次列全靠它，空表会让整张课表塌掉。
        // app 自身的逻辑（设置页校验 + ensureSemester）也保证它至少有 1 条，
        // 所以空作息的文件一定不是这个 app 正常导出的。
        if (file.periods.isEmpty()) reject()
        if (file.periods.any { it.section < 1 || it.endMinute <= it.startMinute }) reject()
        if (file.periods.any { it.startMinute < 0 || it.endMinute > 24 * 60 }) reject()
    }
}

// ---------- DTO ↔ 实体的互相转换 ----------

private fun BackupPayload.toFile() = BackupFile(
    schemaVersion = BACKUP_SCHEMA_VERSION,
    exportedAt = System.currentTimeMillis(),
    semester = SemesterDto(
        id = semester.id,
        name = semester.name,
        startEpochDay = semester.startEpochDay,
        totalWeeks = semester.totalWeeks,
        weekOverride = semester.weekOverride
    ),
    courses = courses.map {
        CourseDto(
            id = it.id,
            semesterId = it.semesterId,
            name = it.name,
            teacher = it.teacher,
            location = it.location,
            colorIndex = it.colorIndex,
            note = it.note
        )
    },
    slots = slots.map {
        SlotDto(
            id = it.id,
            courseId = it.courseId,
            dayOfWeek = it.dayOfWeek,
            startSection = it.startSection,
            endSection = it.endSection,
            startWeek = it.startWeek,
            endWeek = it.endWeek,
            weekType = it.weekType.value,
            roomOverride = it.roomOverride
        )
    },
    periods = periods.map {
        PeriodDto(
            id = it.id,
            semesterId = it.semesterId,
            section = it.section,
            startMinute = it.startMinute,
            endMinute = it.endMinute
        )
    },
    notes = notes.map {
        NoteDto(
            id = it.id,
            title = it.title,
            content = it.content,
            colorIndex = it.colorIndex,
            pinned = it.pinned,
            updatedAt = it.updatedAt
        )
    },
    todos = todos.map {
        TodoDto(
            id = it.id,
            title = it.title,
            note = it.note,
            dueAt = it.dueAt,
            remindAt = it.remindAt,
            repeatRule = it.repeatRule,
            done = it.done,
            courseId = it.courseId
        )
    }
)

private fun BackupFile.toPayload() = BackupPayload(
    semester = Semester(
        id = semester.id,
        name = semester.name,
        startEpochDay = semester.startEpochDay,
        totalWeeks = semester.totalWeeks,
        weekOverride = semester.weekOverride
    ),
    courses = courses.map {
        Course(
            id = it.id,
            semesterId = it.semesterId,
            name = it.name,
            teacher = it.teacher,
            location = it.location,
            colorIndex = it.colorIndex,
            note = it.note
        )
    },
    slots = slots.map {
        CourseSlot(
            id = it.id,
            courseId = it.courseId,
            dayOfWeek = it.dayOfWeek,
            startSection = it.startSection,
            endSection = it.endSection,
            startWeek = it.startWeek,
            endWeek = it.endWeek,
            // 校验已经保证 weekType 是合法值，这里直接还原
            weekType = WeekType.from(it.weekType),
            roomOverride = it.roomOverride
        )
    },
    periods = periods.map {
        PeriodTime(
            id = it.id,
            semesterId = it.semesterId,
            section = it.section,
            startMinute = it.startMinute,
            endMinute = it.endMinute
        )
    },
    notes = notes.map {
        Note(
            id = it.id,
            title = it.title,
            content = it.content,
            colorIndex = it.colorIndex,
            pinned = it.pinned,
            updatedAt = it.updatedAt
        )
    },
    todos = todos.map {
        Todo(
            id = it.id,
            title = it.title,
            note = it.note,
            dueAt = it.dueAt,
            remindAt = it.remindAt,
            repeatRule = it.repeatRule,
            done = it.done,
            courseId = it.courseId
        )
    }
)
