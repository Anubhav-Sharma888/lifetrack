# Kotlin Serialization generates serializers referenced by the app. AndroidX,
# Hilt, Room, WorkManager and Glance ship their own consumer rules.
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations
