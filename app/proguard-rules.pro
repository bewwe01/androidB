# Not active yet (minify is off). Starting point for when R8 is enabled:
-keep class org.postgresql.** { *; }
-dontwarn org.postgresql.**
-dontwarn javax.naming.**
-dontwarn java.lang.management.**
-dontwarn org.osgi.**
-dontwarn com.sun.jna.**
-dontwarn waffle.**
