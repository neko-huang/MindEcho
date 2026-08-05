package com.moodecho.app.data.db

import androidx.room.TypeConverter
import com.moodecho.app.domain.model.EmotionType

/**
 * Type converters for Room to handle custom types (enums, lists).
 * Room 2.6+ has built-in enum support, but explicit TypeConverters
 * ensure KSP can resolve all type references during annotation processing.
 */
class Converters {
    @TypeConverter
    fun fromEmotionType(value: EmotionType): String = value.name

    @TypeConverter
    fun toEmotionType(value: String): EmotionType =
        EmotionType.valueOf(value.uppercase())
}