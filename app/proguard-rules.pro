# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Kotlin data classes for serialization
-keep class com.example.llamadroid.data.** { *; }
-keep class com.example.llamadroid.service.**.* { *; }

# Keep Room entities
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# Keep JSch for SSH
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jzlib.**
-dontwarn org.ietf.jgss.**
-dontwarn com.jcraft.jsch.jgss.**
-dontwarn com.jcraft.jsch.jcraft.Compression
-dontwarn com.gemalto.jp2.**

# Keep OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# Keep enums for TypeConverters
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Compose classes
-keep class androidx.compose.** { *; }

# Keep model classes that might be serialized
-keep class com.example.llamadroid.**.model.** { *; }

# Preserve line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Gson - preserve Signature and annotations for reflection
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# Keep Gson-serialized classes with their generic signatures
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Explicitly keep HuggingFace API DTOs (Gson needs these for generic List<T>)
-keep class com.example.llamadroid.data.api.HfModelDto { *; }
-keep class com.example.llamadroid.data.api.HfRepoInfoDto { *; }
-keep class com.example.llamadroid.data.api.HfSiblingDto { *; }
-keep class com.example.llamadroid.data.api.HfTreeItemDto { *; }

# Keep all API data classes
-keep class com.example.llamadroid.data.api.** { *; }

# Keep ONNX Runtime Java bindings
-keep class ai.onnxruntime.** { *; }

# Keep PDFBox Android extraction internals used by document imports.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-keep class com.tom_roush.harmony.** { *; }
-dontwarn com.tom_roush.**

# LiteRT-LM is loaded through a reflection bridge because the current app Kotlin
# toolchain cannot compile directly against its newer metadata yet.
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
-dontwarn com.google.ai.edge.litert.**

