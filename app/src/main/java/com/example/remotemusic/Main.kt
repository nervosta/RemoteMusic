package com.example.remotemusic

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private var mode = 0 // 0 = wifi, 1 = bluetooth
    private val found = ArrayList<Found>()
    private val btList = ArrayList<BluetoothDevice>()
    private val labels = ArrayList<String>()
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var localNow: TextView
    private lateinit var remoteNow: TextView
    private lateinit var status: TextView
    private lateinit var modeWifi: Button
    private lateinit var modeBt: Button
    private var target: Found? = null
    private var targetDev: BluetoothDevice? = null
    private lateinit var controls: LinearLayout

    private val poll = object : Runnable {
        override fun run() {
            localNow.text = "На этом телефоне: " + MediaAccess.nowPlaying()
            ui.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MediaAccess.init(this)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply { text = "RemoteMusic"; textSize = 20f })
        status = TextView(this).apply { textSize = 13f; setPadding(0, pad / 2, 0, pad / 2) }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "Разрешить доступ к уведомлениям"
            setOnClickListener { startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        })
        localNow = TextView(this).apply { textSize = 15f; setPadding(0, pad / 2, 0, pad / 2) }
        root.addView(localNow)
        root.addView(Button(this).apply {
            text = "Запустить сервис"
            setOnClickListener { startService(Intent(this@MainActivity, ConnService::class.java)) }
        })

        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, pad, 0, 0) }
        modeWifi = Button(this).apply { text = "Wi-Fi"; setOnClickListener { setMode(0) } }
        modeBt = Button(this).apply { text = "Bluetooth"; setOnClickListener { setMode(1) } }
        modeRow.addView(modeWifi, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        modeRow.addView(modeBt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(modeRow)

        root.addView(Button(this).apply { text = "Обновить список"; setOnClickListener { refreshList() } })

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        val lv = ListView(this)
        lv.adapter = adapter
        lv.setOnItemClickListener { _, _, pos, _ -> selectTarget(pos) }
        root.addView(lv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (220 * resources.displayMetrics.density).toInt()))

        remoteNow = TextView(this).apply { textSize = 15f; setPadding(0, pad, 0, pad / 2) }
        root.addView(remoteNow)

        controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = LinearLayout.GONE }
        fun ctrlBtn(t: String, cmd: String) = Button(this).apply {
            text = t
            setOnClickListener { sendCmd(cmd) }
        }
        controls.addView(ctrlBtn("⏮", "PREV"))
        controls.addView(ctrlBtn("⏯", "PLAYPAUSE"))
        controls.addView(ctrlBtn("⏭", "NEXT"))
        controls.addView(ctrlBtn("🔉", "VOLDOWN"))
        controls.addView(ctrlBtn("🔊", "VOLUP"))
        root.addView(controls)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
            perms.add("android.permission.NEARBY_WIFI_DEVICES")
        }
        if (Build.VERSION.SDK_INT >= 31) perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (perms.isNotEmpty()) requestPermissions(perms.toTypedArray(), 1)

        setMode(0)
    }

    override fun onResume() {
        super.onResume()
        ui.post(poll)
        status.text = if (MediaAccess.isEnabled(this)) "Доступ к уведомлениям: включён"
        else "Доступ к уведомлениям: выключен — включи кнопкой выше"
        startService(Intent(this, ConnService::class.java))
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(poll)
        Net.stopDiscovery(this)
    }

    private fun setMode(m: Int) {
        mode = m
        modeWifi.alpha = if (m == 0) 1f else 0.5f
        modeBt.alpha = if (m == 1) 1f else 0.5f
        refreshList()
    }

    private fun refreshList() {
        found.clear(); btList.clear(); labels.clear()
        adapter.notifyDataSetChanged()
        target = null; targetDev = null
        controls.visibility = LinearLayout.GONE
        if (mode == 0) {
            Net.startDiscovery(this, { f ->
                if (found.none { it.host == f.host && it.port == f.port }) {
                    found.add(f); labels.add(f.name); adapter.notifyDataSetChanged()
                }
            }, { })
        } else {
            Net.stopDiscovery(this)
            for (d in Bt.bondedDevices()) {
                btList.add(d); labels.add(d.name ?: d.address)
            }
            adapter.notifyDataSetChanged()
        }
    }

    private fun selectTarget(pos: Int) {
        if (mode == 0) target = found.getOrNull(pos) ?: return
        else targetDev = btList.getOrNull(pos) ?: return
        controls.visibility = LinearLayout.VISIBLE
        sendCmd("PING")
    }

    private fun sendCmd(cmd: String) {
        if (mode == 0) {
            val t = target ?: return
            Net.send(t.host, t.port, cmd) { res -> showRemote(res) }
        } else {
            val d = targetDev ?: return
            Bt.send(d, cmd) { res -> showRemote(res) }
        }
    }

    private fun showRemote(res: String?) {
        remoteNow.text = if (res != null && res.startsWith("NOW:")) "На другом телефоне: " + res.removePrefix("NOW:")
        else "Не удалось связаться с устройством"
    }
}
