# Room: keep entity classes — annotation-driven reflection at runtime.
-keep class com.storehop.app.data.entity.** { *; }

# Hilt — generated code keeps itself; nothing extra needed for v1.

# kotlinx.serialization — keep @Serializable classes.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-if @kotlinx.serialization.Serializable class ** { *; }
-keep class <1> { *; }

# Firestore data classes (de)serialized via reflection. Package gets created in M4.
-keep class com.storehop.app.sync.dto.** { *; }
