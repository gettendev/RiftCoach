plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

// Os arquivos de compilação ficam fora do OneDrive para não sincronizar milhares de arquivos temporários.
val outRoot = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"), "RiftCoachBuild")
allprojects {
    layout.buildDirectory.set(File(outRoot, name))
}
