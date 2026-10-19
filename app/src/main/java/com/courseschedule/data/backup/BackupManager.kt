package com.courseschedule.data.backup

import android.content.ContentResolver
import android.net.Uri
import com.courseschedule.data.ScheduleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 备份文件的**读写与落地**。
 *
 * ## 职责边界
 *
 * - [BackupCodec]：字节 → 对象（纯函数，可单测）
 * - [ScheduleRepository]：对象 → 数据库（事务）
 * - 本类：对象 → 文件 / 文件 → 对象，**唯一的 Android IO 层**
 *
 * 这样切开的好处是：出了问题时能立刻定位是哪一层。
 * "导出后文件是空的"→ 查本类；"文件内容不对"→ 查 Codec；
 * "导进去课表空了"→ 查 Repository 的事务。
 *
 * ## 为什么用 `ContentResolver` 而不是文件路径
 *
 * 因为走的是系统文件选择器（SAF）。用户可能把文件存到本地、
 * OneDrive、微信收藏、下载目录……**我们拿不到也不该拿到真实路径**。
 * `ContentResolver` 给的是一个 `Uri`，通过它读写由系统代理，
 * 这个 app 因此**不需要申请任何存储权限**。
 *
 * @param repository 数据来源，导入时负责落库。
 * @param resolver 由 `AppContainer` 传入 `context.contentResolver`。
 */
class BackupManager(
    private val repository: ScheduleRepository,
    private val resolver: ContentResolver
) {

    /**
     * 导出到用户选定的 [uri]。
     *
     * 所有磁盘操作都切到 [Dispatchers.IO]：写云盘的时候可能要等几秒，
     * 在主线程上做会直接 ANR。
     *
     * 打开模式用 `"wt"` 而不是 `"w"`：**w 不截断**。如果用户选了一个
     * 已有的、更长的文件，新内容写完后后面会留下一截旧内容，
     * 结果是一个语法都不完整的 JSON。
     */
    suspend fun export(uri: Uri): BackupSummary = withContext(Dispatchers.IO) {
        val payload = repository.exportBackup()
        val text = BackupCodec.encode(payload)
        try {
            val stream = resolver.openOutputStream(uri, "wt")
                ?: throw BackupException(BackupException.Reason.IO)
            stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        } catch (e: IOException) {
            throw BackupException(BackupException.Reason.IO, e)
        } catch (e: SecurityException) {
            // 用户从选择器拿到 uri 之后又撤销了授权（少见，但真的会发生）
            throw BackupException(BackupException.Reason.IO, e)
        }
        BackupSummary(payload.courses.size, payload.slots.size)
    }

    /**
     * 从 [uri] 读入并覆盖当前数据。
     *
     * ## 顺序：先完整读进内存、解析校验，再开事务写库
     *
     * 这个顺序不能反过来。如果边读边写，一个坏文件写到一半失败，
     * 用户就会处在一个"一半是新数据、一半是旧数据"的状态——
     * 比导入失败严重得多。现在的写法保证：**要么全换，要么完全不动**。
     *
     * ## 大小上限
     *
     * 一个正常的备份文件是几十 KB 级别（20 门课不到 30KB）。
     * 设 8MB 上限是为了防止用户误选了一个几百兆的视频：
     * `readBytes()` 会把整个文件读进内存，不设上限就是 OOM 崩溃。
     */
    suspend fun restore(uri: Uri): BackupSummary = withContext(Dispatchers.IO) {
        val size = try {
            resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        } catch (e: IOException) {
            -1L
        } catch (e: SecurityException) {
            -1L
        }
        // statSize 可能拿不到（-1），那时不阻断，靠读取时再出错
        if (size > MAX_BACKUP_BYTES) throw BackupException(BackupException.Reason.TOO_LARGE)

        val text = try {
            val stream = resolver.openInputStream(uri)
                ?: throw BackupException(BackupException.Reason.IO)
            stream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: IOException) {
            throw BackupException(BackupException.Reason.IO, e)
        } catch (e: SecurityException) {
            throw BackupException(BackupException.Reason.IO, e)
        }

        val payload = BackupCodec.decode(text)
        repository.restoreBackup(payload)
        BackupSummary(payload.courses.size, payload.slots.size)
    }

    /**
     * "导入了多少东西"的摘要，只用于给用户一句反馈。
     *
     * 为什么不在界面上只说"导入成功"：用户需要确认"我这份备份里确实有
     * 12 门课"——如果他刚才选错了文件（比如导了一份空的旧备份），
     * 只说"成功"会让他以为数据都在。
     */
    data class BackupSummary(val courses: Int, val slots: Int)

    private companion object {
        /** 8 MB。正常备份 < 100KB，这个上限只是防呆，不是性能优化。 */
        const val MAX_BACKUP_BYTES = 8L * 1024 * 1024
    }
}
