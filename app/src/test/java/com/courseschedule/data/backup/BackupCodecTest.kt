package com.courseschedule.data.backup

import com.courseschedule.data.entity.Course
import com.courseschedule.data.entity.CourseSlot
import com.courseschedule.data.entity.Note
import com.courseschedule.data.entity.PeriodTime
import com.courseschedule.data.entity.Semester
import com.courseschedule.data.entity.Todo
import com.courseschedule.data.entity.WeekType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份格式的单元测试。
 * 备份出错的后果是"用户以为有备份，真出事时导不回来"，这种问题开发期几乎暴露不出来，只能靠测试钉死。
 * 能跑在纯 JVM 上，是因为 `BackupCodec` 不碰任何 Android API（Context、Uri 等），文件 IO 在 `BackupManager`——
 * 纯逻辑和 IO 分开，测试成本差一个数量级。
 */
class BackupCodecTest {

    /** 一份"真实使用中可能长这样"的数据：一门课两个时段（单双周不同），外加便签和待办。 */
    private fun samplePayload() = BackupPayload(
        semester = Semester(
            id = 7L,
            name = "2026-2027 秋",
            startEpochDay = 20_000L,
            totalWeeks = 18,
            weekOverride = 3
        ),
        courses = listOf(
            Course(
                id = 1L,
                semesterId = 7L,
                name = "高等数学",
                teacher = "张三",
                location = "A201",
                colorIndex = 2,
                note = "带计算器"
            ),
            Course(id = 2L, semesterId = 7L, name = "编译原理")
        ),
        slots = listOf(
            CourseSlot(
                id = 11L,
                courseId = 1L,
                dayOfWeek = 1,
                startSection = 1,
                endSection = 2,
                startWeek = 1,
                endWeek = 16,
                weekType = WeekType.ODD,
                roomOverride = "B305"
            ),
            CourseSlot(
                id = 12L,
                courseId = 1L,
                dayOfWeek = 3,
                startSection = 5,
                endSection = 6,
                weekType = WeekType.EVEN
            ),
            CourseSlot(id = 13L, courseId = 2L, dayOfWeek = 5, startSection = 3, endSection = 4)
        ),
        periods = listOf(
            PeriodTime(id = 101L, semesterId = 7L, section = 1, startMinute = 480, endMinute = 525),
            PeriodTime(id = 102L, semesterId = 7L, section = 2, startMinute = 535, endMinute = 580)
        ),
        notes = listOf(
            Note(id = 201L, title = "买书", content = "数据结构教材", pinned = true, updatedAt = 1_700_000_000_000L)
        ),
        todos = listOf(
            Todo(id = 301L, title = "交作业", dueAt = 1_700_100_000_000L, courseId = 2L)
        )
    )

    @Test
    fun `编解码往返后内容完全一致`() {
        val original = samplePayload()

        val restored = BackupCodec.decode(BackupCodec.encode(original))

        assertEquals(original, restored)
    }

    @Test
    fun `单双周以外的字段也能正确还原`() {
        // 单独验证 WeekType → Int → WeekType 这条转换链，
        // 这是最容易在"加字段时改错映射"的地方
        val restored = BackupCodec.decode(BackupCodec.encode(samplePayload()))
        assertEquals(WeekType.ODD, restored.slots[0].weekType)
        assertEquals(WeekType.EVEN, restored.slots[1].weekType)
        assertEquals(WeekType.ALL, restored.slots[2].weekType)
    }

    @Test
    fun `来自更高版本的文件被拒绝而不是硬读`() {
        val text = BackupCodec.encode(samplePayload())
            .replace("\"schemaVersion\": $BACKUP_SCHEMA_VERSION", "\"schemaVersion\": 99")

        val error = assertThrows(BackupException::class.java) { BackupCodec.decode(text) }
        assertEquals(BackupException.Reason.TOO_NEW, error.reason)
    }

    @Test
    fun `不是 JSON 的文件报「不是备份」`() {
        val error = assertThrows(BackupException::class.java) {
            BackupCodec.decode("这是一张图片，不是备份")
        }
        assertEquals(BackupException.Reason.NOT_BACKUP, error.reason)
    }

