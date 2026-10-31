package dev.ryuya.galaxycapture

import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.CompoundButton.OnCheckedChangeListener
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.divider.MaterialDivider
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.slider.Slider.OnChangeListener
import com.google.android.material.textview.MaterialTextView
import java.util.ArrayList
import java.util.HashMap
import java.util.IdentityHashMap
import java.util.UUID
import kotlin.math.round

class MainActivity : AppCompatActivity() {
    private var status: TextView? = null
    private val individual: MutableList<LinearLayout?> = ArrayList<LinearLayout?>()
    private var pages: SettingsTabs? = null
    private var preview: RecordingPreview? = null
    private var syncing: Boolean = false
    private val sections: MutableMap<LinearLayout?, LinearLayout?> =
        IdentityHashMap<LinearLayout?, LinearLayout?>()
    private val toggles: MutableMap<String?, MaterialSwitch?> = HashMap<String?, MaterialSwitch?>()
    private val levels: MutableMap<String?, Slider?> = HashMap<String?, Slider?>()
    private val changed: OnSharedPreferenceChangeListener =
        OnSharedPreferenceChangeListener { p: SharedPreferences?, key: String? ->
            syncSettings()
        }

    public override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false)
        val safe = LinearLayout(this)
        safe.setOrientation(LinearLayout.VERTICAL)
        setContentView(safe)
        ViewCompat.setOnApplyWindowInsetsListener(
            safe,
            OnApplyWindowInsetsListener { v: View?, i: WindowInsetsCompat? ->
                val b = i!!.getInsets(Type.systemBars() or Type.displayCutout())
                v!!.setPadding(b.left, b.top, b.right, b.bottom)
                i!!
            },
        )
        ViewCompat.requestApplyInsets(safe)
        val bar = MaterialToolbar(this)
        bar.setTitle(R.string.app_name)
        safe.addView(bar, android.widget.LinearLayout.LayoutParams(-1, dp(64)))
        val master = LinearLayout(this)
        master.setOrientation(LinearLayout.VERTICAL)
        master.setPadding(dp(20), 0, dp(20), 0)
        safe.addView(master)
        addMasterSwitch(master)
        pages = SettingsTabs(this, safe, state)
        val common = pages!!.page(R.string.tab_common)
        val galaxy = pages!!.page(R.string.tab_galaxy)
        val operation = pages!!.page(R.string.tab_status)
        val information = pages!!.page(R.string.tab_about)
        addScope(common, R.string.scope_common_title, R.string.scope_common_detail)
        addScope(galaxy, R.string.scope_galaxy_title, R.string.scope_galaxy_detail)
        val commonOptions = LinearLayout(this)
        commonOptions.setOrientation(LinearLayout.VERTICAL)
        common.addView(commonOptions)
        val galaxyOptions = LinearLayout(this)
        galaxyOptions.setOrientation(LinearLayout.VERTICAL)
        galaxy.addView(galaxyOptions)
        individual.add(commonOptions)
        individual.add(galaxyOptions)
        addHeading(commonOptions, R.string.section_video)
        addSwitch(commonOptions, "rotation", R.string.rotation, R.string.rotation_detail, true)
        addSwitch(
            commonOptions,
            "ignore_half_turns",
            R.string.ignore_half_turns,
            R.string.ignore_half_turns_detail,
            false,
        )
        addSwitch(
            commonOptions,
            "single_window",
            R.string.single_window,
            R.string.single_window_detail,
            false,
        )
        addSwitch(
            commonOptions,
            "secure_recording",
            R.string.secure_recording,
            R.string.secure_recording_detail,
            false,
        )
        addHeading(commonOptions, R.string.section_audio_display)
        addSwitch(
            commonOptions,
            "app_audio_only",
            R.string.app_audio_only,
            R.string.app_audio_only_detail,
            true,
        )
        addSwitch(
            commonOptions,
            "include_call_audio",
            R.string.include_call_audio,
            R.string.include_call_audio_detail,
            false,
        )
        addSwitch(
            commonOptions,
            "hide_duplicate_indicator",
            R.string.hide_duplicate_indicator,
            R.string.hide_duplicate_indicator_detail,
            true,
        )
        addSwitch(
            galaxyOptions,
            "recording_icon_right",
            R.string.recording_icon_right,
            R.string.recording_icon_right_detail,
            false,
        )
        addHeading(galaxyOptions, R.string.section_recording_start)
        addSwitch(
            galaxyOptions,
            "samsung_app",
            R.string.samsung_app,
            R.string.samsung_app_detail,
            true,
        )
        addSwitch(galaxyOptions, "countdown", R.string.countdown, R.string.countdown_detail, true)
        addHeading(galaxyOptions, R.string.section_audio)
        addSwitch(
            galaxyOptions,
            "speaker_media",
            R.string.speaker_media,
            R.string.speaker_media_detail,
            true,
        )
        addSwitch(
            galaxyOptions,
            "recording_volume",
            R.string.recording_volume,
            R.string.recording_volume_detail,
            false,
        )
        addVolume(galaxyOptions, "media_volume", R.string.media_recording_volume)
        addVolume(galaxyOptions, "mic_volume", R.string.mic_recording_volume)
        addHeading(galaxyOptions, R.string.section_screenshot)
        addSwitch(
            galaxyOptions,
            "secure_screenshot",
            R.string.secure_screenshot,
            R.string.secure_screenshot_detail,
            false,
        )
        addHeading(galaxyOptions, R.string.section_capture_controls)
        addSwitch(
            galaxyOptions,
            "exclude_capture_controls",
            R.string.exclude_capture_controls,
            R.string.exclude_capture_controls_detail,
            true,
        )
        addSwitch(
            galaxyOptions,
            "hide_recording_controls",
            R.string.hide_recording_controls,
            R.string.hide_recording_controls_detail,
            false,
        )
        updateEnabled()
        status = MaterialTextView(this)
        status!!.setTextSize(14f)
        status!!.setPadding(dp(16), dp(16), dp(16), dp(16))
        val card = MaterialCardView(this)
        card.addView(status)
        operation.addView(card)
        val refresh = MaterialButton(ContextThemeWrapper(this, R.style.TonalActionTheme))
        refresh.setText(R.string.refresh)
        refresh.setOnClickListener(android.view.View.OnClickListener { v: View? -> showStatus() })
        operation.addView(refresh)
        val reset =
            MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            )
        reset.setText(R.string.reset_guard)
        reset.setOnClickListener(
            android.view.View.OnClickListener { v: View? ->
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.reset_guard)
                    .setMessage(R.string.reset_detail)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(
                        android.R.string.ok,
                        android.content.DialogInterface.OnClickListener {
                            _: android.content.DialogInterface?,
                            w: Int ->
                            getSharedPreferences("config", 0)
                                .edit()
                                .putString("reset", UUID.randomUUID().toString())
                                .apply()
                            notifyConfig()
                            showStatus()
                        },
                    )
                    .show()
            }
        )
        operation.addView(reset)
        val version = MaterialTextView(this)
        version.setText(getString(R.string.app_version, BuildConfig.VERSION_NAME))
        version.setTextAppearance(
            com.google.android.material.R.style.TextAppearance_Material3_TitleMedium
        )
        information.addView(version)
        val intro = MaterialTextView(this)
        intro.setText(R.string.intro)
        intro.setTextSize(16f)
        intro.setPadding(0, dp(16), 0, dp(24))
        information.addView(intro)
        val notice = MaterialButton(ContextThemeWrapper(this, R.style.TonalActionTheme))
        notice.setText(R.string.licenses)
        notice.setOnClickListener(
            android.view.View.OnClickListener { v: View? ->
                startActivity(Intent(this, LicensesActivity::class.java))
            }
        )
        information.addView(notice)
        pages!!.restore()
        showStatus()
    }

    private fun addMasterSwitch(parent: LinearLayout) {
        val heading = MaterialTextView(this)
        heading.setText(R.string.enabled)
        heading.setPadding(dp(8), 0, 0, dp(8))
        parent.addView(heading)
        val card = MaterialCardView(this)
        card.setRadius(dp(28).toFloat())
        card.setStrokeWidth(0)
        card.setCardBackgroundColor(
            MaterialColors.getColor(
                parent,
                com.google.android.material.R.attr.colorPrimaryContainer,
            )
        )
        val toggle = MaterialSwitch(this)
        toggle.setContentDescription(getString(R.string.enabled))
        toggle.setTextSize(20f)
        toggle.setMinHeight(dp(64))
        toggle.setPadding(dp(24), dp(8), dp(24), dp(8))
        toggles.put("enabled", toggle)
        toggle.setOnCheckedChangeListener(
            OnCheckedChangeListener { button: CompoundButton?, on: Boolean ->
                updateMasterLabel(toggle, on)
                if (syncing) return@OnCheckedChangeListener
                getSharedPreferences("config", 0).edit().putBoolean("enabled", on).apply()
                notifyConfig()
                updateEnabled()
            }
        )
        toggle.setChecked(getSharedPreferences("config", 0).getBoolean("enabled", false))
        updateMasterLabel(toggle, toggle.isChecked())
        card.addView(toggle, android.view.ViewGroup.LayoutParams(-1, -2))
        parent.addView(card)
        val detail = MaterialTextView(this)
        detail.setText(R.string.master_scope_detail)
        detail.setTextSize(12f)
        detail.setPadding(dp(8), dp(8), dp(8), dp(8))
        parent.addView(detail)
    }

    private fun updateMasterLabel(toggle: MaterialSwitch, on: Boolean) {
        toggle.setText(if (on) R.string.master_on else R.string.master_off)
        toggle.setTextColor(
            MaterialColors.getColor(
                toggle,
                if (on) com.google.android.material.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorOnSurface,
            )
        )
    }

    private fun addScope(parent: LinearLayout, title: Int, detail: Int) {
        val card = MaterialCardView(this)
        card.setRadius(dp(28).toFloat())
        card.setStrokeWidth(0)
        card.setCardBackgroundColor(
            MaterialColors.getColor(
                parent,
                com.google.android.material.R.attr.colorSurfaceContainer,
            )
        )
        val body = LinearLayout(this)
        body.setOrientation(LinearLayout.VERTICAL)
        body.setPadding(dp(16), dp(16), dp(16), dp(16))
        val label = MaterialTextView(this)
        label.setText(title)
        label.setTextAppearance(
            com.google.android.material.R.style.TextAppearance_Material3_TitleMedium
        )
        ViewCompat.setAccessibilityHeading(label, true)
        body.addView(label)
        val description = MaterialTextView(this)
        description.setText(detail)
        description.setPadding(0, dp(8), 0, 0)
        body.addView(description)
        if (title == R.string.scope_common_title) {
            preview = RecordingPreview(this)
            body.addView(preview, android.widget.LinearLayout.LayoutParams(-1, dp(112)))
        }
        card.addView(body)
        val position = android.widget.LinearLayout.LayoutParams(-1, -2)
        position.bottomMargin = dp(20)
        parent.addView(card, position)
    }

    private fun section(parent: LinearLayout): LinearLayout? {
        val current = sections.get(parent)
        if (current != null) return current
        val card = MaterialCardView(this)
        card.setRadius(dp(28).toFloat())
        card.setStrokeWidth(0)
        card.setCardBackgroundColor(
            MaterialColors.getColor(
                parent,
                com.google.android.material.R.attr.colorSurfaceContainerLow,
            )
        )
        val body = LinearLayout(this)
        body.setOrientation(LinearLayout.VERTICAL)
        body.setPadding(dp(4), dp(4), dp(4), dp(4))
        card.addView(body)
        val position = android.widget.LinearLayout.LayoutParams(-1, -2)
        position.bottomMargin = dp(16)
        parent.addView(card, position)
        sections.put(parent, body)
        return body
    }

    private fun appendRow(parent: LinearLayout, row: LinearLayout?) {
        val body = section(parent)
        if (
            body!!.getChildCount() > 0 &&
                "section-heading" != body!!.getChildAt(body!!.getChildCount() - 1).getTag()
        ) {
            val divider = MaterialDivider(this)
            val position = android.widget.LinearLayout.LayoutParams(-1, -2)
            position.setMargins(dp(16), 0, dp(16), 0)
            body!!.addView(divider, position)
        }
        body!!.addView(row, android.widget.LinearLayout.LayoutParams(-1, -2))
    }

    private fun addHeading(parent: LinearLayout, title: Int) {
        sections.remove(parent)
        val label = MaterialTextView(this)
        label.setTag("section-heading")
        label.setText(title)
        label.setTextAppearance(
            com.google.android.material.R.style.TextAppearance_Material3_TitleSmall
        )
        label.setTextColor(
            MaterialColors.getColor(
                label,
                androidx.appcompat.R.attr.colorPrimary,
            )
        )
        label.setPadding(dp(16), dp(16), dp(16), dp(4))
        ViewCompat.setAccessibilityHeading(label, true)
        section(parent)!!.addView(label)
    }

    private fun addSwitch(
        parent: LinearLayout,
        key: String,
        label: Int,
        detail: Int,
        def: Boolean,
    ) {
        val box = LinearLayout(this)
        box.setOrientation(LinearLayout.VERTICAL)
        box.setPadding(dp(16), dp(12), dp(16), dp(16))
        val toggle = MaterialSwitch(this)
        toggle.setText(label)
        toggle.setTextAppearance(
            com.google.android.material.R.style.TextAppearance_Material3_TitleMedium
        )
        toggle.setMinHeight(dp(48))
        toggle.setChecked(getSharedPreferences("config", 0).getBoolean(key, def))
        toggles.put(key, toggle)
        toggle.setOnCheckedChangeListener(
            OnCheckedChangeListener { b: CompoundButton?, on: Boolean ->
                if (syncing) return@OnCheckedChangeListener
                getSharedPreferences("config", 0).edit().putBoolean(key, on).apply()
                notifyConfig()
                if (key == "enabled" || key == "recording_volume") updateEnabled()
            }
        )
        box.addView(toggle)
        val desc = MaterialTextView(this)
        desc.setText(detail)
        desc.setTextAppearance(
            com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
        )
        desc.setTextColor(
            MaterialColors.getColor(
                desc,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
            )
        )
        desc.setLineSpacing(dp(2).toFloat(), 1f)
        desc.setPadding(0, dp(4), dp(40), 0)
        box.addView(desc)
        appendRow(parent, box)
    }

    private fun addVolume(parent: LinearLayout, key: String?, title: Int) {
        val box = LinearLayout(this)
        box.setTag("recording-volume")
        box.setOrientation(LinearLayout.VERTICAL)
        box.setPadding(dp(16), dp(12), dp(16), dp(12))
        val label = MaterialTextView(this)
        val current = AudioLevels.clamp(getSharedPreferences("config", 0).getInt(key, 100))
        label.setText(getString(title) + ": " + current + "%")
        box.addView(label)
        val slider = Slider(this)
        levels.put(key, slider)
        slider.setContentDescription(getString(title))
        slider.setValueFrom(0f)
        slider.setValueTo(100f)
        slider.setStepSize(1f)
        slider.setValue(current.toFloat())
        val pending = Handler(Looper.getMainLooper())
        val notify = Runnable { this.notifyConfig() }
        slider.addOnChangeListener(
            OnChangeListener { v: Slider?, value: Float, user: Boolean ->
                label.setText(getString(title) + ": " + Math.round(value) + "%")
                if (user && !syncing) {
                    getSharedPreferences("config", 0).edit().putInt(key, Math.round(value)).apply()
                    pending.removeCallbacks(notify)
                    pending.postDelayed(notify, 200)
                }
            }
        )
        box.addView(slider)
        appendRow(parent, box)
    }

    private fun updateEnabled() {
        if (preview != null) preview!!.invalidate()
        val enabled = getSharedPreferences("config", 0).getBoolean("enabled", false)
        for (group in individual) {
            setChildrenEnabled(group!!, enabled)
            group!!.setAlpha(if (enabled) 1f else .38f)
            updateVolumeEnabled(
                group,
                enabled && getSharedPreferences("config", 0).getBoolean("recording_volume", false),
            )
        }
    }

    private fun updateVolumeEnabled(view: View, enabled: Boolean) {
        if ("recording-volume" == view.getTag()) {
            setChildrenEnabled(view, enabled)
            view.setAlpha(if (enabled) 1f else .38f)
        } else if (view is ViewGroup)
            for (i in 0 until view.getChildCount()) updateVolumeEnabled(view.getChildAt(i), enabled)
    }

    private fun setChildrenEnabled(view: View, enabled: Boolean) {
        view.setEnabled(enabled)
        if (view is ViewGroup)
            for (i in 0 until view.getChildCount()) setChildrenEnabled(view.getChildAt(i), enabled)
    }

    private fun notifyConfig() {
        Startup.notifyHosts(this)
    }

    private fun showStatus() {
        val p = getSharedPreferences("status", 0)
        val s = StringBuilder(getString(R.string.status))
        for (host in arrayOf<String>("framework", "systemui", "samsung")) s.append("\n\n")
            .append(host)
            .append(": ")
            .append(p.getString(host, getString(R.string.not_loaded)))
        status!!.setText(s)
    }

    override fun onSaveInstanceState(out: Bundle) {
        if (pages != null) pages!!.save(out!!)
        super.onSaveInstanceState(out!!)
    }

    private fun syncSettings() {
        if (preview != null) preview!!.invalidate()
        syncing = true
        try {
            val prefs = getSharedPreferences("config", 0)
            for (setting in CaptureSettings.all()) {
                val toggle = toggles.get(setting!!.key)
                if (toggle != null)
                    toggle.setChecked(prefs.getBoolean(setting!!.key, setting!!.defaultValue))
            }
            for (entry in levels.entries) entry.value!!.setValue(
                AudioLevels.clamp(prefs.getInt(entry.key, 100)).toFloat()
            )
            updateEnabled()
        } finally {
            syncing = false
        }
    }

    override fun onStart() {
        super.onStart()
        getSharedPreferences("config", 0).registerOnSharedPreferenceChangeListener(changed)
        syncSettings()
    }

    override fun onStop() {
        getSharedPreferences("config", 0).unregisterOnSharedPreferenceChangeListener(changed)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (status != null) showStatus()
    }

    private fun dp(n: Int): Int {
        return Math.round(n * getResources().getDisplayMetrics().density)
    }
}
