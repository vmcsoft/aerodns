package com.vmcsoft.aerodns.validation

import android.app.Service
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.AppComponentFactory
import com.vmcsoft.aerodns.data.vpn.DnsVpnService
import com.vmcsoft.aerodns.presentation.tile.DnsTileService
import java.lang.ref.WeakReference

/** Debug-only observation of real framework-created services; absent from release APKs. */
@RequiresApi(Build.VERSION_CODES.P)
class ValidationComponentFactory : AppComponentFactory() {
    override fun instantiateServiceCompat(cl: ClassLoader, className: String, intent: Intent?): Service =
        super.instantiateServiceCompat(cl, className, intent).also { service ->
            if (service is DnsVpnService) vpn = WeakReference(service)
            if (service is DnsTileService) tile = WeakReference(service)
        }

    companion object {
        @Volatile var vpn = WeakReference<DnsVpnService>(null)
            private set
        @Volatile var tile = WeakReference<DnsTileService>(null)
            private set
    }
}
