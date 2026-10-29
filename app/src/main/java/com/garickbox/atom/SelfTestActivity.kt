package com.garickbox.atom

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.garickbox.atom.databinding.ActivitySelfTestBinding
import kotlinx.coroutines.launch

class SelfTestActivity : AppCompatActivity() {

    private lateinit var b: ActivitySelfTestBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySelfTestBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        lifecycleScope.launch {
            val results = SelfTestRunner.runAll(this@SelfTestActivity)
            b.progress.visibility = android.view.View.GONE
            val ok = results.count { it.passed }
            val total = results.size
            addHeader("$ok/$total проверок пройдено")
            results.forEach { addRow(it) }
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
}
