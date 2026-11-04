package org.francasio.sing

import org.francasio.sing.provider.SettingsValidation
import org.francasio.sing.provider.SingSettings
import org.junit.Assert.*
import org.junit.Test

/** Shared between JVM and on-device instrumentation runs. */
class SettingsValidationTest {
    @Test fun rejectsTraversalAndNonModelFiles() {
        for (name in listOf("../models.json", "..\\model.onnx", "/tmp/x.onnx", "a/b.onnx", "a%2fb.onnx", "settings.xml", "", "..")) {
            assertFalse(name, SettingsValidation.isModelFileName(name))
        }
        for (name in listOf("models.json", "UVR-MDX.v1_fp32.onnx", "vi_nnet_fp32.vinet", "model.mnn")) {
            assertTrue(name, SettingsValidation.isModelFileName(name))
        }
    }

    @Test fun rejectsUnknownKeysAndIncorrectPreferenceTypes() {
        assertFalse(SettingsValidation.isValidSetting("heartbeat_time", true))
        assertFalse(SettingsValidation.isValidSetting(SingSettings.KEY_THREAD_COUNT, true))
        assertFalse(SettingsValidation.isValidSetting(SingSettings.KEY_THREAD_COUNT, 0))
        assertFalse(SettingsValidation.isValidSetting(SingSettings.KEY_THREAD_COUNT, 65))
        assertFalse(SettingsValidation.isValidSetting(SingSettings.KEY_HARDWARE_TARGET, "invalid"))
        assertFalse(SettingsValidation.isValidSetting(SingSettings.KEY_SELECTED_MODEL, "../x"))
        assertTrue(SettingsValidation.isValidSetting(SingSettings.KEY_THREAD_COUNT, 4))
        assertTrue(SettingsValidation.isValidSetting(SingSettings.KEY_ECO_MODE, false))
        assertTrue(SettingsValidation.isValidSetting(SingSettings.KEY_SELECTED_MODEL, ""))
    }
}
