package com.example.llamadroid.tama.transfer

import android.content.Context
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.example.llamadroid.util.NsdHelper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class PetTransferPeer(val name: String, val host: String, val port: Int)

/** LAN advertisement/discovery of receiving devices; faked or omitted in JVM tests. */
interface PetTransferDiscovery {
    val peers: Flow<List<PetTransferPeer>>
    fun register(port: Int, deviceName: String)
    fun unregister()
    fun startDiscovery()
    fun stopDiscovery()
}

/** `_adtpet._tcp` over the shared [NsdHelper]. */
class NsdPetTransferDiscovery(context: Context) : PetTransferDiscovery {
    private val helper = NsdHelper(context.applicationContext, SERVICE_TYPE, SERVICE_PREFIX)

    override val peers: Flow<List<PetTransferPeer>> = helper.discoveredServices.map { services ->
        services.mapNotNull { info ->
            val host = hostOf(info) ?: return@mapNotNull null
            PetTransferPeer(info.serviceName.removePrefix("$SERVICE_PREFIX-"), host, info.port)
        }
    }

    override fun register(port: Int, deviceName: String) = helper.registerService(port, deviceName)
    override fun unregister() = helper.unregisterService()
    override fun startDiscovery() = helper.startDiscovery()
    override fun stopDiscovery() = helper.stopDiscovery()

    @Suppress("DEPRECATION")
    private fun hostOf(info: NsdServiceInfo): String? =
        if (Build.VERSION.SDK_INT >= 34) info.hostAddresses.firstOrNull()?.hostAddress
        else info.host?.hostAddress

    companion object {
        const val SERVICE_TYPE = "_adtpet._tcp."
        const val SERVICE_PREFIX = "AdtPet"
    }
}
