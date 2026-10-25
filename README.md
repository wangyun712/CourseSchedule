# 课程表 · CourseSchedule

一个**完全离线**的 Android 课程表：不联网、不申请任何权限，所有数据只存在手机本地的 Room 数据库里。

写它的原因很简单——市面上的课表 App 大多要账号、要联网、要通知权限，而学生每天真正需要的其实只有三件事：今天上什么、这一周怎么排、记两笔便签和待办。

## 功能

**课表**

- 7 × N 周视图，左右翻周，今天那一列高亮；不在本周时顶部有「回到本周」
- 今日课程卡固定在课格上方（不跟着滚）：日期条 + 时间轴，正在上的课标「上课中」，超过 3 节默认折叠、可点开
- 点空白格子加课（带上点中的星期；节次默认第 1 节，进页面可改），点课程色块进编辑
- 一门课可挂多条时间段：星期 + 起止节次 + 起止周 + 单/双周，能表达「第 9–16 周单周上课」这类真实排课
- 每条时间段可单独覆盖教室（同一门课不同周换教室）
- 8 色配色，同一门课在所有位置颜色一致

**学期与作息**

- 学期名称、开学日期、总周数；当前第几周由开学日期算出，也能手动指定
- 节次作息可逐节改上下课时间，周视图左侧显示每节几点开始

**便签**

- 两列瀑布流（长短不一的卡片不留大片空白），置顶优先，按修改时间倒序
- 自动保存：停手 600 ms 落库；返回、完成、系统返回键三条路径都会强制刷盘
- 不写标题也能存，列表用正文首行当标题显示

**待办**

- 未完成 / 已完成分组，点圆圈勾选，点行内其它位置进编辑
- 截止日期（逾期标红，已完成的不算逾期），可关联到某门课程
- 没有截止日期的排在有日期的后面

**数据**

- 导出 / 恢复备份为单个 `.json`，走系统文件选择器（SAF），**不需要存储权限**
- 恢复是整库覆盖：先完整解析校验、再开单个事务清空重建，中途失败自动回滚
- 恢复前二次确认，弹窗里写明当前有多少课、便签、待办会被替换

**其它**

- 深色模式跟随系统，字号跟随系统字体缩放
- 一门课都没有时，课格中央显示引导卡（格子仍然可点）

## 不做的

| 不做 | 原因 |
|---|---|
| 上课提醒 / 通知 / 闹钟 | 要通知与精确闹钟权限、重启后重注册、还得对抗国产 ROM 的后台限制；需求已由「今日课程卡置顶」覆盖 |
| 云同步 / 账号 / 任何网络功能 | `AndroidManifest.xml` 里没有 `INTERNET` 权限，这是系统层面的保证，不是口头承诺 |
| 多学期切换 | 要引入「当前学期」标记与全表迁移；一年换两次，改开学日期就够了 |
| 课表冲突检测 | 会挡住「必修 + 选修排在同一格」的真实需求 |
| 待办重复规则 | 每周几 × 每几天 × 截止日期是组合爆炸，做错的表现是静默漏数据。字段保留，v1 只存空串 |
| 桌面小组件 | 渲染模型与周视图完全不同，成本远超收益 |
| 自动备份 / 定期提醒导出 | 又是一个后台机制加一次打扰；改成设置页里显眼的导出按钮 |

## 技术栈

| 项 | 版本 / 说明 |
|---|---|
| 语言 | Kotlin 2.4.21 |
| UI | Jetpack Compose + Material 3（BOM 2026.09.00），单 Activity + Navigation Compose |
| 数据库 | Room 2.8.5（KSP），schema 导出到 `app/schemas/` |
| 序列化 | kotlinx.serialization 1.11.0，只用于备份文件；编译期生成、无反射，可直接跑 JVM 单测 |
| 构建 | AGP 9.4.1，Gradle Wrapper 9，JDK 17 |
| SDK | compileSdk 37 / targetSdk 37 / **minSdk 26** |
| 依赖注入 | 手写 `AppContainer`（1 个 Repository、1 个 BackupManager、7 个 ViewModel——这个规模上 Hilt 是负收益） |

四层结构：`data`（Room 实体 / DAO / Repository / 备份）→ `core`（周次与时间计算，纯 Kotlin，可单测）→ `feature`（每个页面一组 Screen + ViewModel）→ `ui`（主题、8 色调色板、跨页面小组件）。

`feature` 层不直接碰 Android API，也拿不到 `NavController`——页面只往外抛「我想去哪」的回调，具体跳转由 `MainActivity` 的路由表决定。这样每个页面都能单独 `@Preview`，也不依赖导航库。

## 构建

需要 JDK 17 与 Android SDK 37（含 build-tools）。在根目录建 `local.properties`，写上本机 SDK 路径：

```properties
sdk.dir=C\:\\path\\to\\Android\\Sdk
```

```bash
./gradlew :app:assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # 周次换算 17 例 + 备份编解码 11 例
./gradlew :app:assembleRelease      # R8 混淆 + 资源压缩
```

## Release 签名

