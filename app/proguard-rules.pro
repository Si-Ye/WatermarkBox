# ===== 水印盒 Release 混淆规则 =====

# ---- Kotlinx Serialization ----
# @Serializable 类生成的 serializer 依赖伴生对象，必须保留
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# 保留本项目的 @Serializable 数据模型（预设、水印配置等）及其 serializer
-keep,includedescriptorclasses class com.watermarkbox.data.model.**$$serializer { *; }
-keepclassmembers class com.watermarkbox.data.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.watermarkbox.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# 保留字段名，避免 JSON 键名被混淆导致旧数据读取失败
-keepclassmembers class com.watermarkbox.data.model.** {
    <fields>;
}

# ---- AndroidX DataStore / Preferences ----
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

# ---- WorkManager / Room ----
# Worker 由系统按类名反射实例化，不能被混淆
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(...);
}
-keepclassmembers class * extends androidx.work.ListenableWorker {
    <init>(...);
}

# WorkManager 内部依赖 Room 建库。Room 通过【类名字符串】反射加载
# 生成的 <Database>_Impl 实现类，一旦被混淆就会抛：
#   RuntimeException: Failed to create an instance of class ...WorkDatabase.canonicalName
# 因此必须完整保留 Room 相关类与 androidx.work.impl 下的数据库实现。
-keep class androidx.work.impl.WorkDatabase { *; }
-keep class androidx.work.impl.WorkDatabase_Impl { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class androidx.room.** { *; }
-dontwarn androidx.room.**
# 保留 Room 注解处理生成的构造器与 DatabaseImpl 命名约定
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>(...);
    public static ** create(...);
}

# ---- Jetpack Compose ----
# Compose 运行时通过反射访问部分内部结构
-dontwarn androidx.compose.**
-keep class androidx.compose.runtime.** { *; }

# ---- ExifInterface ----
-keep class androidx.exifinterface.** { *; }

# ---- Kotlin 元数据 ----
-keep class kotlin.Metadata { *; }
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

# ---- 枚举 ----
# 版本类型 / 改动类型枚举被序列化使用，保留 values() 与 valueOf()
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---- 保留行号，方便崩溃定位（体积影响很小）----
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
