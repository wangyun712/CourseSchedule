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
 * 备份文件（JSON）的格式定义。
 * DTO 与数据库实体分离：备份格式要能独立于 schema 演进，实体加字段、改名都不影响老备份文件的读取。
 * 字段全给默认值：缺字段的老文件也能反序列化（读宽松），数据对错交给校验把关（校验严格，见 `BackupCodec.decode`）。
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
     * 单双周。存 `WeekType.value` 的数字而不是枚举名，理由和数据库那边一样：数字不因枚举改名而失效。
     * 0 每周 / 1 单周 / 2 双周。
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
 * 备份文件的根节点；`semester` 必填，没有学期的备份没有任何意义。
 * @property schemaVersion 格式版本，高于当前版本必须直接拒绝——文件里可能有本版本不认识的字段，硬读会静默丢数据。
 * @property exportedAt 导出时间（epochMillis），给人看，程序逻辑不依赖。
 * @property notes / todos 现在恒为空，但字段从第一版就进格式：以后加内容时，老备份文件不会缺字段。
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
 * 备份的内存形态，直接是数据库实体。
 * 夹在 JSON DTO 与 Repository 写库方法之间的接缝：Repository 只认实体，编解码只认 DTO。
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
 * 备份失败的原因分类，意义是能给用户一句有用的话，而不是笼统的"导入失败"。
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
 * JSON 编解码，纯 Kotlin 不碰 Android API，可直接在 JVM 单测里跑（见 `BackupCodecTest`）。
 * 备份是最不能出错的功能：出错的表现是"用户以为有备份，其实导不回来"，而那时原始数据往往已经没了。
 */
object BackupCodec {

    /**
     * `prettyPrint`：备份文件可能被用户用编辑器打开，缩进过的 JSON 至少可读。
     * `encodeDefaults`：默认值也写出，避免用户看文件时疑惑"字段去哪了"。
     * `ignoreUnknownKeys`：未知字段忽略而非报错，这是"读宽松"；该拒绝的情况靠 `schemaVersion` 版本号判断，不靠未知字段。
     */
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(payload: BackupPayload): String = json.encodeToString(payload.toFile())

    /**
     * 解析并校验。顺序是刻意的：先反序列化（能不能读）→再查版本（该不该读）→最后查关系（读出来的对不对）。
     * 版本检查放在反序列化之后：`schemaVersion` 本身就是文件里的一个字段，必须先读出来才能比。
     * 所有失败都抛 `BackupException`，调用方按 `reason` 决定提示文案。
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
     * 关系校验，这一步不能省：反序列化只保证"能解析成对象"，不保证对象之间讲得通。
     * 手改或外部生成的文件可能解析通过，但时间段指向不存在的课程——导进去会让课表"有课但显示不出来"。
     * 与其导入后变成这种状态，不如在门口就拒绝。
     */
    private fun validate(file: BackupFile) {
        fun reject(): Nothing = throw BackupException(BackupException.Reason.INCONSISTENT)

        // 学期合法性：app 自身写入口（设置页校验 + ensureSemester）保证 totalWeeks >= 1、
        // 周次落在 1..totalWeeks、名称非空，越界的一定是坏文件。不能省：坏值导入时不报错，
        // 而是打开课表时让周次计算的 coerceIn 抛「空区间」异常，导入"成功"但 app 打不开。
        if (file.semester.totalWeeks < 1) reject()
        file.semester.weekOverride?.let { override ->
            if (override !in 1..file.semester.totalWeeks) reject()
        }
        if (file.semester.name.isBlank()) reject()

        val courseIds = file.courses.map { it.id }
        // 重复 id 会让 id 映射出错，直接拒绝
        if (courseIds.size != courseIds.toSet().size) reject()
        val knownCourses = courseIds.toSet()

        if (file.slots.any { it.courseId !in knownCourses }) reject()
        if (file.slots.any { it.dayOfWeek !in 1..7 }) reject()
        if (file.slots.any { it.startSection < 1 || it.endSection < it.startSection }) reject()
        if (file.slots.any { it.startWeek < 1 || it.endWeek < it.startWeek }) reject()
        if (file.slots.any { WeekType.entries.none { type -> type.value == it.weekType } }) reject()

        // periods 空必拒：课表左侧节次列全靠它，空表整张课表塌掉；app 自身也保证至少 1 条，空作息必是坏文件。
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
