# OkHttp 自帶 R8 規則。JSch 用反射載入演算法的實作（com.jcraft.jsch.jce.*、DH、Random…），R8 看不到：整個留著
-keep class com.jcraft.jsch.** { *; }
# R8 壓過的程式只有 release 版跑得到：加了規則就在這裡寫為什麼，並讓 CI 的 release 冒煙測試真的走過那段程式

# JSch 選用的整合，QuietPTT 都用不到（伺服器用 none 驗證、不接 ssh-agent、不寫 log）：
# Windows Pageant（JNA）、log4j／slf4j 記錄器、Kerberos（GSS-API）、Unix socket 的 ssh-agent（junixsocket）。
# 上面整包 keep 會留住引用它們的類別，但執行時不會走到，告訴 R8 不用找
-dontwarn com.sun.jna.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**
-dontwarn org.ietf.jgss.**
-dontwarn org.newsclub.net.unix.**
