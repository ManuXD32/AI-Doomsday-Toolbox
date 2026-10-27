package com.example.llamadroid.util

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress

/**
 * Helper for Network Service Discovery (NSD) to find and register llama.cpp RPC workers.
 */
class NsdHelper(
    context: Context,
    private val serviceType: String = SERVICE_TYPE,
    private val serviceNamePrefix: String = SERVICE_NAME_PREFIX
) {

    companion object {
        private const val TAG = "NsdHelper"
        const val SERVICE_TYPE = "_llama-rpc._tcp."
        const val SERVICE_NAME_PREFIX = "LlamaWorker"
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    
    // Registration listener
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var serviceName: String? = null
    
    // Discovery listener
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var isDiscovering = false
    
    // Discovered services
    private val _discoveredServices = MutableStateFlow<List<NsdServiceInfo>>(emptyList())
    val discoveredServices: StateFlow<List<NsdServiceInfo>> = _discoveredServices.asStateFlow()

    /**
     * Register this device as a worker service
     */
    fun registerService(port: Int, deviceName: String) {
        if (registrationListener != null) {
            unregisterService()
        }

        val serviceInfo = NsdServiceInfo().apply {
            // Service name must be unique. NSD will append a number if it conflicts.
            // We use a prefix + random ID or device name to minimize conflicts initially.
            this.serviceName = "$serviceNamePrefix-${deviceName.filter { it.isLetterOrDigit() }}"
            this.serviceType = this@NsdHelper.serviceType
            this.port = port
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                serviceName = NsdServiceInfo.serviceName
                DebugLog.log("[$TAG] Service registered: $serviceName")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                DebugLog.log("[$TAG] Registration failed: Error code $errorCode")
                registrationListener = null
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                DebugLog.log("[$TAG] Service unregistered")
                serviceName = null
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                DebugLog.log("[$TAG] Unregistration failed: Error code $errorCode")
            }
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            DebugLog.log("[$TAG] Exception registering service: ${e.message}")
        }
    }

    /**
     * Unregister the service
     */
    fun unregisterService() {
        if (registrationListener != null) {
            try {
                nsdManager.unregisterService(registrationListener)
            } catch (e: Exception) {
                DebugLog.log("[$TAG] Error unregistering: ${e.message}")
            }
            registrationListener = null
        }
    }

    /**
     * Start discovering services
     */
    fun startDiscovery() {
        if (isDiscovering) return

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                DebugLog.log("[$TAG] Service discovery started")
                isDiscovering = true
            }

            // Queue for sequential resolution
            private val resolveQueue = java.util.concurrent.ConcurrentLinkedQueue<NsdServiceInfo>()
            private var isResolving = false

            private fun processQueue() {
                if (isResolving) return
                val nextService = resolveQueue.poll() ?: return
                
                isResolving = true
                try {
                    nsdManager.resolveService(nextService, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            DebugLog.log("[$TAG] Resolve failed: $errorCode")
                            isResolving = false
                            processQueue()
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            DebugLog.log("[$TAG] Resolved: ${serviceInfo.serviceName} at ${serviceInfo.host}:${serviceInfo.port}")
                            
                            // Update list safely
                            val current = _discoveredServices.value.toMutableList()
                            // Remove if exists (update)
                            current.removeAll { it.serviceName == serviceInfo.serviceName }
                            current.add(serviceInfo)
                            _discoveredServices.value = current
                            
                            isResolving = false
                            processQueue()
                        }
                    })
                } catch (e: Exception) {
                    DebugLog.log("[$TAG] Resolve exception: ${e.message}")
                    isResolving = false
                    processQueue()
                }
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                DebugLog.log("[$TAG] Service found: ${service.serviceName} typeVal=${service.serviceType}")
                // Relaxed check: serviceType might differ slightly (e.g. leading dot)
                if (service.serviceType.contains(serviceType.trim('.').substringBefore('.').removePrefix("_")) &&
                    service.serviceType.contains("_tcp")) {
                    if (service.serviceName == serviceName) {
                        DebugLog.log("[$TAG] Found self, skipping")
                    } else {
                        // Always attempt resolution - onServiceResolved handles deduplication
                        resolveQueue.offer(service)
                        processQueue()
                    }
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                DebugLog.log("[$TAG] Service lost: ${service.serviceName}")
                val current = _discoveredServices.value.toMutableList()
                current.removeAll { it.serviceName == service.serviceName }
                _discoveredServices.value = current
            }

            override fun onDiscoveryStopped(serviceType: String) {
                DebugLog.log("[$TAG] Discovery stopped")
                isDiscovering = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                DebugLog.log("[$TAG] Discovery failed: Error code $errorCode")
                try {
                    nsdManager.stopServiceDiscovery(this)
                } catch (e: Exception) {
                    // Ignore
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                DebugLog.log("[$TAG] Stop discovery failed: Error code $errorCode")
                try {
                    nsdManager.stopServiceDiscovery(this)
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }

        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            DebugLog.log("[$TAG] Exception starting discovery: ${e.message}")
        }
    }

    /**
     * Stop discovering services
     */
    fun stopDiscovery() {
        if (discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (e: Exception) {
               // DebugLog.log("[$TAG] Error stopping discovery: ${e.message}") 
            }
            discoveryListener = null
            isDiscovering = false
        }
    }
}