    @Test
    fun `空文件报「不是备份」`() {
        val error = assertThrows(BackupException::class.java) { BackupCodec.decode("   \n  ") }
        assertEquals(BackupException.Reason.NOT_BACKUP, error.reason)
    }

    @Test
    fun `多余字段被忽略而不是报错`() {
        // 模拟"更新版本的 app 写了一份多了个字段的文件"。
        // 只要 schemaVersion 没变，老版本就应该能读。
        val text = BackupCodec.encode(samplePayload()).replaceFirst("{", "{\n  \"futureField\": 42,")

        val restored = BackupCodec.decode(text)
        assertEquals(2, restored.courses.size)
    }

    @Test
    fun `时间段指向不存在的课程时拒绝导入`() {
        val broken = samplePayload().let {
            it.copy(slots = it.slots + CourseSlot(courseId = 999L, dayOfWeek = 2, startSection = 1, endSection = 1))
        }

        val error = assertThrows(BackupException::class.java) {
            BackupCodec.decode(BackupCodec.encode(broken))
        }
        assertEquals(BackupException.Reason.INCONSISTENT, error.reason)
    }

    @Test
    fun `作息表为空的文件被拒绝`() {
        // 没有作息 = 课表左侧的节次列全空，整张表塌掉。
        // app 自身任何路径都产不出这种文件，所以一定是坏文件。
        val broken = samplePayload().copy(periods = emptyList())

        val error = assertThrows(BackupException::class.java) {
            BackupCodec.decode(BackupCodec.encode(broken))
        }
        assertEquals(BackupException.Reason.INCONSISTENT, error.reason)
    }

    @Test
    fun `总周数小于 1 的文件被拒绝`() {
        // totalWeeks = 0 会让课表页的周次计算 coerceIn(1, 0) 抛「空区间」异常，
        // 表现是导入成功但 app 再也打不开——必须在门口拦下
        val payload = samplePayload()
        val broken = payload.copy(semester = payload.semester.copy(totalWeeks = 0))

        val error = assertThrows(BackupException::class.java) {
            BackupCodec.decode(BackupCodec.encode(broken))
        }
        assertEquals(BackupException.Reason.INCONSISTENT, error.reason)
    }

    @Test
    fun `手动周次越界的文件被拒绝`() {
        val payload = samplePayload()
        val broken = payload.copy(semester = payload.semester.copy(weekOverride = 99))

        val error = assertThrows(BackupException::class.java) {
            BackupCodec.decode(BackupCodec.encode(broken))
        }
        assertEquals(BackupException.Reason.INCONSISTENT, error.reason)
    }

    @Test
    fun `学期名为空的文件被拒绝`() {
        // app 自己的写入口（设置页校验）不允许空名称，出现空名称的一定是坏文件
        val payload = samplePayload()
        val broken = payload.copy(semester = payload.semester.copy(name = "  "))

        val error = assertThrows(BackupException::class.java) {
            BackupCodec.decode(BackupCodec.encode(broken))
        }
        assertEquals(BackupException.Reason.INCONSISTENT, error.reason)
    }

    @Test
    fun `星期越界的时间段被拒绝`() {
        val broken = samplePayload().let {
            it.copy(slots = listOf(it.slots[0].copy(dayOfWeek = 8)))
        }

        val error = assertThrows(BackupException::class.java) {
            BackupCodec.decode(BackupCodec.encode(broken))
        }
        assertEquals(BackupException.Reason.INCONSISTENT, error.reason)
    }

    @Test
    fun `带 BOM 的文件也能读`() {
        // Windows 记事本保存 UTF-8 时会加 BOM，用户手动改过备份文件就会遇到
        val text = "\uFEFF" + BackupCodec.encode(samplePayload())

        val restored = BackupCodec.decode(text)
        assertEquals("高等数学", restored.courses[0].name)
    }

    @Test
    fun `导出的文件是可读的缩进 JSON`() {
        // 不测具体内容，只确认它真的是多行缩进格式——
        // 用户可能拿记事本打开看，一坨单行 JSON 没法读
        val text = BackupCodec.encode(samplePayload())
        assertTrue(text.contains("\n"))
        assertTrue(text.contains("\"semester\""))
    }
}
