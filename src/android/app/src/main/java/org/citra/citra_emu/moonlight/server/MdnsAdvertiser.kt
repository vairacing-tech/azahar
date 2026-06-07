package org.citra.citra_emu.moonlight.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

class MdnsAdvertiser(
    context: Context,
    private val serviceName: String,
    private val port: Int,
    private val onLog: (String) -> Unit,
) {
    private val nsdManager = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    fun start() {
        if (listener != null) return
        val info = NsdServiceInfo().apply {
            serviceName = this@MdnsAdvertiser.serviceName
            serviceType = SERVICE_TYPE
            port = this@MdnsAdvertiser.port
        }
        val registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                onLog("mDNS advertised ${serviceInfo.serviceName} on $SERVICE_TYPE:$port")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                listener = null
                onLog("mDNS registration failed: $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                onLog("mDNS advertisement stopped")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                onLog("mDNS unregistration failed: $errorCode")
            }
        }
        listener = registrationListener
        runCatching {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        }.onFailure {
            listener = null
            onLog("mDNS registration failed: ${it.message}")
        }
    }

    fun stop() {
        val activeListener = listener ?: return
        listener = null
        runCatching { nsdManager.unregisterService(activeListener) }
    }

    companion object {
        private const val SERVICE_TYPE = "_nvstream._tcp."
    }
}
