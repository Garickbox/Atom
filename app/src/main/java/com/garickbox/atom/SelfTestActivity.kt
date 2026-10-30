package com.garickbox.atom

import android.graphics.Color
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.mesh.core.InAppLogger
import androidx.lifecycle.lifecycleScope
import com.garickbox.atom.databinding.ActivitySelfTestBinding
import kotlinx.coroutines.launch

class SelfTestActivity : AppCompatActivity() {

    private lateinit var b: ActivitySelfTestBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!com.garickbox.atom.TvHelper.isTv(this)) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        b = ActivitySelfTestBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        if (!PermissionsHelper.allGranted(this)) {
            val missing = PermissionsHelper.missing(this)
            InAppLogger.d("SelfTest", "requesting missing: $missing")
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            runSelfTest()
        }
    }

    private fun addHeader(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 18f
            setPadding(0, 0, 0, 16)
            gravity = Gravity.START
        }
        b.resultsContainer.addView(tv)
    }

    private fun addRow(r: SelfTestRunner.TestResult) {
        val tv = TextView(this).apply {
            text = "${if (r.passed) "✅" else "❌"}  ${r.name}\n     ${r.detail}"
            textSize = 15f
            setPadding(0, 8, 0, 8)
            setTextColor(if (r.passed) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))
        }
        b.resultsContainer.addView(tv)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        runSelfTest()
    }

    private fun runSelfTest() {
        b.progress.visibility = android.view.View.VISIBLE
        b.resultsContainer.removeAllViews()
        lifecycleScope.launch {
            val results = SelfTestRunner.runAll(this@SelfTestActivity)
            b.progress.visibility = android.view.View.GONE
            val ok = results.count { it.passed }
            val total = results.size
            addHeader("$ok/$total проверок пройдено")
            results.forEach { addRow(it) }
        }
    }

}
