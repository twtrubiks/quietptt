# OkHttp 自帶 R8 規則。JSch 用反射載入演算法的實作（com.jcraft.jsch.jce.*、DH、Random…），R8 看不到：整個留著
-keep class com.jcraft.jsch.** { *; }
# R8 壓過的程式只有 release 版跑得到：加了規則就在這裡寫為什麼，並讓 CI 的 release 冒煙測試真的走過那段程式
