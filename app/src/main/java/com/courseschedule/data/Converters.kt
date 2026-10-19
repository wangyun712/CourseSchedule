package com.courseschedule.data

import androidx.room.TypeConverter
import com.courseschedule.data.entity.WeekType

/**
 * Room 的类型转换器：让数据库能存 Kotlin 的枚举。
 *
 * ## 为什么需要这个类
 *
 * SQLite 只认五种存储类型：NULL、INTEGER、REAL、TEXT、BLOB。
 * 它不认识 Kotlin 的枚举、`Date`、`UUID` 这些类型。
 * 当实体里出现了 SQLite 不认识的字段类型时，Room **在编译期就会报错**
 * （不是运行期），错误信息大致是
 * `Cannot figure out how to save this field into database`。
 *
 * 解决办法就是在这里声明"怎么存、怎么取"的一对函数，然后在
 * [AppDatabase] 上用 `@TypeConverters` 注册。
 *
 * ## 为什么存 Int 而不是存枚举名
 *
 * 另一个常见做法是 `type.name` 存 `"ODD"` 这样的字符串。本项目选了 Int：
 *
 * | 方案 | 占空间 | 可读性 | 改名安全性 |
 * |---|---|---|---|
 * | `type.name`（TEXT） | 3-5 字节 | 手翻数据库时直观 | **改名即毁数据** |
 * | `type.value`（INTEGER） | 1 字节 | 要对照 [WeekType] 才知道 0/1/2 | 改名不影响数据 |
 *
 * 关键在最后一列。这个 app 的数据在用户手机上，一旦发了版本，
 * 任何"改枚举名"的改动都会让老数据读不出来。存数字则完全免疫。
 *
 * 代价是直接打开数据库文件时看不懂——所以 [WeekType] 里的 `value` 数值
 * 必须当成**持久化格式**来对待，一旦发布就只能追加、不能修改。
 *
 * ## 用法
 *
 * 你**不需要手动调用**这两个函数。Room 生成的 DAO 实现会在读写
 * [WeekType] 字段时自动调用它们。只要 [AppDatabase] 上标了
 * `@TypeConverters(Converters::class)`，剩下的都是自动的。
 *
 * 自己写查询时要注意：SQL 里比较的是转换后的数字，不是枚举名。
 * ```
 * -- 对："单周"
 * SELECT * FROM course_slot WHERE weekType = 1
 * -- 错：查不到任何东西（SQLite 会把 'ODD' 当成一个普通字符串比较）
 * SELECT * FROM course_slot WHERE weekType = 'ODD'
 * ```
 */
class Converters {

    /** 存库方向：枚举 → Int。Room 在 INSERT / UPDATE 时自动调用。 */
    @TypeConverter
    fun weekTypeToInt(type: WeekType): Int = type.value

    /**
     * 读库方向：Int → 枚举。Room 在 SELECT 时自动调用。
     *
     * 委托给 [WeekType.from]，那里的容错策略（查不到返回 `ALL`）见其注释。
     */
    @TypeConverter
    fun intToWeekType(value: Int): WeekType = WeekType.from(value)
}
