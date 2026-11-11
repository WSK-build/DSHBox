
package com.dshbox.app.ui.theme

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.view.Display
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * App 模块 JVM 测试的最小 Context 桩：只提供 SharedPreferences（由测试注入
 * 内存实现），其余 Context API 一律显式拒绝——设置状态的装配不触达那些路径。
 * 与 remote-connection 模块的 StubContext 同构（复用其逐项拒绝的口径）。
 */
class StubContext : Context() {

    var prefsProvider: ((String, Int) -> SharedPreferences)? = null

    /** 测试不触达：仅编译存在。 */
    override fun getApplicationContext(): Context = unused()

    /** 测试不触达：仅编译存在。 */
    override fun getFilesDir(): File = unused()

    private fun unused(): Nothing = throw AssertionError("Context stub path is not exercised in JVM tests")

    /** SharedPreferences 桩：由外部注入（测试用例装配内存 prefs）。 */
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        prefsProvider?.invoke(name, mode) ?: unused()

    // ---- 下列成员全部为 JVM 测试未触达路径的显式拒绝 ----

    override fun moveSharedPreferencesFrom(sourceContext: Context, name: String): Boolean = unused()
    override fun deleteSharedPreferences(name: String): Boolean = unused()
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase = unused()
    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?,
        errorHandler: DatabaseErrorHandler?,
    ): SQLiteDatabase = unused()
    override fun moveDatabaseFrom(sourceContext: Context, name: String): Boolean = unused()
    override fun deleteDatabase(name: String): Boolean = unused()
    override fun getDatabasePath(name: String): File = unused()
    override fun databaseList(): Array<String> = unused()
    override fun getDir(name: String, mode: Int): File = unused()
    override fun openFileInput(name: String): FileInputStream = unused()
    override fun openFileOutput(name: String, mode: Int): FileOutputStream = unused()
    override fun deleteFile(name: String): Boolean = unused()
    override fun getFileStreamPath(name: String): File = unused()
    override fun fileList(): Array<String> = unused()
    override fun getDataDir(): File = unused()
    override fun getCacheDir(): File = unused()
    override fun getCodeCacheDir(): File = unused()
    override fun getExternalCacheDir(): File? = unused()
    override fun getExternalCacheDirs(): Array<File> = unused()
    override fun getExternalFilesDir(type: String?): File? = unused()
    override fun getExternalFilesDirs(type: String?): Array<File> = unused()
    override fun getExternalMediaDirs(): Array<File> = unused()
    override fun getObbDir(): File = unused()
    override fun getObbDirs(): Array<File> = unused()
    override fun getNoBackupFilesDir(): File = unused()
    override fun getAssets(): AssetManager = unused()
    override fun getResources(): Resources = unused()
    override fun getTheme(): Resources.Theme = unused()
    override fun setTheme(resId: Int) = unused()
    override fun getPackageManager(): PackageManager = unused()
    override fun getContentResolver(): ContentResolver = unused()
    override fun getMainLooper(): Looper = unused()
    override fun getPackageName(): String = unused()
    override fun getApplicationInfo(): ApplicationInfo = unused()
    override fun getPackageResourcePath(): String = unused()
    override fun getPackageCodePath(): String = unused()
    override fun getClassLoader(): ClassLoader = unused()
    override fun getSystemService(name: String): Any = unused()
    override fun getSystemServiceName(serviceClass: Class<*>): String? = unused()
    override fun getWallpaper(): Drawable = unused()
    override fun peekWallpaper(): Drawable = unused()
    override fun getWallpaperDesiredMinimumWidth(): Int = unused()
    override fun getWallpaperDesiredMinimumHeight(): Int = unused()
    override fun setWallpaper(bitmap: Bitmap) = unused()
    override fun setWallpaper(input: InputStream) = unused()
    override fun clearWallpaper() = unused()
    override fun startActivity(intent: Intent) = unused()
    override fun startActivity(intent: Intent, options: Bundle?) = unused()
    override fun startActivities(intents: Array<Intent>) = unused()
    override fun startActivities(intents: Array<Intent>, options: Bundle?) = unused()
    override fun startIntentSender(intent: IntentSender, fillInIntent: Intent?, flagsMask: Int, flagsValues: Int, extraFlags: Int) = unused()
    override fun startIntentSender(
        intent: IntentSender,
        fillInIntent: Intent?,
        flagsMask: Int,
        flagsValues: Int,
        extraFlags: Int,
        options: Bundle?,
    ) = unused()
    override fun sendBroadcast(intent: Intent) = unused()
    override fun sendBroadcast(intent: Intent, receiverPermission: String?) = unused()
    override fun sendOrderedBroadcast(intent: Intent, receiverPermission: String?) = unused()
    override fun sendOrderedBroadcast(
        intent: Intent,
        receiverPermission: String?,
        resultReceiver: BroadcastReceiver?,
        scheduler: Handler?,
        initialCode: Int,
        initialData: String?,
        initialExtras: Bundle?,
    ) = unused()
    override fun sendBroadcastAsUser(intent: Intent, user: UserHandle) = unused()
    override fun sendBroadcastAsUser(intent: Intent, user: UserHandle, receiverPermission: String?) = unused()
    override fun sendStickyBroadcast(intent: Intent) = unused()
    override fun sendStickyOrderedBroadcast(
        intent: Intent,
        resultReceiver: BroadcastReceiver,
        scheduler: Handler?,
        initialCode: Int,
        initialData: String?,
        initialExtras: Bundle?,
    ) = unused()
    override fun removeStickyBroadcast(intent: Intent) = unused()
    override fun sendStickyBroadcastAsUser(intent: Intent, user: UserHandle) = unused()
    override fun sendStickyOrderedBroadcastAsUser(
        intent: Intent,
        user: UserHandle?,
        resultReceiver: BroadcastReceiver?,
        scheduler: Handler?,
        initialCode: Int,
        initialData: String?,
        initialExtras: Bundle?,
    ) = unused()
    override fun removeStickyBroadcastAsUser(intent: Intent, user: UserHandle) = unused()

    // 这两个 AsUser 广播重载的参数与 android.content.Context 的抽象签名逐位一致
    override fun sendOrderedBroadcastAsUser(
        intent: Intent,
        user: UserHandle?,
        receiverPermission: String?,
        resultReceiver: BroadcastReceiver?,
        scheduler: Handler?,
        initialCode: Int,
        initialData: String?,
        initialExtras: Bundle?,
    ) = unused()

    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? = unused()
    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter, flags: Int): Intent? = unused()
    override fun registerReceiver(
        receiver: BroadcastReceiver,
        filter: IntentFilter,
        broadcastPermission: String?,
        scheduler: Handler?,
    ): Intent? = unused()
    override fun registerReceiver(
        receiver: BroadcastReceiver,
        filter: IntentFilter,
        broadcastPermission: String?,
        scheduler: Handler?,
        flags: Int,
    ): Intent? = unused()
    override fun unregisterReceiver(receiver: BroadcastReceiver) = unused()
    override fun startService(service: Intent): ComponentName? = unused()
    override fun startForegroundService(service: Intent): ComponentName? = unused()
    override fun stopService(name: Intent): Boolean = unused()
    override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean = unused()
    override fun unbindService(conn: ServiceConnection) = unused()
    override fun startInstrumentation(className: ComponentName, profileFile: String?, arguments: Bundle?): Boolean = unused()
    override fun checkPermission(permission: String, pid: Int, uid: Int): Int = unused()
    override fun checkCallingPermission(permission: String): Int = unused()
    override fun checkCallingOrSelfPermission(permission: String): Int = unused()
    override fun checkSelfPermission(permission: String): Int = unused()
    override fun enforcePermission(permission: String, pid: Int, uid: Int, message: String?) = unused()
    override fun enforceCallingPermission(permission: String, message: String?) = unused()
    override fun enforceCallingOrSelfPermission(permission: String, message: String?) = unused()
    override fun grantUriPermission(toPackage: String, uri: Uri, modeFlags: Int) = unused()
    override fun revokeUriPermission(uri: Uri, modeFlags: Int) = unused()
    override fun revokeUriPermission(targetPackage: String, uri: Uri, modeFlags: Int) = unused()
    override fun checkUriPermission(uri: Uri, pid: Int, uid: Int, modeFlags: Int): Int = unused()
    override fun checkUriPermission(uri: Uri?, readPermission: String?, writePermission: String?, pid: Int, uid: Int, modeFlags: Int): Int = unused()
    override fun enforceUriPermission(uri: Uri, pid: Int, uid: Int, modeFlags: Int, message: String?) = unused()
    override fun enforceUriPermission(
        uri: Uri?,
        readPermission: String?,
        writePermission: String?,
        pid: Int,
        uid: Int,
        modeFlags: Int,
        message: String?,
    ) = unused()
    override fun checkCallingUriPermission(uri: Uri, modeFlags: Int): Int = unused()
    override fun checkCallingOrSelfUriPermission(uri: Uri, modeFlags: Int): Int = unused()
    override fun enforceCallingUriPermission(uri: Uri, modeFlags: Int, message: String?) = unused()
    override fun enforceCallingOrSelfUriPermission(uri: Uri, modeFlags: Int, message: String?) = unused()
    override fun createPackageContext(packageName: String, flags: Int): Context = unused()
    override fun createContextForSplit(splitName: String): Context = unused()
    override fun createConfigurationContext(overrides: Configuration): Context = unused()
    override fun createDisplayContext(display: Display): Context = unused()
    override fun createDeviceProtectedStorageContext(): Context = unused()
    override fun isDeviceProtectedStorage(): Boolean = unused()
}
