package com.karinlab.mymonitor

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbConstants
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import android.graphics.Color

class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("monitor", MODE_PRIVATE) }
    private lateinit var monitor: MonitorFragment
    private lateinit var video: FrameLayout
    private var cameraMounted = false
    private var receiverRegistered = false
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED || intent?.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                video.postDelayed({ scanUsbDevices(); monitor.refreshUsb() }, 400)
            }
        }
    }
    private lateinit var state: TextView
    private lateinit var record: Button
    private var controlsVisible = true
    private lateinit var controls: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = FrameLayout(this).apply { id = View.generateViewId() }
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        state = TextView(this).apply { setTextColor(Color.WHITE); setBackgroundColor(0x88000000.toInt()); text = "USBカメラを接続してください"; setPadding(16, 8, 16, 8); textSize = 14f }
        root.addView(state, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))

        controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(8, 10, 8, 10)
            setBackgroundColor(0x99000000.toInt())
        }
        val photo = button("📷 撮影") { monitor.takePhoto() }
        record = button("● 録画") { monitor.toggleRecording() }
        val folder = button("📁 保存先") { chooseFolder() }
        val smb = button("NAS / SMB") { smbSettings() }
        val scan = button("USB確認") { scanUsbDevices(); monitor.refreshUsb() }
        controls.addView(photo)
        controls.addView(record)
        controls.addView(folder)
        controls.addView(smb)
        controls.addView(scan)
        root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        monitor = (supportFragmentManager.findFragmentByTag("monitor") as? MonitorFragment) ?: MonitorFragment()
        val filter = IntentFilter().apply { addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) mountCamera()
        else { showStatus("USB映像の利用にはカメラ権限が必要です"); requestPermissions(arrayOf(Manifest.permission.CAMERA), 101) }
        scanUsbDevices()
        root.setOnClickListener {
            controlsVisible = !controlsVisible
            controls.visibility = if (controlsVisible) View.VISIBLE else View.GONE
        }
    }

    private fun mountCamera() {
        if (cameraMounted) return
        cameraMounted = true
        supportFragmentManager.beginTransaction().replace(video.id, monitor, "monitor").commitNow()
    }

    @Deprecated("Permission callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                mountCamera()
                scanUsbDevices()
            } else showStatus("カメラ権限が拒否されました。端末の設定から許可してください")
        }
    }

    private fun scanUsbDevices() {
        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = usb.deviceList.values.toList()
        val cameras = devices.filter { device ->
            (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO }
                || device.deviceClass == UsbConstants.USB_CLASS_VIDEO
        }
        val details = devices.joinToString("; ") { device ->
            val classes = (0 until device.interfaceCount).joinToString(",") { device.getInterface(it).interfaceClass.toString() }
            "VID:%04X PID:%04X cls:%d if:[%s]".format(device.vendorId, device.productId, device.deviceClass, classes)
        }
        val message = when {
            devices.isEmpty() -> "USB機器なし。OTG・ケーブル・給電を確認"
            cameras.isEmpty() -> "USB機器 ${devices.size}台検出、映像用インターフェースなし: $details"
            else -> "UVC候補 ${cameras.size}台検出: $details"
        }
        showStatus(message)
    }

    override fun onDestroy() {
        if (receiverRegistered) { unregisterReceiver(usbReceiver); receiverRegistered = false }
        super.onDestroy()
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
    }

    fun showStatus(message: String) { runOnUiThread { state.text = message } }
    fun setRecording(active: Boolean) { runOnUiThread { record.text = if (active) "■ 停止" else "● 録画" } }
    fun saveMedia(file: java.io.File, mime: String) { Storage.save(this, file, mime, prefs) { showStatus(it) } }

    private fun chooseFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(i, 47)
    }

    @Deprecated("Android Activity callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 47 && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            prefs.edit().putString("folder", uri.toString()).apply()
            showStatus("保存先を設定しました")
        }
    }

    private fun smbSettings() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 10, 24, 0) }
        fun field(hint: String, key: String, secret: Boolean = false): EditText = EditText(this).also {
            it.hint = hint
            if (!secret) it.setText(prefs.getString(key, "") ?: "")
            if (secret) it.inputType = 129
            layout.addView(it)
        }
        val path = field("smb://192.168.1.10/share/folder", "smb")
        val domain = field("ドメイン (省略可)", "domain")
        val username = field("ユーザー名", "username")
        val password = field("パスワード (この起動中だけ保持)", "unused", true)
        AlertDialog.Builder(this).setTitle("SMB保存先")
            .setView(layout)
            .setNeutralButton("無効にする") { _, _ ->
                prefs.edit().remove("smb").apply(); Storage.password = null; showStatus("SMB転送を無効にしました")
            }
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("保存") { _, _ ->
                val url = path.text.toString().trim().trimEnd('/')
                if (!url.startsWith("smb://") || url.contains("@")) {
                    showStatus("SMB URLは smb://host/share の形式で入力してください")
                } else {
                    prefs.edit().putString("smb", url).putString("domain", domain.text.toString()).putString("username", username.text.toString()).apply()
                    Storage.password = password.text.toString().toCharArray()
                    showStatus("SMB転送を有効にしました")
                }
            }.show()
    }
}