签名材料不进仓库（`.gitignore` 已排除 `*.jks` / `*.keystore` / `keystore.properties`）。在根目录建 `keystore.properties`：

```properties
storeFile=../release.jks
storePassword=你的密码
keyAlias=courseschedule
keyPassword=你的密码
```

`storeFile` 的**相对路径以 `app/` 模块目录为基准**，所以根目录的密钥要写 `../release.jks`——写绝对路径反而容易踩坑（会被拼成 `app/` 下的相对路径，报 `Missing keystore`）。

没有 `keystore.properties` 时，release 变体会退回 debug 签名并在构建日志里警告，这样 R8 这条链路仍然能装到手机上验证。**debug 签名的包不能对外发布**（密钥是公用的，且和正式包签名不同，无法互相覆盖安装）。

## 数据与隐私

- 安装时**不提示任何权限**：没有 `INTERNET`、没有 `POST_NOTIFICATIONS`、没有存储权限
  （合并后的清单里只有 `androidx.core` 自动注入的一条 signature 级私有权限，属于库内部机制，不涉及用户数据）
- `android:allowBackup="false"`：关掉系统自动云备份，否则系统服务会把数据库传到用户的云账号，和「数据只在本机」矛盾
- 卸载会连同数据库一起删除，**换机 / 重装前请先导出备份**
- 备份文件是明文 JSON，存哪里由你决定（网盘、电脑、U 盘都行）

## 备份文件格式

```json
{
  "schemaVersion": 1,
  "exportedAt": 1760000000000,
  "semester": { "id": 1, "name": "2026 秋", "startEpochDay": 20500, "totalWeeks": 20, "weekOverride": null },
  "courses": [{ "id": 1, "semesterId": 1, "name": "高等数学", "teacher": "张三", "location": "教三-401", "colorIndex": 0, "note": "" }],
  "slots": [{ "id": 1, "courseId": 1, "dayOfWeek": 1, "startSection": 1, "endSection": 2, "startWeek": 1, "endWeek": 16, "weekType": 0, "roomOverride": "" }],
  "periods": [{ "id": 1, "semesterId": 1, "section": 1, "startMinute": 480, "endMinute": 525 }],
  "notes": [{ "id": 1, "title": "期中范围", "content": "…", "colorIndex": 0, "pinned": false, "updatedAt": 1760000000000 }],
  "todos": [{ "id": 1, "title": "交作业", "note": "", "dueAt": 1760304000000, "remindAt": null, "repeatRule": "", "done": false, "courseId": 1 }]
}
```

约定：

- `schemaVersion` 高于当前版本的文件会被**直接拒绝**并提示升级 App，不做兼容猜测
- `notes` / `todos` 即使为空也写出空数组，保证格式稳定
- 恢复时 id 全部重新编号，时间段与待办的 `courseId` 跟着重映射，旧文件不会和新数据撞主键
- `weekType`：`0` 每周 / `1` 单周 / `2` 双周；`dueAt` 是**毫秒时间戳**（存为截止当天本地 00:00，界面上按"天"取用），不是 epochDay
- 文件上限 8 MB，超过直接判为「不是有效的备份文件」，避免误选大文件导致 OOM

## 目录结构

```
app/src/main/java/com/courseschedule/
├── MainActivity.kt            单 Activity：底部导航 + 路由表
├── ScheduleApp.kt             Application：建库、装配 AppContainer
├── core/                      WeekCalculator（周次/日期换算）、TimeUtils
├── data/
│   ├── entity/Entities.kt     semester / course / course_slot / period_time / note / todo
│   ├── dao/                   CourseDao、SemesterDao、ExtraDaos（Note + Todo）
│   ├── backup/                BackupModels（DTO + 编解码 + 校验）、BackupManager（SAF 读写）
│   ├── AppDatabase.kt         Room 数据库，version = 1
│   └── ScheduleRepository.kt  唯一数据出口
├── feature/
│   ├── schedule/              周视图、今日卡片、空课表引导
│   ├── courseedit/            加课 / 编辑课程
│   ├── note/                  便签列表 + 自动保存的编辑页
│   ├── todo/                  待办列表 + 编辑页
│   └── settings/              学期、作息、数据、关于
└── ui/                        主题、调色板、跨页面小组件、ViewModel 工厂

app/schemas/                   Room 导出的 schema，写 migration 的唯一依据（必须提交）
app/src/test/                  纯 JVM 单测（不依赖 Android Framework）
```

## 开发状态

| 里程碑 | 内容 | 状态 |
|---|---|---|
| P1 | 周视图 + 加课编辑 + 学期作息 | 完成 |
| M1 | 备份与恢复（JSON + SAF） | 完成 |
| M2 | 便签（瀑布流 + 置顶 + 自动保存） | 完成 |
| M3 | 待办（分组 + 截止日期 + 关联课程） | 完成 |
| M4 | 收尾：空课表引导、图标、关于、release 签名 | 完成 |

各项取舍与「明确不做」的理由见 [PLAN.md](PLAN.md)。

## 许可

尚未指定开源许可证。仓库公开可读，但在补充 LICENSE 之前不构成任何授权。
