package com.leseraphim.notipanel

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

/** 설정 화면 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var btnOverlay: MaterialButton
    private lateinit var btnListener: MaterialButton
    private lateinit var swEnable: MaterialSwitch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = "노티패널 설정"

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        btnOverlay = findViewById(R.id.btnOverlay)
        btnListener = findViewById(R.id.btnListener)
        swEnable = findViewById(R.id.swEnable)

        btnOverlay.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        btnListener.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        val prefs = Prefs.get(this)

        swEnable.setOnCheckedChangeListener { sw, checked ->
            if (checked && !Settings.canDrawOverlays(this)) {
                sw.isChecked = false
                Toast.makeText(this, "먼저 '다른 앱 위에 표시'를 허용해 주세요", Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            prefs.edit().putBoolean(Prefs.KEY_ENABLED, checked).apply()
            if (checked) OverlayService.start(this) else OverlayService.stop(this)
        }

        bindSlider(R.id.slOpacity, R.id.lblOpacity, Prefs.KEY_OPACITY, Prefs.opacity(this)) {
            "터치 아이콘 불투명도: $it%  (0 = 완전 투명)"
        }
        bindSlider(R.id.slSize, R.id.lblSize, Prefs.KEY_SIZE, Prefs.sizeDp(this)) {
            "터치 아이콘 크기: ${it}dp"
        }
        bindSlider(R.id.slBlur, R.id.lblBlur, Prefs.KEY_BLUR, Prefs.blur(this)) {
            "패널 블러 강도: $it"
        }

        findViewById<MaterialButton>(R.id.btnResetPos).setOnClickListener {
            prefs.edit().remove(Prefs.KEY_POS_X).remove(Prefs.KEY_POS_Y).apply()
        }

        val blurInfo = findViewById<TextView>(R.id.blurInfo)
        val wm = getSystemService(WindowManager::class.java)
        blurInfo.text = when {
            Build.VERSION.SDK_INT < 31 -> "이 기기는 안드로이드 12 미만이라 블러 대신 반투명 배경을 써요."
            !wm.isCrossWindowBlurEnabled -> "지금은 시스템 블러가 꺼져 있어요(절전 모드 등). 반투명 배경으로 표시돼요."
            else -> "블러 지원됨"
        }
    }

    private fun bindSlider(sliderId: Int, labelId: Int, key: String, value: Int, label: (Int) -> String) {
        val slider = findViewById<Slider>(sliderId)
        val lbl = findViewById<TextView>(labelId)
        slider.value = value.toFloat().coerceIn(slider.valueFrom, slider.valueTo)
        lbl.text = label(value)
        slider.addOnChangeListener { _, v, _ ->
            val i = v.toInt()
            lbl.text = label(i)
            Prefs.get(this).edit().putInt(key, i).apply()
        }
    }

    override fun onResume() {
        super.onResume()
        val overlayOk = Settings.canDrawOverlays(this)
        btnOverlay.text = if (overlayOk) "다른 앱 위에 표시: 허용됨 ✓" else "다른 앱 위에 표시 허용하기"

        val cn = ComponentName(this, NotiListenerService::class.java).flattenToString()
        val listenerOk = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(cn) == true
        btnListener.text = if (listenerOk) "알림 접근: 허용됨 ✓" else "알림 접근 허용하기"

        swEnable.isChecked = Prefs.enabled(this) && overlayOk
        if (swEnable.isChecked) OverlayService.start(this)
    }
}
