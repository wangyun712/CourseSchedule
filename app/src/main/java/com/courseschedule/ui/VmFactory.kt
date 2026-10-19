package com.courseschedule.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.courseschedule.ScheduleApp
import com.courseschedule.data.ScheduleRepository

/**
 * # 手写依赖注入：从 Application 里取 Repository
 *
 * ## 为什么不上 Hilt / Koin
 *
 * 这个 app 只有 1 个 Repository 和 4 个 ViewModel。Hilt 会带来
 * 注解处理器（kapt/KSP）+ 代码生成 + 构建变慢，收益为负。
 * 手动传参在这个规模下更直白：想知道 ViewModel 依赖什么，看构造函数一眼就够，
 * 不用去追 `@Inject` 和 `@Module`。等依赖多到手动传不下去再换也不迟。
 *
 * ## 为什么这是个 `@Composable` 函数
 *
 * 因为它需要 `LocalContext`——而 `LocalContext` 只能在 Composable 里读。
 * 这是"从宿主环境里取东西"的唯一途径：Compose 里没有 `getApplication()` 这种魔法。
 *
 * @Composable 在这里**不代表会渲染 UI**，只代表"它依赖 Compose 的运行时环境"。
 * 返回值是个普通对象，直接 return 没有任何问题。
 */
@Composable
fun appRepository(): ScheduleRepository {
    // LocalContext.current 是 CompositionLocal 的写法，读的是"当前最近的 Context"。
    //
    // ⚠️ 内存泄漏陷阱：这个 Context 在某些场景下是 Activity 级别的
    // （比如在 Activity 里 setContent 时）。**绝对不能把它存进长生命周期的对象**
    // （ViewModel、单例、companion object）：Activity 销毁后对象还活着，
    // 就把它引用住了，整棵 View 树都回收不掉。
    // 传得越远越危险，所以这个函数只在原地用、不往上传。
    val context = LocalContext.current

    // 取 applicationContext 而不是直接用 context：Application 的生命周期
    // 跟进程一样长，拿它做什么都不会泄漏。
    //
    // `as ScheduleApp` 是向下转型。安全性由 AndroidManifest 保证：
    // 那里声明了 application 用 ScheduleApp，所以运行期必定是这个类型。
    // 如果 Manifest 里漏了 `android:name`，这行会抛 ClassCastException——
    // 这是本项目最容易返工的地方，改 Manifest 时务必检查。
    return (context.applicationContext as ScheduleApp).container.repository
}

/**
 * # 用 Repository 造 ViewModel 的通用工厂
 *
 * 调用长这样：
 * ```
 * val vm = appViewModel { repo -> ScheduleViewModel(repo) }
 * ```
 *
 * ## 为什么 ViewModel 的构造函数里可以写 Repository
 *
 * ViewModel 是普通类（不继承任何东西），构造函数随便定义。
 * 麻烦的只是"谁来 new 它"——系统默认的工厂只会调无参构造，
 * 所以我们必须自己提供一个 factory 告诉系统怎么造。
 *
 * ## `inline` + `reified` 为什么是必需的
 *
 * `viewModel(factory = ...)` 需要知道**具体类型**才能决定缓存 key 和返回类型。
 * 普通泛型 `<VM : ViewModel>` 在运行时会被擦除成 `ViewModel`，做不到。
 * 只有 `inline`（把函数体复制到调用点）+ `reified`（在该处看到真实类型）
 * 才能让 `VM` 在运行时可用。
 *
 * 代价：inline 函数体会被复制到每个调用点，编译后体积略大。
 * 这个函数只有 3 行，不值一提。
 *
 * ## `crossinline` 是什么
 *
 * `create` 这个参数在函数体里被放进了**另一个 lambda**（`initializer { ... }`）内部。
 * Kotlin 默认允许 inline 函数的 lambda 参数做"非局部返回"（`return` 直接穿出去），
 * 但穿进另一个 lambda 之后这个能力就不再安全了。`crossinline` 就是承诺
 * "我不会在里面用非局部返回"。
 *
 * 如果去掉它，编译器会直接报错，不需要我们记住规则。
 *
 * ## ⚠️ 一个必须知道的坑：ViewModel 是按 key 缓存的
 *
 * `viewModel()` 的缓存键是「ViewModelStoreOwner + 类型（+ 可选的 key）」。
 * 对于 NavHost 里的页面，StoreOwner 是**那条导航记录（NavBackStackEntry）**。
 *
 * 后果：同一个 Composable 如果只是**参数不同**（比如 `courseEdit?courseId=1`
 * 和 `courseEdit?courseId=2` 落在同一条导航记录上），拿到的会是**同一个 ViewModel**，
 * 里面的状态还是旧课程的。
 *
 * 本项目绕开它的办法是：每次跳转都用 `navController.navigate(...)` 压一条新的
 * 导航记录，新记录 = 新的 StoreOwner = 新的 ViewModel。
 * 所以 CourseEditScreen 里读的是路由参数而不是复用同一个实例。
 *
 * 如果哪天发现"编辑第二门课时表单还是第一门课的内容"，先查这里。
 */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    crossinline create: (ScheduleRepository) -> VM
): VM {
    // 每次重组都会执行到这里，但下面 viewModel() 内部有缓存，
    // 同一个 ViewModelStoreOwner + 同一个类型只会真正调用一次 factory。
    // 所以这里重复读 repository 是无害的（它本身也就是取个引用）。
    val repository = appRepository()

    return viewModel(
        // viewModelFactory {} 是官方提供的 DSL，比手写 ViewModelProvider.Factory
        // 少一堆样板代码（不用再写 `when (modelClass)` 和 unchecked cast）。
        factory = viewModelFactory {
            // initializer 的 lambda 参数是 CreationExtras，我们不需要，
            // 所以用 `initializer {` 忽略掉它。
            initializer { create(repository) }
        }
    )
}
