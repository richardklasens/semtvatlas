package nl.atlas.semtv

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

class AdminReceiver : DeviceAdminReceiver() {
    companion object {
        fun component(ctx: Context) = ComponentName(ctx.applicationContext, AdminReceiver::class.java)
        fun dpm(ctx: Context) = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        fun isDeviceOwner(ctx: Context) = dpm(ctx).isDeviceOwnerApp(ctx.packageName)
    }
}
