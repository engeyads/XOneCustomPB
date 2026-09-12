package com.tabletgamepadbridge

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * Receives the UHID gamepad Binder from PrivilegedMain (a shell-privileged
 * process started via `adb shell app_process`), the same mechanism Shizuku
 * itself uses to cross the SELinux boundary between "shell" and
 * "untrusted_app" domains - a plain Unix-domain-socket connect is blocked by
 * policy, but a Binder call routed through a ContentProvider (mediated by
 * system_server) is not.
 */
class UhidBinderProvider : ContentProvider() {

    companion object {
        const val METHOD_SEND_BINDER = "sendBinder"
        const val EXTRA_BINDER = "binder"

        @Volatile
        var receivedService: IUhidGamepadService? = null
            private set

        var onBinderReceived: (() -> Unit)? = null
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == METHOD_SEND_BINDER && extras != null) {
            val binder = extras.getBinder(EXTRA_BINDER)
            if (binder != null) {
                receivedService = IUhidGamepadService.Stub.asInterface(binder)
                onBinderReceived?.invoke()
            }
        }
        return Bundle()
    }

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
