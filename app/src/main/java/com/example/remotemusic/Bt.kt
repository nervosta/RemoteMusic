package com.example.remotemusic

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.concurrent.Executors

object Bt {
    private val pool = Executors.newCachedThreadPool()
    private val ui = Handler(Looper.getMainLooper())

    fun bondedDevices(): List<BluetoothDevice> {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
        return try { adapter.bondedDevices.toList() } catch (e: SecurityException) { emptyList() }
    }

    fun send(device: BluetoothDevice, cmd: String, done: (String?) -> Unit) {
        pool.execute {
            val result = try {
                val sock = device.createInsecureRfcommSocketToServiceRecord(ConnService.BT_UUID)
                BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
                sock.connect()
                sock.use {
                    val w = PrintWriter(it.outputStream, true)
                    val r = BufferedReader(InputStreamReader(it.inputStream))
                    w.println("CMD:$cmd")
                    r.readLine()
                }
            } catch (e: Exception) { null }
            ui.post { done(result) }
        }
    }
}