# Parquet/Hadoop local dataset imports reference optional desktop/server JVM APIs.
-dontwarn com.fasterxml.jackson.core.JsonFactory
-dontwarn com.fasterxml.jackson.databind.ObjectMapper
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.ThreadMXBean
-dontwarn javax.management.DynamicMBean
-dontwarn javax.management.InstanceAlreadyExistsException
-dontwarn javax.management.MBeanServer
-dontwarn javax.management.ObjectInstance
-dontwarn javax.management.ObjectName
-dontwarn javax.naming.NamingException
-dontwarn javax.naming.directory.DirContext
-dontwarn javax.naming.directory.InitialDirContext
-dontwarn javax.security.auth.callback.NameCallback
-dontwarn javax.security.auth.kerberos.KerberosPrincipal
-dontwarn javax.security.auth.kerberos.KerberosTicket
-dontwarn javax.security.auth.kerberos.KeyTab
-dontwarn javax.security.auth.login.AppConfigurationEntry$LoginModuleControlFlag
-dontwarn javax.security.auth.login.AppConfigurationEntry
-dontwarn javax.security.auth.login.Configuration$Parameters
-dontwarn javax.security.auth.login.Configuration
-dontwarn javax.security.auth.login.LoginContext
-dontwarn javax.security.auth.spi.LoginModule
-dontwarn javax.security.sasl.RealmCallback
-dontwarn javax.security.sasl.RealmChoiceCallback
-dontwarn javax.security.sasl.Sasl
-dontwarn javax.security.sasl.SaslClient
-dontwarn javax.security.sasl.SaslClientFactory
-dontwarn javax.security.sasl.SaslException
-dontwarn javax.security.sasl.SaslServer
-dontwarn javax.security.sasl.SaslServerFactory
-dontwarn javax.xml.stream.Location
-dontwarn javax.xml.stream.XMLEventFactory
-dontwarn javax.xml.stream.XMLEventReader
-dontwarn javax.xml.stream.XMLInputFactory
-dontwarn javax.xml.stream.XMLOutputFactory
-dontwarn javax.xml.stream.XMLReporter
-dontwarn javax.xml.stream.XMLResolver
-dontwarn javax.xml.stream.XMLStreamConstants
-dontwarn javax.xml.stream.XMLStreamException
-dontwarn javax.xml.stream.XMLStreamReader
-dontwarn javax.xml.stream.XMLStreamWriter
-dontwarn javax.xml.stream.events.DTD
-dontwarn javax.xml.stream.events.EntityDeclaration
-dontwarn javax.xml.stream.events.NotationDeclaration
-dontwarn javax.xml.stream.events.XMLEvent
-dontwarn javax.xml.stream.util.XMLEventAllocator
-dontwarn lombok.Generated
-dontwarn net.jpountz.lz4.LZ4Factory
-dontwarn net.jpountz.lz4.LZ4SafeDecompressor
-dontwarn org.apache.hadoop.shaded.com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn org.apache.hadoop.shaded.com.google.errorprone.annotations.CheckReturnValue
-dontwarn org.apache.hadoop.shaded.com.google.errorprone.annotations.Immutable
-dontwarn org.apache.hadoop.shaded.com.google.protobuf.GeneratedMessage$ExtendableMessageOrBuilder
-dontwarn org.apache.hadoop.shaded.com.sun.jersey.server.spi.component.ResourceComponentProviderFactory
-dontwarn org.apache.hadoop.shaded.com.sun.jersey.server.spi.component.ResourceComponentProviderFactoryClass
-dontwarn org.apache.hadoop.shaded.com.sun.jna.Library
-dontwarn org.apache.hadoop.shaded.com.sun.jna.Memory
-dontwarn org.apache.hadoop.shaded.com.sun.jna.Native
-dontwarn org.apache.hadoop.shaded.com.sun.jna.Pointer
-dontwarn org.apache.hadoop.shaded.com.sun.jna.Structure$ByReference
-dontwarn org.apache.hadoop.shaded.com.sun.jna.Structure$FieldOrder
-dontwarn org.apache.hadoop.shaded.com.sun.jna.Structure
-dontwarn org.apache.hadoop.shaded.com.sun.jna.WString
-dontwarn org.apache.hadoop.shaded.com.sun.jna.platform.win32.Win32Exception
-dontwarn org.apache.hadoop.shaded.com.sun.jna.ptr.IntByReference
-dontwarn org.apache.hadoop.shaded.com.sun.jna.win32.StdCallLibrary
-dontwarn org.apache.hadoop.shaded.com.sun.jna.win32.W32APIOptions
-dontwarn org.apache.hadoop.shaded.com.sun.msv.reader.GrammarReaderController
-dontwarn org.apache.hadoop.shaded.com.sun.msv.reader.util.IgnoreController
-dontwarn org.apache.hadoop.shaded.org.apache.curator.shaded.com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn org.apache.hadoop.shaded.org.apache.curator.shaded.com.google.errorprone.annotations.CheckReturnValue
-dontwarn org.apache.hadoop.shaded.org.apache.curator.shaded.com.google.errorprone.annotations.Immutable
-dontwarn org.apache.hadoop.shaded.org.fusesource.jansi.Ansi
-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn sun.net.spi.nameservice.NameServiceDescriptor

# ========== RETROFIT - CRITICAL RULES FOR GENERIC TYPES ==========
# Keep Retrofit service interfaces with their method signatures
-keep,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Keep all Retrofit service interfaces in the api package
-keep interface com.example.llamadroid.data.api.HuggingFaceService { *; }

# Keep method signature information for all Retrofit interfaces
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.GET <methods>;
    @retrofit2.http.POST <methods>;
    @retrofit2.http.PUT <methods>;
    @retrofit2.http.DELETE <methods>;
    @retrofit2.http.Path <methods>;
    @retrofit2.http.Query <methods>;
}

# Keep kotlinx.serialization classes
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}

# Keep the generated serializers
-keep class **$$serializer { *; }
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
