package org.francasio.sing.provider

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class SettingsRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == SingSettings.ACTION_REQUEST_SETTINGS) {
            val ts = intent.getLongExtra("timestamp", System.currentTimeMillis())
            val manager = intent.getStringExtra("manager_info") ?: "LSPatch"
            SingSettings.saveHeartbeat(context, ts, manager, engineVersion = intent.getStringExtra("engine_version"))

            SingSettings.loadLocal(context)
            // Make sure the catalog is in memory before resolving the model:
            // without it the lookup used to fabricate native engine info for ANY
            // selection, and the module registered that as a dynamic model -
            // which made Apple Music run Vi-NNet no matter what was selected.
            // loadModels' sources here are all local and cheap (sdcard, filesDir
            // cache, provider, assets); the network fetch only runs off-main.
            try {
                org.francasio.sing.util.ModelLoader.loadModels(context)
            } catch (t: Throwable) {
                Log.w("AppleSing_Settings", "Could not prepare model catalog: ${t.message}")
            }
            // null when the catalog really does not contain the id: then send the
            // selection id only and let the module resolve it against its own
            // catalog instead of shipping fabricated file/engine info.
            val modelInfo = org.francasio.sing.util.ModelLoader.findCatalogModel(SingSettings.selectedModelId)

            val baseUri = android.net.Uri.parse("content://org.francasio.sing.provider/")
            try {
                context.grantUriPermission(
                    "com.apple.android.music",
                    baseUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                )
            } catch (t: Throwable) {
                Log.w("AppleSing_Settings", "Failed to grant URI permission to com.apple.android.music: ${t.message}")
            }

            // NOTE: no data URI / grant flags here on purpose - a dynamically
            // registered IntentFilter(action) does not match intents that carry
            // data, which silently stopped this reply from ever reaching the
            // module. The explicit grantUriPermission above already gives Apple
            // Music access to the provider.
            val reply = SingSettings.createSettingsUpdateIntent(context, "com.apple.android.music")
            context.sendBroadcast(reply)
            Log.i("AppleSing_Settings", "Replied to REQUEST_SETTINGS: apk=${context.applicationInfo.sourceDir}, preserveSide=${SingSettings.preserveSide}, twoWaySlider=${SingSettings.twoWaySlider}, threads=${SingSettings.threadCount}, ecoMode=${SingSettings.ecoMode}, target=${SingSettings.hardwareTarget} (forceCpu=${SingSettings.forceCpu}), model=${SingSettings.selectedModelId} (${modelInfo?.fileName ?: "catalog entry not found - module resolves id itself"}), allowZeroFloor=${SingSettings.allowZeroFloor}, forceSongEligibility=${SingSettings.forceSongEligibility}")
        }
    }
}
