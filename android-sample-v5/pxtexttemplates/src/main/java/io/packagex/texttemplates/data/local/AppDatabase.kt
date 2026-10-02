package io.packagex.texttemplates.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [TemplateEntity::class], version = 3, exportSchema = false)
internal abstract class AppDatabase : RoomDatabase() {
    abstract fun templateDao(): TemplateDao
}
