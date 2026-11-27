package com.dotmusic.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Updates come from the app's own GitHub release, so a new build installs over this one.
 * Android always shows its own install dialog: nothing can replace an installed app silently
 * unless it is a system app.
 */
object Updates {
    private const val REPO = "kavinagarwalagarwal-coder/dot-music"
    private const val TAG = "latest"
    private const val INFO = "https://github.com/$REPO/releases/download/$TAG/update.json"
    private const val APK = "https://github.com/$REPO/releases/download/$TAG/dot-music.apk"
    const val ACTION_INSTALLED = "com.dotmusic.app.INSTALL_RESULT"

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dot-update").apply { priority = Thread.MIN_PRIORITY }
    }

    data class Release(val versionCode: Int, val versionName: String)

    /** Run blocking work off the main thread. */
    fun background(task: () -> Unit) {
        io.execute { task() }
    }

    /** The version installed right now. */
    fun installedCode(c: Context): Int = runCatching {
        val info = c.packageManager.getPackageInfo(c.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt()
        else @Suppress("DEPRECATION") info.versionCode
    }.getOrDefault(0)

    fun installedName(c: Context): String = runCatching {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName
    }.getOrNull().orEmpty().ifEmpty { "1.0" }

    /** The build published as the `latest` release, or null when offline. */
    fun latest(): Release? = runCatching {
        val json = JSONObject(Net.get(INFO))
        Release(json.getInt("versionCode"), json.getString("versionName"))
    }.getOrNull()

    /** Downloaded into the cache; the installer reads it from there. */
    fun download(c: Context): File {
        val apk = File(c.cacheDir, "dot-music.apk")
        Net.download(APK, apk)
        return apk
    }

    /** Android only lets apps install packages if you allowed Dot Music to. */
    fun canInstall(c: Context) = c.packageManager.canRequestPackageInstalls()

    /** Hand the APK to the system installer, which asks for confirmation. */
    fun install(c: Context, apk: File) {
        val installer = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(c.packageName)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("dot-music.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val result = PendingIntent.getBroadcast(
                c, id,
                Intent(c, InstallResultReceiver::class.java).setAction(ACTION_INSTALLED),
                flags
            )
            session.commit(result.intentSender)
        }
    }
}

/** System installer's answer, so a failed update says why instead of vanishing. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_SUCCESS) return
        val reason = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        Toast.makeText(
            context,
            context.getString(R.string.update_install_failed, reason.ifEmpty { "status $status" }),
            Toast.LENGTH_LONG
        ).show()
    }
}
