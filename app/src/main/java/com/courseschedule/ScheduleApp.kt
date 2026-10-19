package com.courseschedule

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.courseschedule.data.AppDatabase
import com.courseschedule.data.ScheduleRepository
import com.courseschedule.data.backup.BackupManager

/**
 * # Application：进程级的"单例容器"
 *
 * `Application` 是 Android 里**每个进程只有一个**的对象，生命周期跟进程一样长：
 * 进程起来就创建，进程被杀才销毁。所以它是存放"全局唯一、且需要 Context
 * 才能创建"的东西的标准位置——这里放的就是数据库和 Repository。
 *
 * ⚠️ 注册方式：光写这个类没用，必须在 `AndroidManifest.xml` 的 `<application>`
 * 标签上加 `android:name=".ScheduleApp"`。不加的话系统会给你一个默认的
 * `android.app.Application`，`container` 永远没被初始化，
 * 各个页面调 `appRepository()` 时会抛 `UninitializedPropertyAccessException`。
 */
class ScheduleApp : Application() {

    /**
     * 全 app 唯一的依赖容器。
     *
     * `lateinit`（延迟初始化）：因为它的值要等到 `onCreate` 里才能算出来。
     * 如果写成 `val container = AppContainer(this)`，那会在构造函数阶段就用到
     * `this`——此时 Application 自己还没初始化完，属于危险操作。
     *
     * `private set`：外界只能读，不能替换。防止某个页面手一抖把它换成别的实例，
     * 那样就会出现两份数据库连接（数据不一致、文件锁冲突）。
     */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // 这是整个进程最早能跑我们自己代码的地方（在 Activity/Service 之前）。
        container = AppContainer(this)
    }
}

/**
 * # 依赖容器（手写 DI）
 *
 * ## 它解决什么问题
 *
 * 谁需要 Repository 就来这里拿，不用自己 new。好处是：
 * - 数据库只被创建一次（Room 实例很贵，多份实例还会有文件锁问题）
 * - 以后想让 ViewModel 拿到别的东西（比如设置项读取器），
 *   只需在这里加一行，不用改所有调用点
 *
 * ## 为什么不上 Hilt / Koin
 *
 * 全 app 只有 1 个 Repository 和 4 个 ViewModel。Hilt 要引注解处理器，
 * 每次构建多几十秒，还会生成一堆看不见的代码；Koin 要额外学一套 DSL。
 * 手动传参在这种规模下更简单、更好调试，出问题直接点进去看就行。
 *
 * 判断标准：**当"手动传递"开始污染构造函数签名（比如某个 ViewModel 要 6 个依赖），
 * 或者出现环形依赖**，才是引入 DI 框架的时候。
 *
 * ## 构造函数里的 Context
 *
 * 这里收的是 `Context` 而不是 `Application`，但**实际传进来的永远是
 * ScheduleApp 实例**（见上面 `AppContainer(this)`）。
 *
 * ⚠️ 如果哪天在 Activity 里写 `AppContainer(this)`（传 Activity），
 * Room 会持有这个 Activity 的强引用，Activity 销毁后内存回收不掉，就是内存泄漏。
 * 保险的写法是构造函数里就取 `context.applicationContext`——
 * 本项目靠"只有一个调用点"来保证，所以没加这层防御。
 */
class AppContainer(context: Context) {

    /**
     * Room 数据库。声明为 `private` 是刻意的封装：
     *
     * 全 app 只有 Repository 一个类能碰数据库，其他所有层（包括 ViewModel）
     * 一律只看得到 Repository。这样"换存储"（比如以后换成 SQLDelight、
     * 或者加一层网络缓存）只需要改 Repository 一个文件。
     *
     * 如果为了图方便把 database 暴露出去，很快就会出现
     * "某个 ViewModel 直接调 DAO"的野路子，业务规则开始散落。
     *
     * `.databaseBuilder()` 三个参数：
     * - context：Application 的 context（见上面的泄漏警告）
     * - 实体类：AppDatabase::class.java，Room 靠反射读它上面的 @Database 注解
     * - 数据库文件名：落在 /data/data/com.courseschedule/databases/schedule.db
     *
     * `.build()` 是**懒的**：这里不会真的打开文件，第一次执行 SQL 时才开。
     * 所以它很快，放在 Application.onCreate 里不会拖慢启动。
     */
    private val database: AppDatabase = Room
        .databaseBuilder(context, AppDatabase::class.java, "schedule.db")
        .build()

    /**
     * 唯一对外的出口。
     *
     * 注意它是 `val` 且直接初始化——Repository 是无状态的（所有状态都在数据库里），
     * 一个实例全局够用，不需要每次新建。
     */
    val repository: ScheduleRepository = ScheduleRepository(database)

    /**
     * 备份读写器。
     *
     * 它需要 `ContentResolver` 才能走系统文件选择器读写文件——
     * 这件事**刻意不放 Repository 里**：Repository 的注释里写了
     * "不需要 Android 的任何东西"，一旦让它碰 ContentResolver，
     * 那层就被污染了，以后想给数据层写纯 JVM 测试也做不到了。
     *
     * `context` 在这里是 Application（见类注释的泄漏警告），
     * `contentResolver` 跟着它的生命周期，安全。
     */
    val backupManager: BackupManager = BackupManager(repository, context.contentResolver)
}
