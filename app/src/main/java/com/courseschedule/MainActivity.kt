package com.courseschedule

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.courseschedule.feature.courseedit.CourseEditScreen
import com.courseschedule.feature.note.NoteEditScreen
import com.courseschedule.feature.note.NoteListScreen
import com.courseschedule.feature.schedule.ScheduleScreen
import com.courseschedule.feature.settings.SettingsScreen
import com.courseschedule.feature.todo.TodoEditScreen
import com.courseschedule.feature.todo.TodoListScreen
import com.courseschedule.ui.theme.CourseScheduleTheme

/**
 * 全 app 唯一的 Activity（单 Activity 架构）：所有页面都是 Composable，
 * 由 `NavHost` 在本 Activity 内切换，页面间直接传回调，不走 Intent 序列化。
 * 图标一律用文字字形，不引 material-icons-extended（几 MB 体积只为了 3 个图标）。
 */
class MainActivity : ComponentActivity() {

    /**
     * 入口：挂载 Compose 树。状态都存于 ViewModel/Room，不依赖 savedInstanceState。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 内容延伸到系统栏底下；此后必须自行处理内边距（见下方 Scaffold 的 innerPadding）
        enableEdgeToEdge()
        setContent {
            // 主题必须包在最外层：MaterialTheme 靠 CompositionLocal 向下传递
            CourseScheduleTheme {
                // 提供不透明底色，避免 edge-to-edge 下边缘区域透出
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppRoot()
                }
            }
        }
    }
}

/**
 * 底部导航栏的一个 Tab。`route` 与 NavHost 注册的路由一一对应；
 * 用 data class 以按值比较，参与 Compose 跳过判断。
 */

// 声明在文件顶层：编译期确定的常量，避免每次重组重新构造 List
private data class TabItem(val route: String, val label: String, val glyph: String)

private val TABS = listOf(
    TabItem("schedule", "课表", "课"),
    TabItem("notes", "便签", "签"),
    TabItem("todos", "待办", "办")
)

/**
 * app 根 Composable：底部导航 + NavHost 页面容器。
 */
@Composable
private fun AppRoot() {

    // 内部是 rememberSaveable：转屏后导航状态仍在
    val navController = rememberNavController()

    // 导航状态是 NavController 里的外部可变状态，须转成 State 才能触发重组
    val backStackEntry by navController.currentBackStackEntryAsState()

    // 首帧 backStackEntry 尚为 null（NavHost 未完成首次导航），提前放行避免底部栏闪烁
    val currentRoute = backStackEntry?.destination?.route

    // 仅三个主 Tab 显示底部栏；二级页面（设置、编辑页）隐藏
    val showBottomBar = currentRoute == null || TABS.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                // 点当前页不重复导航，否则会往栈里压多个相同路由
                                if (currentRoute != tab.route) {
                                    navController.navigate(tab.route) {
                                        // 底部导航标准三件套，缺一不可：popUpTo 弹回起始页防无限堆栈；
                                        // saveState/restoreState 成对存取各页状态（滚动位置等）；
                                        // launchSingleTop 保证同一路由在栈里只有一份
                                        popUpTo(navController.graph.startDestinationId) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            // 图标用文字字形：省一个几 MB 的图标依赖
                            icon = { Text(tab.glyph, fontSize = 14.sp) },
                            label = { Text(tab.label, fontSize = 11.sp) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        // innerPadding 必须被消费，否则内容会被底部导航栏/系统栏压住；
        // consumeWindowInsets 防止 imePadding 把已扣除的系统栏高度再算一遍；
        // edge-to-edge 下键盘只以 inset 形式下发，不 imePadding 则底部输入框会被键盘盖住
        NavHost(
            navController = navController,
            startDestination = "schedule",
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
        ) {

            // 课表页（起始页）：页面只抛回调、不碰 navController，便于 @Preview 与复用
            composable("schedule") {
                ScheduleScreen(
                    onOpenSettings = { navController.navigate("settings") },
                    // 点空白格子新增：把点中的星期和节次带过去做预填
                    onAddCourse = { day, section ->
                        navController.navigate("courseEdit?courseId=0&day=$day&section=$section")
                    },
                    // 编辑模式：courseId 是真 id；day/section 传 1 仅占位，
                    // 编辑时不需要预填位置（由课程自己的 slots 决定）
                    onEditCourse = { courseId ->
                        navController.navigate("courseEdit?courseId=$courseId&day=1&section=1")
                    }
                )
            }

            // 便签页
            composable("notes") {
                NoteListScreen(
                    onAddNote = { navController.navigate("noteEdit?noteId=0") },
                    onEditNote = { noteId -> navController.navigate("noteEdit?noteId=$noteId") }
                )
            }

            // 便签编辑页：noteId=0 表示新建；每次 navigate 压新的导航记录
            // （即新的 ViewModel），连续编辑多条不会串数据
            composable(
                route = "noteEdit?noteId={noteId}",
                arguments = listOf(
                    navArgument("noteId") { type = NavType.LongType; defaultValue = 0L }
                )
            ) { entry ->
                NoteEditScreen(
                    noteId = entry.arguments?.getLong("noteId") ?: 0L,
                    onBack = { navController.popBackStack() }
                )
            }

            // 待办页
            composable("todos") {
                TodoListScreen(
                    onAddTodo = { navController.navigate("todoEdit?todoId=0") },
                    onEditTodo = { todoId -> navController.navigate("todoEdit?todoId=$todoId") }
                )
            }

            // 待办编辑页
            composable(
                route = "todoEdit?todoId={todoId}",
                arguments = listOf(
                    navArgument("todoId") { type = NavType.LongType; defaultValue = 0L }
                )
            ) { entry ->
                TodoEditScreen(
                    todoId = entry.arguments?.getLong("todoId") ?: 0L,
                    onBack = { navController.popBackStack() }
                )
            }

            // 设置页（学期/作息时间）
            composable("settings") {
                SettingsScreen(onBack = { navController.popBackStack() })
            }

            // 加课/编辑页：查询串形式使三个参数均可选（各有 defaultValue）；
            // 路径形式则必须传参，而新增场景没有 courseId
            composable(
                route = "courseEdit?courseId={courseId}&day={day}&section={section}",
                arguments = listOf(
                    // courseId 用 Long 以匹配 Room 自增主键；0L 约定为"新建"
                    // （自增 id 从 1 开始，0 不可能是真实 id）
                    navArgument("courseId") { type = NavType.LongType; defaultValue = 0L },
                    navArgument("day") { type = NavType.IntType; defaultValue = 1 },
                    navArgument("section") { type = NavType.IntType; defaultValue = 1 }
                )
            ) { entry ->
                // arguments 的值可能为 null（解析失败或未传），defaultValue 不影响其可空类型，
                // 故仍需兜底
                CourseEditScreen(
                    courseId = entry.arguments?.getLong("courseId") ?: 0L,
                    presetDay = entry.arguments?.getInt("day") ?: 1,
                    presetSection = entry.arguments?.getInt("section") ?: 1,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
