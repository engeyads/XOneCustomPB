package com.tabletgamepadbridge

import android.os.Bundle
import android.os.IBinder

/**
 * Entry point started directly via `adb shell app_process` (shell UID), no
 * Shizuku or any other separate app involved. It creates the real
 * UhidGamepadService (a Binder implementing IUhidGamepadService that owns
 * the actual /dev/uhid file descriptor) and hands that Binder over to our
 * normal app process via the exact same mechanism Shizuku's own server uses:
 * a direct (reflection-based) call to ActivityManager's hidden
 * getContentProviderExternal(), followed by a Binder call into our app's own
 * ContentProvider (UhidBinderProvider). This works despite SELinux blocking
 * plain Unix-domain-socket connects between "shell" and "untrusted_app"
 * domains, because ContentProvider Binder calls are mediated through
 * system_server, which already has broad, pre-approved cross-domain Binder
 * call rules (the same general mechanism any app's ContentProvider relies on
 * to be reachable at all).
 */
object PrivilegedMain {

    private const val PACKAGE_NAME = "com.tabletgamepadbridge"
    private const val AUTHORITY = "$PACKAGE_NAME.uhid"
    private const val METHOD_SEND_BINDER = "sendBinder"
    private const val EXTRA_BINDER = "binder"

    @JvmStatic
    fun main(args: Array<String>) {
        val service = UhidGamepadService()
        val created = service.createGamepad()
        println("createGamepad() = $created")

        // Keep re-sending the Binder periodically, forever - not just once.
        // This makes the app self-healing: any time it restarts (reinstall,
        // force-stop, crash), it loses its in-memory reference to this
        // service, but within a few seconds it will automatically get a
        // fresh one without ever needing the PC/adb again. Only a reboot of
        // this helper process itself (e.g. a full tablet reboot) needs a
        // manual restart via adb.
        while (true) {
            try {
                sendBinderToApp(service.asBinder())
                println("Binder handed off to app successfully")
            } catch (e: Throwable) {
                println("Handoff attempt failed: $e")
            }
            Thread.sleep(5000)
        }
    }

    private fun sendBinderToApp(binder: IBinder) {
        // 1. IActivityManager am = IActivityManager.Stub.asInterface(ServiceManager.getService("activity"));
        val serviceManagerClass = Class.forName("android.os.ServiceManager")
        val amBinder = serviceManagerClass.getMethod("getService", String::class.java)
            .invoke(null, "activity") as? IBinder
            ?: throw IllegalStateException("activity service binder is null")

        val iamStubClass = Class.forName("android.app.IActivityManager\$Stub")
        val am = iamStubClass.getMethod("asInterface", IBinder::class.java).invoke(null, amBinder)
            ?: throw IllegalStateException("asInterface returned null")

        // 2. ContentProviderHolder holder = am.getContentProviderExternal(AUTHORITY, 0, null, AUTHORITY);
        val getCpExternal = am.javaClass.getMethod(
            "getContentProviderExternal",
            String::class.java, Int::class.javaPrimitiveType, IBinder::class.java, String::class.java
        )
        val holder = getCpExternal.invoke(am, AUTHORITY, 0, null, AUTHORITY)
            ?: throw IllegalStateException("getContentProviderExternal returned null (is the app running?)")

        try {
            // 3. IContentProvider provider = holder.provider;
            val providerField = holder.javaClass.getField("provider")
            val provider = providerField.get(holder)
                ?: throw IllegalStateException("holder.provider is null")

            // 4. provider.call(attributionSource, AUTHORITY, "sendBinder", null, extras);
            //    Must use the real 5-arg method with the correct authority - the
            //    deprecated 4-arg default hardcodes authority="unknown" internally,
            //    which Android 13+ rejects with a SecurityException.
            val extras = Bundle()
            extras.putBinder(EXTRA_BINDER, binder)

            val attributionSourceClass = Class.forName("android.content.AttributionSource")
            val myUid = Class.forName("android.os.Process").getMethod("myUid").invoke(null) as Int
            val attributionSource = attributionSourceClass.getConstructor(
                Int::class.javaPrimitiveType, String::class.java, String::class.java
            ).newInstance(myUid, null, null)

            val callMethod = provider.javaClass.getMethod(
                "call", attributionSourceClass, String::class.java, String::class.java,
                String::class.java, Bundle::class.java
            )
            callMethod.invoke(provider, attributionSource, AUTHORITY, METHOD_SEND_BINDER, null, extras)
        } finally {
            try {
                val removeExternal = am.javaClass.getMethod(
                    "removeContentProviderExternal", String::class.java, IBinder::class.java
                )
                removeExternal.invoke(am, AUTHORITY, null)
            } catch (e: Exception) {
                // not critical
            }
        }
    }
}
