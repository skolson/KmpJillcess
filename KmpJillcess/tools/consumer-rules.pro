-keepclassmembers @kotlinx.serialization.Serializable class com.oldguy.jillcess.configuration.** {
    # lookup for plugin generated serializable classes
    *** Companion;
    # lookup for serializable objects
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
# lookup for plugin generated serializable classes
-if @kotlinx.serialization.Serializable class com.oldguy.jillcess.configuration.**
-keepclassmembers class com.oldguy.jillcess.configuration.<1>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
