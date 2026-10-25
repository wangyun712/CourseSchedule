package com.courseschedule.data.backup

import android.content.ContentResolver
import android.net.Uri
import com.courseschedule.data.ScheduleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * 备份文件的读写与落地，本类是唯一的 Android IO 层。
 * 职责切分：`BackupCodec` 管字节 ↔ 对象（纯函数，可单测），`ScheduleRepository` 管对象落库（事务），本类管文件 ↔ 对象。
 * 走 `ContentResolver` 是因为文件来自 SAF 系统选择器：拿不到也不该拿到真实路径，因此无需存储权限。
 */
class BackupManager(
    private val repository: ScheduleRepository,
    private val resolver: ContentResolver
) {

    /**
     * 导出到用户选定的 [uri]。磁盘操作切到 `Dispatchers.IO`：写云盘可能要等几秒，主线程会 ANR。
     * 打开模式用 `"wt"` 截断而不是 `"w"`：w 不截断，往更长的旧文件里写会留下残余，得到语法不完整的 JSON。
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
     * 顺序不能反：先完整读进内存、解析校验，再开事务写库。边读边写的话，坏文件写到一半失败，
     * 用户会处在"一半新一半旧"的状态——现在的写法保证要么全换、要么完全不动。
     * 8MB 上限防误选大文件导致 OOM（正常备份几十 KB），两道防线：
     * `statSize` 快检便宜，但部分云盘提供方拿不到大小（返回 -1）；读取时再靠 [readCapped] 边读边数兜底。
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
            stream.use { readCapped(it) }
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
     * 把流读成 UTF-8 文本，读取过程中按 [MAX_BACKUP_BYTES] 截断。
     * statSize 可能返回 -1（部分云盘提供方不报大小），那时这是唯一可靠防线：超限立刻抛 TOO_LARGE，绝不整读进内存。
     */
    private fun readCapped(input: InputStream): String {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(chunk)
            if (read == -1) break
            total += read
            if (total > MAX_BACKUP_BYTES) throw BackupException(BackupException.Reason.TOO_LARGE)
            buffer.write(chunk, 0, read)
        }
        return String(buffer.toByteArray(), Charsets.UTF_8)
    }

    /**
     * "导入了多少东西"的摘要，只用于给用户一句反馈。
     * 带条数是防"选错文件还以为成功"：只报"导入成功"，用户无法确认这份备份里的数据确实都在。
     */
    data class BackupSummary(val courses: Int, val slots: Int)

    private companion object {
        /** 8 MB。正常备份 < 100KB，这个上限只是防呆，不是性能优化。 */
        const val MAX_BACKUP_BYTES = 8L * 1024 * 1024
    }
}
