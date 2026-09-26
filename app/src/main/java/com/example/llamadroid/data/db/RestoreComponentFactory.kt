package com.example.llamadroid.data.db

import android.app.Activity
import android.app.AppComponentFactory
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.IBinder
import androidx.annotation.RequiresApi

/** Framework component admission for Android 9 and newer; database admission covers older APIs. */
@RequiresApi(28)
class RestoreComponentFactory : AppComponentFactory() {
    private fun blocked(): Boolean = RestoreAdmissionContext.isMaintenance()

    override fun instantiateService(cl: ClassLoader, className: String, intent: Intent?): Service =
        if (blocked()) InertService() else super.instantiateService(cl, className, intent)

    override fun instantiateReceiver(cl: ClassLoader, className: String, intent: Intent?): BroadcastReceiver =
        if (blocked() && !className.endsWith("RestoreReceiver")) InertReceiver()
        else super.instantiateReceiver(cl, className, intent)

    override fun instantiateProvider(cl: ClassLoader, className: String): ContentProvider =
        if (blocked()) InertProvider() else super.instantiateProvider(cl, className)

    override fun instantiateActivity(cl: ClassLoader, className: String, intent: Intent?): Activity =
        if (blocked() && className != "com.example.llamadroid.MainActivity") InertActivity()
        else super.instantiateActivity(cl, className, intent)
}

/** Installed from Application.attachBaseContext before component instantiation. */
internal object RestoreAdmissionContext {
    lateinit var application: Context
    fun isMaintenance(): Boolean = ::application.isInitialized &&
        RestoreCoordinator.isMaintenance(application)
}

class InertService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }
}

class InertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}

class InertActivity : Activity() {
    override fun onStart() {
        super.onStart()
        finish()
    }
}

class InertProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: android.content.ContentValues?, selection: String?,
        selectionArgs: Array<out String>?): Int = 0
}
