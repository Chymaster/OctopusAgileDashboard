package com.chymaster.octopusagiledashboard.data.local

import androidx.room.TypeConverter
import java.time.Instant

class Converters {

    @TypeConverter
    fun fromInstant(instant: Instant?): Long? = instant?.toEpochMilli()

    @TypeConverter
    fun toInstant(millis: Long?): Instant? = millis?.let { Instant.ofEpochMilli(it) }
}
