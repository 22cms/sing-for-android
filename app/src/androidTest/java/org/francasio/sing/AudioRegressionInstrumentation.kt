package org.francasio.sing

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.util.Log
import org.junit.runner.JUnitCore

/** Minimal runner: no test-service/UI framework dependency and no real model downloads. */
class AudioRegressionInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        System.setProperty("sing.native.tests", "true")
        ProviderAccessTest.appContext = targetContext
        val result = JUnitCore.runClasses(
            AiStemSeparationEngineTest::class.java,
            NativeResamplerTest::class.java,
            VrChunkingTest::class.java,
            VrStreamingBoundaryTest::class.java,
            SettingsValidationTest::class.java,
            ProviderAccessTest::class.java
        )
        val report = Bundle().apply {
            putInt("tests", result.runCount)
            putInt("failures", result.failureCount)
            putString("stream", result.failures.joinToString("\n") { it.trace }.ifEmpty {
                "Passed ${result.runCount} audio regression tests on ${android.os.Build.SUPPORTED_ABIS.first()}"
            })
        }
        Log.i("SingRegression", report.getString("stream") ?: "")
        finish(if (result.wasSuccessful()) Activity.RESULT_OK else Activity.RESULT_CANCELED, report)
    }
}
