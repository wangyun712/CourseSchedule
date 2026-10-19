package com.courseschedule

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
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
import com.courseschedule.feature.placeholder.PlaceholderScreen
import com.courseschedule.feature.schedule.ScheduleScreen
import com.courseschedule.feature.settings.SettingsScreen
import com.courseschedule.ui.theme.CourseScheduleTheme

/**
 * # 唯一的 Activity
 *
 * ## 这个类为什么几乎全是空的
 *
 * 在 XML + Fragment 的传统安卓里，Activity 干很多活：`setContentView(R.layout.xxx)`
 * 加载布局、`findViewById` 找控件、注册监听器、处理 onSaveInstanceState……
 *
 * 在 Compose 里这些事情**全部搬到 Composable 里**了。所以 Activity 退化成一个
 * 很薄的壳，只负责三件事：
 *
 * 1. 让系统知道有这么一个界面存在（AndroidManifest 里注册）
 * 2. 拿到 Android 的系统能力入口：`setContent` 把 Compose 世界挂到窗口上
 * 3. 转发系统的生命周期回调（本项目里其实一个都没用到）
 *
 * ## 单 Activity 架构
 *
 * 整个 app 只有一个 Activity，四个"页面"（课表 / 便签 / 待办 / 设置 / 加课）
 * 都是 Composable，由 `NavHost` 在同一个 Activity 内部切换。
 *
 * 为什么不全用 Activity：
 * - 一个 Activity 一次只能有一个窗口；切页要做转场动画、要管理任务栈，很重
 * - Activity 之间传数据得走 Intent（序列化），而 Composable 之间直接传函数和对象
 * - 进程内共享的状态（比如 NavController、ViewModel）跨 Activity 就断了
 *
 * 什么时候确实需要第二个 Activity：需要出现在"最近任务"里独立一条、
 * 需要独立任务栈、或者要接收外部 App 的 intent（比如分享目标）。本项目都不需要。
 *
 * ## `ComponentActivity` 是什么
 *
 * `androidx.activity.ComponentActivity` 是 `AppCompatActivity` 的基类，
 * 也是 Compose 要求的最低门槛：`setContent` 是它的扩展函数。
 * 上层的 Fragment、AppCompat 那一套主题兼容机制，Compose 用不上，所以不继承它们。
 *
 * 图标一律用文字字形（"课""签""办"），不引 `material-icons-extended`：
 * 那个库有几千个矢量图标、几 MB 体积，我们只需要 3 个。
 */
class MainActivity : ComponentActivity() {

