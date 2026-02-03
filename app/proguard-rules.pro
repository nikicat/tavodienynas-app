# Keep WebView JavaScript interface
-keepclassmembers class lt.manodienynas.app.MainActivity$WebAppInterface {
    public *;
}

# Keep enum classes
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
