# Rules for the release build, which shrinks and renames code with R8.
#
# R8 removes fields that are written but never read by code it can see. Classes that are
# turned into JSON by reflection look exactly like that, so without the rules below the
# release build would send empty requests and read empty answers.

# Generic type information and annotations are read at run time by Gson and Retrofit
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault

# --- Claude (Anthropic) request and response bodies, converted by Gson ---
-keep class com.purrfectbytes.android.services.AnthropicRequest { *; }
-keep class com.purrfectbytes.android.services.AnthropicMessage { *; }
-keep class com.purrfectbytes.android.services.AnthropicResponse { *; }
-keep class com.purrfectbytes.android.services.AnthropicContent { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# --- Retrofit 2.9 with suspend functions under R8 full mode ---
# (these rules ship inside Retrofit from 2.10 on)
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>

# --- YouTube Data API: google-api-client fills fields marked @Key by reflection ---
-keepclassmembers class * {
    @com.google.api.client.util.Key <fields>;
}
-keep class com.google.api.services.youtube.model.** { *; }
-keep class com.google.api.client.googleapis.json.** { *; }
-keep class com.google.api.client.json.GenericJson { *; }
-keep class com.google.api.client.util.GenericData { *; }
