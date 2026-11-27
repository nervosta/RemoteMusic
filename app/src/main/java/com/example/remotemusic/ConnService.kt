package com.example.remotemusic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors

class ConnService : Service() {
    companion object {
        const val SERVICE_TYPE = "_remotemusic._tcp."
        val BT_UUID: UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
        @Volatile var running = false
    }

    private lateinit var nsd: NsdManager
    private var regListener: NsdManager.RegistrationListener? = null
    private val pool = Executors.newCachedThreadPool()
    private var wifiServer: ServerSocket? = null
    private var btServer: BluetoothServerSocket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        MediaAccess.init(this)
        startForegroundNotice()
        running = true
        pool.execute { runWifiServer() }
        pool.execute { runBtServer() }
    }

    private fun startForegroundNotice() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("rm", "RemoteMusic", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "rm")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Удалённая музыка активна")
            .build()
        ServiceCompat.startForeground(
            this, 1, n,
            if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        )
    }

    private fun handleLine(line: String): String {
        val cmd = line.trim().removePrefix("CMD:")
        when (cmd) {
            "PLAY" -> MediaAccess.play()
            "PAUSE" -> MediaAccess.pause()
            "PLAYPAUSE" -> MediaAccess.playPause()
            "NEXT" -> MediaAccess.next()
            "PREV" -> MediaAccess.prev()
            "VOLUP" -> MediaAccess.volUp()
            "VOLDOWN" -> MediaAccess.volDown()
        }
        Thread.sleep(150)
        return "NOW:" + MediaAccess.nowPlaying()
    }

    private fun runWifiServer() {
        try {
            val server = ServerSocket(0)
            wifiServer = server
            registerNsd(server.localPort)
            while (running) {
                val sock = server.accept()
                pool.execute { serveSocket(sock) }
            }
        } catch (e: Exception) {}
    }

    private fun serveSocket(sock: Socket) {
        try {
            sock.use {
                val reader = BufferedReader(InputStreamReader(it.getInputStream()))
                val writer = PrintWriter(it.getOutputStream(), true)
                val line = reader.readLine() ?: return
                writer.println(handleLine(line))
            }
        } catch (e: Exception) {}
    }

    private fun registerNsd(port: Int) {
        try {
            nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
            val info = NsdServiceInfo().apply {
                serviceName = Build.MODEL + "-" + (System.currentTimeMillis() % 1000)
                serviceType = SERVICE_TYPE
                setPort(port)
            }
            regListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) {}
                override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
            }
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, regListener)
        } catch (e: Exception) {}
    }

    private fun runBtServer() {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
            if (!adapter.isEnabled) return
            val server = adapter.listenUsingInsecureRfcommWithServiceRecord("RemoteMusic", BT_UUID)
            btServer = server
            while (running) {
                val sock = server.accept()
                pool.execute { serveBtSocket(sock) }
            }
        } catch (e: Exception) {}
    }

    private fun serveBtSocket(sock: BluetoothSocket) {
        try {
            sock.use {
                val reader = BufferedReader(InputStreamReader(it.inputStream))
                val writer = PrintWriter(it.outputStream, true)
                val line = reader.readLine() ?: return
                writer.println(handleLine(line))
            }
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        running = false
        try { wifiServer?.close() } catch (e: Exception) {}
        try { btServer?.close() } catch (e: Exception) {}
        try { regListener?.let { nsd.unregisterService(it) } } catch (e: Exception) {}
        pool.shutdownNow()
        super.onDestroy()
    }
}
