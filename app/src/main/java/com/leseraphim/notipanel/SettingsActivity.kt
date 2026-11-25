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
    private lateinit var btnA11y: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val ver = packageManager.getPackageInfo(packageName, 0).versionName
        findViewById<TextView>(R.id.appTitle).text = "노티패널 설정  v$ver"

        // 상단바·하단 제스처 바 영역만큼 여백 (내용이 상단바에 가리지 않게)
        val root = findViewById<android.view.View>(R.id.root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        btnOverlay = findViewById(R.id.btnOverlay)
        btnListener = findViewById(R.id.btnListener)
        swEnable = findViewById(R.id.swEnable)
        btnA11y = findViewById(R.id.btnA11y)
        findViewById<MaterialButton>(R.id.btnFix).setOnClickListener {
            Toast.makeText(this, "루트로 권한 복구 중…", Toast.LENGTH_SHORT).show()
            PermissionFixer.restore(this) { ok ->
                runOnUiThread {
                    Toast.makeText(
                        this, if (ok) "권한을 다시 켰어요" else "실패: KernelSU에서 루트 허용을 확인해 주세요",
                        Toast.LENGTH_LONG
                    ).show()
                    recreate()
                }
            }
        }
        btnA11y.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

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
            "사이드바 진하기: $it%"
        }
        bindSlider(R.id.slPos, R.id.lblPos, Prefs.KEY_BAR_POS, Prefs.barPos(this)) {
            "사이드바 위치: 화면 위에서 $it%"
        }
        bindSlider(R.id.slLen, R.id.lblLen, Prefs.KEY_BAR_LEN, Prefs.barLen(this)) {
            "사이드바 길이: 화면 높이의 $it%"
        }
        bindSlider(R.id.slWidth, R.id.lblWidth, Prefs.KEY_BAR_W, Prefs.barW(this)) {
            "사이드바 두께: ${it}dp"
        }
        bindSlider(R.id.slBlur, R.id.lblBlur, Prefs.KEY_BLUR, Prefs.blur(this)) {
            "패널 블러 강도: $it"
        }

        findViewById<MaterialButton>(R.id.btnTest).setOnClickListener {
            val svc = OverlayService.instance
            if (svc == null) Toast.makeText(this, "먼저 '사이드바 켜기'를 켜 주세요", Toast.LENGTH_SHORT).show()
            else svc.testShow()
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

        // 루트 되는 폰에서만 루트 버튼 보이기 + 권한 자동 유지 상태 안내
        val btnFix = findViewById<MaterialButton>(R.id.btnFix)
        val keepInfo = findViewById<TextView>(R.id.keepInfo)
        val secureOk = PermissionFixer.hasSecureSettings(this)
        PermissionFixer.checkRoot { rooted ->
            runOnUiThread {
                btnFix.visibility = if (rooted) android.view.View.VISIBLE else android.view.View.GONE
                keepInfo.text = when {
                    secureOk -> "재부팅 후 권한 자동 유지: 켜짐 ✓"
                    rooted -> "루트로 재부팅 때마다 권한을 자동 복구해요"
                    else -> "재부팅 후 접근성이 풀리면, PC에 폰을 연결하고 아래 명령을 한 번만 실행하세요. 그다음부턴 앱이 스스로 다시 켜요.\n\nadb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
                }
            }
        }

        val a11yCn = ComponentName(this, A11yService::class.java).flattenToString()
        val a11yOk = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains(a11yCn) == true
        btnA11y.text = if (a11yOk) "접근성: 켜짐 ✓" else "접근성 켜기 (설치된 앱 → 노티패널)"

        findViewById<TextView>(R.id.homeInfo).text = A11yService.status()

        swEnable.isChecked = Prefs.enabled(this) && overlayOk
        if (swEnable.isChecked) OverlayService.start(this)
    }
}