    /**
     * Activity 的入口。系统在创建窗口时调用，且**整条链路只调一次**
     * （转屏、切后台回来都不会再走这里，那些走 onResume/onStart）。
     *
     * `savedInstanceState` 是"上次被系统回收前的状态"，本项目用不上——
     * 我们的状态都在 ViewModel / Room 里，比 Bundle 可靠。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须调 super，它内部负责创建窗口、恢复 Dialog/Fragment、注册生命周期观察者
        super.onCreate(savedInstanceState)

        // 让内容延伸到状态栏和导航栏底下（全面屏观感）。
        // 开了它之后**必须自己处理内边距**，否则文字会被状态栏盖住——
        // 这就是下方 Scaffold 的 innerPadding 要做的事。
        enableEdgeToEdge()

        // Compose 的入口：把这棵 Composable 树"挂"到 Activity 的窗口上。
        // 之后界面就完全由状态驱动，再也不需要手动 findViewById 或通知刷新。
        setContent {
            // 自定义主题（见 ui/theme/Theme.kt）。必须在所有 Composable 之外包一层，
            // 因为 MaterialTheme 是通过 CompositionLocal 向下传递的，
            // 写在里面就来不及了。
            CourseScheduleTheme {
                // Surface 是"一块有背景色的画布"，也是 Material 的底层容器。
                // 作用：① 给整棵树一个不透明的底色（否则边缘区域是透明的）
                //      ② 提供 elevation 阴影语义 ③ 统一 contentColor
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
 * 底部导航栏的一个 Tab。
 *
 * 用 `data class` 而不是普通 class：需要 `equals` 来参与 Compose 的跳过判断
 * （值相同就不重组），而且以后要往里面加字段（比如红点数）时，
 * 结构体式的写法读起来更省事。
 *
 * `route` 是字符串，跟 NavHost 里注册的路由一一对应。
 */

// 为什么放在类外面（文件顶层）：这几个值是**编译期就确定的常量**，
// 写在 Composable 里每次重组都会重新构造一个 List，纯属浪费。
private data class TabItem(val route: String, val label: String, val glyph: String)

private val TABS = listOf(
    TabItem("schedule", "课表", "课"),
    TabItem("notes", "便签", "签"),
    TabItem("todos", "待办", "办")
)

/**
 * app 的根 Composable：底部导航 + 页面容器。
 *
 * `private` 是因为它只服务于本文件；外部要进 app 走 MainActivity。
 */
@Composable
private fun AppRoot() {

    // rememberNavController()：创建一个 NavController 并**跨重组记住它**。
    // 如果写成 navController()（没有 remember），每次重组都会新建一个，
    // 页面状态会瞬间丢失——这是 Compose 新手最常踩的坑之一。
    // 它内部其实是 rememberSaveable，所以转屏也能活下来。
    val navController = rememberNavController()

    // 当前栈顶的条目。注意这里要 asState：导航状态存在 NavController 里，
    // 是 Compose 管不到的"外部可变状态"，必须显式转成 State 才能触发重组。
    // `by` 是委托属性语法，等价于 .value，同时让 Compose 记录"读过它"。
    val backStackEntry by navController.currentBackStackEntryAsState()

    // 首帧时 backStackEntry 还是 null（NavHost 尚未完成首次导航），
    // 所以这里要 `?: null` 提前放行，否则底部栏会闪一下再出现。
    val currentRoute = backStackEntry?.destination?.route

    // 只有三个主 Tab 才显示底部栏。进到"设置""加课"这类二级页面时隐藏，
    // 视觉上有"钻进去了"的感觉，也把空间让给内容。
    // 为什么是 `any {}` 而不是 `currentRoute in listOf(...)`：
    // TABS 里是对象，比的是 route 字段，不能拿字符串直接比。
    val showBottomBar = currentRoute == null || TABS.any { it.route == currentRoute }

    // Scaffold 是 Material 的"页面骨架"，负责把 topBar / bottomBar / FAB /
    // 内容区按规范摆好，并且把"被系统栏遮挡的尺寸"算成 innerPadding 给出来。
    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                // 点当前页不重复导航。不加这个判断的话，
                                // 反复点同一个 Tab 会往栈里压多个相同路由。
                                if (currentRoute != tab.route) {
                                    navController.navigate(tab.route) {
                                        // ── 底部导航的标准三件套，缺一不可 ──

                                        // ① 压栈前先弹回起始页。否则 A→B→C→A→B 会
                                        //    无限堆栈，按返回键要按很多次才退得出去。
                                        popUpTo(navController.graph.startDestinationId) {
                                            // 弹的时候把每个页面的状态存起来
                                            // （滚动位置、输入内容都在里面）。
                                            saveState = true
                                        }

                                        // ② 目标是栈顶时不再新建，直接复用。
                                        //    配合上面那条，保证"同一路由在栈里只有一份"。
                                        launchSingleTop = true

                                        // ③ 恢复之前 saveState 存下的状态。
                                        //    和 ① 是一对：一个存，一个取。
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
        // ⚠️ innerPadding **必须**被消费掉。因为这个 app 开了 edge-to-edge，
        // 内容会画到系统栏底下；不 padding 的话课表会被底部导航栏压住。
        // 把它交给 NavHost 而不是每个页面各自处理，是因为页面本身
        // 不应该知道"外面有没有底部栏"——那是根布局的事。
        NavHost(
            navController = navController,
            startDestination = "schedule",
            modifier = Modifier.padding(innerPadding)
        ) {

            // ── 课表页（起始页）──
            // 它自己不碰 navController，只往外抛三个"我想去哪"的回调。
            // 这样 ScheduleScreen 能被 @Preview 单独渲染，也能被复用到别处。
            composable("schedule") {
                ScheduleScreen(
                    onOpenSettings = { navController.navigate("settings") },
                    // 点空白格子新增：把点中的星期和节次带过去做预填
                    onAddCourse = { day, section ->
                        navController.navigate("courseEdit?courseId=0&day=$day&section=$section")
                    },
                    // 点已有课程编辑：courseId 是真 id；day/section 传 1 是占位，
                    // 因为编辑模式不需要预填位置（位置由课程自己的 slots 决定）。
                    // 这是"一个页面服务两种用途"的常见小妥协。
                    onEditCourse = { courseId ->
                        navController.navigate("courseEdit?courseId=$courseId&day=1&section=1")
                    }
                )
            }

            // ── 便签页（P3）──
            // 现在是占位页，但它会真的去查 note 表，用条数证明表和 DAO 是通的。
            composable("notes") {
                PlaceholderScreen(
                    title = "便签",
                    description = "P3 实现：note 表已建好，这里会换成便签列表 + 编辑。"
                )
            }

            // ── 待办页（P4）──
            composable("todos") {
                PlaceholderScreen(
                    title = "待办 / 提醒",
                    description = "P4 实现：todo 表已建好，这里会换成待办列表 + AlarmManager 准点提醒。"
                )
            }

            // ── 设置页（学期 / 作息时间）──
            composable("settings") {
                SettingsScreen(onBack = { navController.popBackStack() })
            }

            // ── 加课 / 编辑页 ──
            // 路由写成 `courseEdit?courseId={courseId}&...` 的查询串形式，
            // 三个参数都是可选的（各自有 defaultValue）。
            // 如果用 `courseEdit/{courseId}` 的路径形式，就必须传参，
            // 而"新增"场景其实没有 courseId。
            composable(
                route = "courseEdit?courseId={courseId}&day={day}&section={section}",
                arguments = listOf(
                    // courseId 用 Long：Room 的自增主键是 Long，
                    // 用 Int 在 21 亿之后会溢出（虽然现实里不会，但类型要一致）。
                    // 0L 约定为"新建"（数据库自增 id 从 1 开始，永远不可能是 0）。
                    navArgument("courseId") { type = NavType.LongType; defaultValue = 0L },
                    navArgument("day") { type = NavType.IntType; defaultValue = 1 },
                    navArgument("section") { type = NavType.IntType; defaultValue = 1 }
                )
            ) { entry ->
                // arguments 里的值都是可空的（NavType 解析失败或没传时是 null），
                // 所以每个都要给兜底默认值——虽然上面已经声明了 defaultValue，
                // 但类型系统不认，`?:` 是给编译器看的。
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
