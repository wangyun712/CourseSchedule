package com.courseschedule.data

import androidx.room.TypeConverter
import com.courseschedule.data.entity.WeekType

/**
 * Room 类型转换器：`WeekType` 枚举 ↔ Int，让 SQLite 能存枚举（在 `AppDatabase` 上用 `@TypeConverters` 注册）。
 * 存 Int 而非枚举名（TEXT），是因为改枚举名会让老数据读不出来、存数字免疫——
 * 因此 `WeekType.value` 的数值必须当成持久化格式，一旦发布就只能追加、不能修改。
 * 无需手动调用：Room 读写 `WeekType` 字段时自动调用；手写 SQL 时比较的是数字（`weekType = 1`）而非 'ODD'。
 */
class Converters {

    /** 存库方向：枚举 → Int。Room 在 INSERT / UPDATE 时自动调用。 */
    @TypeConverter
    fun weekTypeToInt(type: WeekType): Int = type.value

    /**
     * 读库方向：Int → 枚举。Room 在 SELECT 时自动调用。
     * 委托 `WeekType.from`：查不到返回 `ALL`（容错策略见其注释），不抛异常。
     */
    @TypeConverter
    fun intToWeekType(value: Int): WeekType = WeekType.from(value)
}
