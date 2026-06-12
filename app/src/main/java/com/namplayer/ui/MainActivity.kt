package com.namplayer.ui

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.namplayer.audio.EngineState
import com.namplayer.databinding.ActivityMainBinding
import com.namplayer.engine.NamFileInfo
import com.namplayer.engine.Preset
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val vm: MainViewModel by viewModels()

    // ── File pickers ──────────────────────────────────────

    private val pickNam = registerForActivityResult(
        ActivityResultContracts.OpenDocument()) { it?.let { vm.loadNam(it) } }

    private val pickCab = registerForActivityResult(
        ActivityResultContracts.OpenDocument()) { it?.let { vm.loadCab(it) } }

    private val askPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.toggle() else toast("يجب منح صلاحية الميكروفون")
    }

    // ── USB receiver ──────────────────────────────────────

    private val usbRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = when (i.action) {
            "com.namplayer.USB_ATTACHED"   -> vm.onUsbAttached()
            "com.namplayer.USB_DETACHED"   -> vm.onUsbDetached()
            "com.namplayer.USB_PERMISSION" -> vm.onUsbAttached()
            else -> Unit
        }
    }

    // ── onCreate ──────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        wire(); observe()
        registerReceiver(usbRx, IntentFilter().apply {
            addAction("com.namplayer.USB_ATTACHED")
            addAction("com.namplayer.USB_DETACHED")
            addAction("com.namplayer.USB_PERMISSION")
        }, RECEIVER_NOT_EXPORTED)
        intent?.data?.let { vm.loadNam(it) }
    }

    override fun onDestroy() { super.onDestroy(); unregisterReceiver(usbRx) }

    // ── Wire ──────────────────────────────────────────────

    private fun wire() {
        // Play/Stop
        b.btnPlay.setOnClickListener {
            if (!hasMicPerm()) { askPerm.launch(Manifest.permission.RECORD_AUDIO); return@setOnClickListener }
            vm.toggle()
        }
        // Files
        b.btnOpen.setOnClickListener  { pickNam.launch(arrayOf("*/*")) }
        b.btnScan.setOnClickListener  { showNamFilesSheet() }
        b.btnUsb.setOnClickListener   { showUsbSheet() }
        b.btnCab.setOnClickListener   { showCabSheet() }
        b.btnRecord.setOnClickListener { vm.toggleRecord() }

        // Gains
        b.sliderIn.addOnChangeListener  { _,v,u -> if(u){ vm.setInputGain(v);  b.tvIn.text  = "${v.toInt()}dB" } }
        b.sliderOut.addOnChangeListener { _,v,u -> if(u){ vm.setOutputGain(v); b.tvOut.text = "${v.toInt()}dB" } }

        // Gate
        b.switchGate.setOnCheckedChangeListener { _,on -> vm.setGateEnabled(on); b.sliderGate.isEnabled=on }
        b.sliderGate.addOnChangeListener { _,v,u -> if(u){ vm.setGateThreshold(v); b.tvGate.text="${v.toInt()}dB" } }

        // EQ
        b.switchEq.setOnCheckedChangeListener { _,on ->
            vm.setEqEnabled(on)
            b.sliderBass.isEnabled=on; b.sliderMid.isEnabled=on; b.sliderTreble.isEnabled=on
        }
        b.sliderBass.addOnChangeListener   { _,v,u -> if(u){ vm.setBass(v);   b.tvBass.text="${v.toInt()}dB" } }
        b.sliderMid.addOnChangeListener    { _,v,u -> if(u){ vm.setMid(v);    b.tvMid.text="${v.toInt()}dB"  } }
        b.sliderTreble.addOnChangeListener { _,v,u -> if(u){ vm.setTreble(v); b.tvTreble.text="${v.toInt()}dB" } }

        // Presets
        b.btnSavePreset.setOnClickListener { showSavePresetDialog() }
        b.btnPresets.setOnClickListener    { showPresetsSheet() }
    }

    // ── Observe ───────────────────────────────────────────

    private fun observe() {
        lifecycleScope.launch {
            vm.engineState.collect { state ->
                when (state) {
                    EngineState.RUNNING -> {
                        b.btnPlay.text = "⏹"
                        b.btnPlay.setBackgroundColor(0xFFE53935.toInt())
                        b.dot.setBackgroundResource(com.namplayer.R.drawable.dot_green)
                        b.tvState.text = "يعمل"
                    }
                    EngineState.IDLE -> {
                        b.btnPlay.text = "▶"
                        b.btnPlay.setBackgroundColor(0xFF43A047.toInt())
                        b.dot.setBackgroundResource(com.namplayer.R.drawable.dot_grey)
                        b.tvState.text = "متوقف"
                    }
                    EngineState.ERROR -> {
                        b.dot.setBackgroundResource(com.namplayer.R.drawable.dot_red)
                        b.tvState.text = "خطأ"; toast("خطأ في الصوت")
                    }
                }
            }
        }
        lifecycleScope.launch {
            vm.model.collect { mdl ->
                b.tvModel.text = mdl?.displayName ?: "لا يوجد موديل"
                b.tvArch.text  = mdl?.arch ?: ""
            }
        }
        lifecycleScope.launch {
            vm.cabName.collect { n ->
                b.tvCab.text = if (n != null) "🔊 $n" else "Cabinet: off"
                b.tvCab.setTextColor(if (n != null) 0xFF43A047.toInt() else 0xFF666666.toInt())
            }
        }
        lifecycleScope.launch {
            vm.stats.collect { s ->
                b.tvLatency.text = "%.1fms".format(s.latencyMs)
                b.tvCpu.text     = "%.0f%%".format(s.cpuPct)
                b.tvSr.text      = "${s.sampleRate/1000}k"
                b.vuIn.progress  = ((s.inLevelDb  + 60f) / 60f * 100f).toInt().coerceIn(0,100)
                b.vuOut.progress = ((s.outLevelDb + 60f) / 60f * 100f).toInt().coerceIn(0,100)
                b.tvXrun.visibility = if (s.xruns > 0) View.VISIBLE else View.GONE
                b.tvXrun.text = "⚠ xrun:${s.xruns}"
                if (s.isRecording) {
                    b.btnRecord.text = "⏹ ${s.recSeconds}s"
                    b.btnRecord.setBackgroundColor(0xFFE53935.toInt())
                } else {
                    b.btnRecord.text = "⏺ REC"
                    b.btnRecord.setBackgroundColor(0xFF333333.toInt())
                }
            }
        }
        lifecycleScope.launch { vm.activeUsb.collect { d -> b.btnUsb.text = if (d!=null) "🔌 ${d.label}" else "🔌 USB" } }
        lifecycleScope.launch { vm.loading.collect   { b.progress.visibility = if (it) View.VISIBLE else View.GONE } }
        lifecycleScope.launch { vm.msg.collect       { toast(it) } }
    }

    // ── NAM Files Sheet ───────────────────────────────────

    private fun showNamFilesSheet() {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val v     = layoutInflater.inflate(com.namplayer.R.layout.sheet_nam_files, null)
        sheet.setContentView(v)

        val ll    = v.findViewById<LinearLayout>(com.namplayer.R.id.llNamFiles)
        val empty = v.findViewById<TextView>(com.namplayer.R.id.tvNamEmpty)
        val files = vm.namFiles.value

        if (files.isEmpty()) {
            empty.visibility = View.VISIBLE
        } else {
            empty.visibility = View.GONE
            files.forEach { info ->
                val row = layoutInflater.inflate(com.namplayer.R.layout.item_nam_file, ll, false)
                row.findViewById<TextView>(com.namplayer.R.id.tvNamName).text   = info.name
                row.findViewById<TextView>(com.namplayer.R.id.tvNamSize).text   = "${info.sizeKb}KB"
                row.setOnClickListener { vm.loadNamFile(info); sheet.dismiss() }
                ll.addView(row)
            }
        }
        v.findViewById<Button>(com.namplayer.R.id.btnRescan).setOnClickListener {
            vm.scanNamFiles(); sheet.dismiss()
        }
        v.findViewById<Button>(com.namplayer.R.id.btnBrowseNam).setOnClickListener {
            pickNam.launch(arrayOf("*/*")); sheet.dismiss()
        }
        sheet.show()
    }

    // ── Cabinet Sheet ─────────────────────────────────────

    private fun showCabSheet() {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val v     = layoutInflater.inflate(com.namplayer.R.layout.sheet_cab, null)
        sheet.setContentView(v)
        v.findViewById<TextView>(com.namplayer.R.id.tvCabCurrent).text =
            vm.cabName.value ?: "لا يوجد Cabinet"
        val sw = v.findViewById<Switch>(com.namplayer.R.id.switchCab)
        sw.isChecked = vm.cabName.value != null
        sw.setOnCheckedChangeListener { _, on -> vm.setCabEnabled(on) }
        v.findViewById<Button>(com.namplayer.R.id.btnLoadCab).setOnClickListener {
            pickCab.launch(arrayOf("audio/*","*/*")); sheet.dismiss()
        }
        v.findViewById<Button>(com.namplayer.R.id.btnClearCab).setOnClickListener {
            vm.clearCab(); sheet.dismiss()
        }
        sheet.show()
    }

    // ── USB Sheet ─────────────────────────────────────────

    private fun showUsbSheet() {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val v     = layoutInflater.inflate(com.namplayer.R.layout.sheet_usb, null)
        sheet.setContentView(v)
        val ll    = v.findViewById<LinearLayout>(com.namplayer.R.id.llDevices)
        val empty = v.findViewById<TextView>(com.namplayer.R.id.tvEmpty)
        val devs  = vm.usbDevices.value
        if (devs.isEmpty()) { empty.visibility = View.VISIBLE } else {
            empty.visibility = View.GONE
            devs.forEach { dev ->
                val row = layoutInflater.inflate(com.namplayer.R.layout.item_usb, ll, false)
                row.findViewById<TextView>(com.namplayer.R.id.tvDevName).text = dev.info
                row.setOnClickListener { vm.selectUsb(dev); sheet.dismiss() }
                ll.addView(row)
            }
        }
        v.findViewById<Button>(com.namplayer.R.id.btnScan).setOnClickListener {
            vm.onUsbAttached(); sheet.dismiss(); showUsbSheet()
        }
        sheet.show()
    }

    // ── Presets Sheet ─────────────────────────────────────

    private fun showPresetsSheet() {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val v     = layoutInflater.inflate(com.namplayer.R.layout.sheet_presets, null)
        sheet.setContentView(v)
        val ll    = v.findViewById<LinearLayout>(com.namplayer.R.id.llPresets)
        val empty = v.findViewById<TextView>(com.namplayer.R.id.tvPresetsEmpty)
        val list  = vm.presetList.value

        if (list.isEmpty()) { empty.visibility = View.VISIBLE } else {
            empty.visibility = View.GONE
            list.forEach { p ->
                val row = layoutInflater.inflate(com.namplayer.R.layout.item_preset, ll, false)
                row.findViewById<TextView>(com.namplayer.R.id.tvPresetName).text = p.name
                row.findViewById<TextView>(com.namplayer.R.id.tvPresetInfo).text =
                    "In:${p.inputGain.toInt()}dB  Out:${p.outputGain.toInt()}dB  " +
                    (if (p.eqOn) "EQ " else "") + (if (p.gateOn) "Gate" else "")
                row.setOnClickListener { vm.loadPreset(p); sheet.dismiss() }
                row.setOnLongClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("حذف: ${p.name}؟")
                        .setPositiveButton("حذف") { _,_ -> vm.deletePreset(p.name); sheet.dismiss() }
                        .setNegativeButton("إلغاء", null).show()
                    true
                }
                ll.addView(row)
            }
        }
        sheet.show()
    }

    // ── Save Preset Dialog ────────────────────────────────

    private fun showSavePresetDialog() {
        val et = EditText(this).apply {
            hint = "اسم الـ Preset"
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(48, 24, 48, 8)
        }
        AlertDialog.Builder(this)
            .setTitle("💾 حفظ Preset")
            .setView(et)
            .setPositiveButton("حفظ") { _,_ ->
                val name = et.text.toString().trim()
                if (name.isNotEmpty()) vm.savePreset(name)
                else toast("أدخل اسماً")
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ── Helpers ───────────────────────────────────────────

    private fun hasMicPerm() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
