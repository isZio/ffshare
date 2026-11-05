package com.caydey.ffshare

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
import androidx.preference.*
import com.caydey.ffshare.utils.CacheCleaner
import com.caydey.ffshare.utils.Settings
import com.caydey.ffshare.utils.Utils
import timber.log.Timber
import java.util.concurrent.Executors

class PreferencesFragment : PreferenceFragmentCompat() {
    private val settings: Settings by lazy { Settings(appContext) }
    private val utils: Utils by lazy { Utils(appContext) }
    private val appContext: Context by lazy { requireContext().applicationContext }
    private val uiHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()

    // pick the folder the compressed files are copied to
    private val pickSaveFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val persisted = runCatching {
            requireContext().contentResolver.takePersistableUriPermission(uri, flags)
        }.isSuccess
        if (!persisted) {
            Timber.d("Unable to persist permission for %s", uri)
            toast(getString(R.string.save_location_unavailable))
            return@registerForActivityResult
        }
        settings.autoSaveTreeUri = uri.toString()
        updateSaveLocationSummary()
        toast(getString(R.string.save_location_picked, folderNameOf(uri) ?: uri.path ?: ""))
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        dynamicallyShowCustomName()
        dynamicallyAddCustomParamTooltips()
        setupSavePreferences()
        setupCachePreference()
        setupAboutPreference()
    }

    override fun onResume() {
        super.onResume()
        updateSaveLocationSummary()
        refreshCacheSummary()
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun dynamicallyAddCustomParamTooltips() {
        val customParamKeys = arrayOf("pref_custom_video_params", "pref_custom_audio_params", "pref_custom_image_params")
        for (customParamKey in customParamKeys) {
            val element = findPreference<EditTextPreference>(customParamKey)
            element?.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
        }
    }

    private fun dynamicallyShowCustomName() {
        // only show pref_compressed_media_custom_name if pref_compressed_media_name is "Custom"
        val customMediaNamePreference = findPreference<EditTextPreference>("pref_compressed_media_custom_name")
        val compressedMediaNamePreference = findPreference<ListPreference>("pref_compressed_media_name")
        compressedMediaNamePreference?.setOnPreferenceChangeListener { _, value ->
            customMediaNamePreference?.isVisible = (value == "CUSTOM")
            true
        }
        // trigger update for initial load
        compressedMediaNamePreference?.callChangeListener(compressedMediaNamePreference.value)
    }

    // ---- saving ----

    private fun setupSavePreferences() {
        findPreference<Preference>("pref_auto_save_location")?.setOnPreferenceClickListener {
            pickSaveFolder.launch(null)
            true
        }
        findPreference<Preference>("pref_auto_save_reset")?.setOnPreferenceClickListener {
            settings.autoSaveTreeUri = ""
            updateSaveLocationSummary()
            toast(getString(R.string.save_location_reset))
            true
        }
        findPreference<Preference>("pref_open_guide")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), GuideActivity::class.java))
            true
        }
    }

    private fun updateSaveLocationSummary() {
        val preference = findPreference<Preference>("pref_auto_save_location") ?: return
        val treeUri = settings.autoSaveTreeUri
        preference.summary = if (treeUri.isBlank()) {
            getString(R.string.settings_auto_save_location_default)
        } else {
            val name = folderNameOf(Uri.parse(treeUri)) ?: Uri.parse(treeUri).path ?: treeUri
            getString(R.string.settings_auto_save_location_selected, name)
        }
        findPreference<Preference>("pref_auto_save_reset")?.isVisible = treeUri.isNotBlank()
    }

    private fun folderNameOf(uri: Uri): String? =
        runCatching { DocumentFile.fromTreeUri(requireContext(), uri)?.name }.getOrNull()

    // ---- cache ----

    private fun setupCachePreference() {
        findPreference<Preference>("pref_cache_cleanup")?.setOnPreferenceClickListener {
            confirmClearCache()
            true
        }
    }

    private fun setupAboutPreference() {
        findPreference<Preference>("pref_about")?.setOnPreferenceClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.about_title)
                .setMessage(getString(R.string.about_message, App.versionName))
                .setPositiveButton(R.string.close, null)
                .setNeutralButton(R.string.about_open_upstream) { _, _ ->
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/caydey/ffshare")))
                    }
                }
                .show()
            true
        }
    }

    private fun refreshCacheSummary() {
        val preference = findPreference<Preference>("pref_cache_cleanup") ?: return
        ioExecutor.execute {
            val size = CacheCleaner.cacheSize(appContext)
            uiHandler.post {
                preference.summary = if (size <= 0L) {
                    getString(R.string.settings_cache_empty)
                } else {
                    getString(R.string.settings_cache_size, utils.bytesToHuman(size))
                }
            }
        }
    }

    private fun confirmClearCache() {
        val context = appContext
        val size = CacheCleaner.cacheSize(context)
        val fileCount = CacheCleaner.cacheFileCount(context)
        if (size <= 0L) {
            toast(getString(R.string.clear_cache_empty))
            refreshCacheSummary()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.clear_cache_dialog_title)
            .setMessage(
                getString(
                    R.string.clear_cache_dialog_message,
                    fileCount,
                    utils.bytesToHuman(size)
                )
            )
            .setNegativeButton(R.string.cancel_ffmpeg, null)
            .setPositiveButton(R.string.clear_cache_confirm) { _, _ ->
                ioExecutor.execute {
                    val result = CacheCleaner.clear(context)
                    uiHandler.post {
                        toast(getString(R.string.clear_cache_done, utils.bytesToHuman(result.freedBytes)))
                        refreshCacheSummary()
                    }
                }
            }
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }
}
