package com.example.remotemusic

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

data class Found(val name: String, val host: String, val port: Int)

object Net {
    private val pool = Executors.newCachedThreadPool()
    private val ui = Handler(Looper.getMainLooper())
    private var discovery: NsdManager.DiscoveryListener? = null
    private var nsd: NsdManager? = null

    fun startDiscovery(ctx: Context, onFound: (Found) -> Unit, onLost: (String) -> Unit) {
        stopDiscovery(ctx)
        val mgr = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsd = mgr
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                mgr.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, e: Int) {}
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        val host = i.host?.hostAddress ?: return
                        ui.post { onFound(Found(i.serviceName, host, i.port)) }
                    }
                })
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                ui.post { onLost(info.serviceName) }
            }
        }
        discovery = listener
        try { mgr.discoverServices(ConnService.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) } catch (e: Exception) {}
    }

    fun stopDiscovery(ctx: Context) {
        try { discovery?.let { nsd?.stopServiceDiscovery(it) } } catch (e: Exception) {}
        discovery = null
    }

    fun send(host: String, port: Int, cmd: String, done: (String?) -> Unit) {
        pool.execute {
            val result = try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, port), 3000)
                    val w = PrintWriter(s.getOutputStream(), true)
                    val r = BufferedReader(InputStreamReader(s.getInputStream()))
                    w.println("CMD:$cmd")
                    r.readLine()
                }
            } catch (e: Exception) { null }
            ui.post { done(result) }
        }
    }
}
