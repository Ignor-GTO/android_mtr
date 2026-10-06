# R8 включает обфускацию и сжатие. Точки входа Android и правила библиотек
# (Compose, Kotlin, coroutines) подключаются автоматически.
-keep class dev.netmtr.app.MainActivity { <init>(); }
-keep class dev.netmtr.app.BuildConfig { *; }
