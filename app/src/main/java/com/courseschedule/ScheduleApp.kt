package com.courseschedule

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.courseschedule.data.AppDatabase
import com.courseschedule.data.ScheduleRepository
import com.courseschedule.data.backup.BackupManager

/**
 * Application：进程级单例，持有全局唯一的数据库与 Repository。
 * 必须在 AndroidManifest 的 <application> 上声明 android:name=".ScheduleApp"，
 * 否则系统创建默认 Application，container 未初始化，取依赖时会抛
 * UninitializedPropertyAccessException。
 */
class ScheduleApp : Application() {

    /**
     * 全 app 唯一的依赖容器。private set 防止被外部替换，
     * 否则会出现两份数据库连接（数据不一致、文件锁冲突）。
     */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // 进程最早的初始化点（先于所有 Activity/Service）
        container = AppContainer(this)
    }
}

/**
 * 手写 DI 容器：集中创建依赖，保证数据库只建一份（多份实例有文件锁问题）。
 * 规模小（1 个 Repository、4 个 ViewModel）不值得上 Hilt/Koin；
 * 待手动传递污染构造函数签名或出现环形依赖时再引入 DI 框架。
 * 注意：构造参数必须传 Application 级 Context，传 Activity 会被 Room 持有导致泄漏。
 */
class AppContainer(context: Context) {

    /**
     * Room 数据库。private 封装：全 app 只有 Repository 能碰数据库，
     * 换存储实现只改 Repository 一个文件。build() 是懒加载，
     * 首次执行 SQL 才真正打开文件，放在 onCreate 不会拖慢启动。
     */
    private val database: AppDatabase = Room
        .databaseBuilder(context, AppDatabase::class.java, "schedule.db")
        .build()

    /**
     * 唯一对外出口。Repository 无状态（状态都在数据库里），单实例全局复用。
     */
    val repository: ScheduleRepository = ScheduleRepository(database)

    /**
     * 备份读写器。刻意不放 Repository，让数据层保持不依赖 Android API，
     * 以便写纯 JVM 测试；contentResolver 来自 Application，无泄漏风险。
     */
    val backupManager: BackupManager = BackupManager(repository, context.contentResolver)
}
