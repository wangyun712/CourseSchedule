package com.courseschedule.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.courseschedule.AppContainer
import com.courseschedule.ScheduleApp

/**
 * 从 Application 取依赖容器。传 `AppContainer` 而非 `Repository`：
 * ViewModel 依赖不止 Repository（如设置页还需 BackupManager），
 * 传容器可避免每加一个依赖就改一次调用签名。
 * 标 @Composable 只是因为要读 LocalContext，并非渲染 UI。
 */
@Composable
fun appContainer(): AppContainer {
    // LocalContext 可能是 Activity 级 Context，绝不能存入长生命周期对象（ViewModel、单例）
    val context = LocalContext.current

    // applicationContext 生命周期同进程，不会泄漏；向下转型的正确性由
    // AndroidManifest 声明 android:name=".ScheduleApp" 保证，漏配会在此抛 ClassCastException
    return (context.applicationContext as ScheduleApp).container
}

/**
 * 用依赖容器构造 ViewModel 的通用工厂（系统默认工厂只认无参构造）。
 *
 * 陷阱：`viewModel()` 按「ViewModelStoreOwner + 类型」缓存，NavHost 页面的
 * StoreOwner 是导航记录（NavBackStackEntry），同一条记录上参数不同也会拿到
 * 同一个 ViewModel。本项目靠每次 navigate 压新导航记录来隔离；
 * 若发现"编辑第二门课仍是旧表单"，先查这里。
 */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    crossinline create: (AppContainer) -> VM
): VM {
    // 每次重组都会执行，但 viewModel() 有缓存，同一 StoreOwner+类型只真正调一次 factory
    val container = appContainer()

    return viewModel(
        // viewModelFactory：官方 DSL，免去手写 Factory 的样板代码
        factory = viewModelFactory {
            initializer { create(container) }
        }
    )
}
