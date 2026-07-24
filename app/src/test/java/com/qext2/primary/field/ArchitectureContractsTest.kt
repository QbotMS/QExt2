package com.qext2.primary.field

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.qbot.karoo.core.FieldComputers
import pl.qbot.karoo.core.FieldStatus
import pl.qbot.karoo.core.RideSample
import pl.qbot.karoo.core.RideState
import java.io.File

class ArchitectureContractsTest {

    @Test
    fun noActiveNgrokUrlInBuildGradle() {
        val root = resolveProjectRoot()
        val buildFile = File(root, "app/build.gradle.kts")
        val content = buildFile.readText()
        val readinessSection = content.lines()
            .dropWhile { !it.contains("readinessUrl") }
            .take(3)
            .joinToString("\n")
        assertFalse("QEXT_READINESS_URL default must not be ngrok", readinessSection.contains("ngrok"))
        assertTrue("QEXT_READINESS_URL must use qbot.cytr.us", readinessSection.contains("qbot.cytr.us"))
    }

    @Test
    fun noActiveNgrokUrlInExtension() {
        val root = resolveProjectRoot()
        val extFiles = File(root, "app/src/main/kotlin/com/qext2/primary/QExt2PrimaryExtension.kt")
        val content = extFiles.readText()
        assertFalse("Extension must not reference ngrok", content.contains("ngrok-free.dev"))
        assertTrue("Extension must reference qbot.cytr.us in fallback", content.contains("qbot.cytr.us/ride-readiness"))
    }

    @Test
    fun hrZeroIsNotInvalid() {
        val state = RideState()
        state.update(RideSample(tSec = 1.0, hrBpm = 0.0))
        val out = FieldComputers().hr(state)
        assertEquals("HR=0 must be NO_DATA, not INVALID", FieldStatus.NO_DATA, out.status)
        assertEquals("hr_zero_or_not_ready", out.reason)
    }

    @Test
    fun wprimeNoFakeDefaults() {
        val noModel = StatsAdvancedFieldPolicy.localWPrime(false, -1)
        assertEquals("Without CP, W' must be WAIT/NO_MODEL", FieldStatus.NO_MODEL, noModel.status)

        val withModel = StatsAdvancedFieldPolicy.localWPrime(true, 72)
        assertEquals("With CP, W' must show OK", FieldStatus.OK, withModel.status)
    }

    @Test
    fun fluidStaticNoMotionIsWaitModel() {
        val noActivity = StatsAdvancedFieldPolicy.localFluid(false, 0.5f)
        assertEquals(FieldStatus.NO_MODEL, noActivity.status)

        val withActivity = StatsAdvancedFieldPolicy.localFluid(true, 0.5f)
        assertEquals(FieldStatus.OK, withActivity.status)
    }

    @Test
    fun rsrvStaticNoMotionIsWaitModel() {
        val noActivity = StatsAdvancedFieldPolicy.localRsrv(false, 100)
        assertEquals(FieldStatus.NO_MODEL, noActivity.status)

        val withActivity = StatsAdvancedFieldPolicy.localRsrv(true, 72)
        assertEquals(FieldStatus.OK, withActivity.status)
    }

    @Test
    fun etaDoesNotUseDeadlineOrSunset() {
        val etaOk = StatsAdvancedFieldPolicy.localEta(true, true, System.currentTimeMillis() + 3600_000L)
        assertEquals(FieldStatus.OK, etaOk.status)
        assertEquals("local_eta_prediction", etaOk.reason)
    }

    @Test
    fun sdkOnlyFieldsDoNotFallbackToLocalModel() {
        val kcalMissing = StatsAdvancedFieldPolicy.sdkCalories(0)
        assertEquals("KCAL without SDK must be NO_DATA", FieldStatus.NO_DATA, kcalMissing.status)
    }

    @Test
    fun localModelFieldsHaveSourceLocalModel() {
        val rsvr = StatsAdvancedFieldPolicy.localRsrv(true, 50)
        assertEquals("local_model", rsvr.source)

        val carb = StatsAdvancedFieldPolicy.localCarb(true, 65)
        assertEquals("local_model", carb.source)

        val eta = StatsAdvancedFieldPolicy.localEta(true, true, System.currentTimeMillis() + 3600_000L)
        assertEquals("local_model", eta.source)
    }

    @Test
    fun weatherDocsDoNotClaimWifiRequired() {
        val root = resolveProjectRoot()
        for (file in listOf("docs/WEATHER_IMPLEMENTATION_AUDIT.md", "docs/QEXT2_CURRENT_STATUS_2026-05-24.md")) {
            val content = File(root, file).readText()
            assertFalse("$file must not say 'bez Wi-Fi' or 'bez internetu' as requirement", content.contains("bez Wi-Fi") || content.contains("bez internetu"))
        }
    }

    @Test
    fun weatherReasonUsesNetworkNotWifi() {
        val root = resolveProjectRoot()
        val weatherDoc = File(root, "docs/WEATHER_IMPLEMENTATION_AUDIT.md").readText()
        val hasNetworkWording = weatherDoc.contains("tras") || weatherDoc.contains("sieciow") || weatherDoc.contains("Companion") || weatherDoc.contains("network")
        assertTrue("Weather doc must mention network/companion, not just Wi-Fi as requirement", hasNetworkWording)
    }

    @Test
    fun gitignoreExistsAndProtectsLocalProperties() {
        val root = resolveProjectRoot()
        val gi = File(root, ".gitignore")
        assertTrue(".gitignore must exist", gi.exists())
        assertTrue(".gitignore must contain local.properties", gi.readText().contains("local.properties"))
    }

    @Test
    fun localPropertiesExampleExists() {
        val root = resolveProjectRoot()
        val ex = File(root, "local.properties.example")
        assertTrue("local.properties.example must exist", ex.exists())
        val content = ex.readText()
        assertTrue("local.properties.example must have OPENWEATHER_API_KEY= with empty value", content.contains("OPENWEATHER_API_KEY="))
        assertFalse("local.properties.example must NOT contain a real key", content.contains("72c2801aeb8c779b59c702cc5f2fbd9c"))
    }

    @Test
    fun noFakeWprimeCpDefaultsInCode() {
        val root = resolveProjectRoot()
        val statsCalc = File(root, "app/src/main/kotlin/com/qext2/primary/engine/StatsCalculator.kt").readText()
        assertFalse("StatsCalculator must not have wPrimeKj=21.3 default", statsCalc.contains("wPrimeKj: Float = 21.3"))
        assertFalse("StatsCalculator must not have ltpWatts=192 default", statsCalc.contains("ltpWatts: Float = 192"))

        val activeDataType = File(root, "app/src/main/kotlin/com/qext2/primary/datatypes/CompositeActiveDataType.kt").readText()
        assertFalse("CompositeActiveDataType must not have wPrimeMax=3750.0 default", activeDataType.contains("wPrimeMax: Double = 375"))
        assertFalse("CompositeActiveDataType must not have ltpWatts=192.0 default", activeDataType.contains("ltpWatts: Double = 192"))

        val bpActiveStatic = File(root, "app/src/main/kotlin/com/qext2/primary/datatypes/BpActiveStaticDataType.kt").readText()
        assertFalse("BpActiveStaticDataType must not have wPrimeMax=3750.0 default", bpActiveStatic.contains("wPrimeMax: Double = 375"))
        assertFalse("BpActiveStaticDataType must not have ltpWatts=192.0 default", bpActiveStatic.contains("ltpWatts: Double = 192"))
    }

    @Test
    fun everyStatsLayoutIdHasBinding() {
        val root = resolveProjectRoot()
        val layoutText = File(root, "app/src/main/res/layout/field_stats_3x3.xml").readText()
        val bindText = File(root, "app/src/main/kotlin/com/qext2/primary/datatypes/StatsDataType.kt").readText()
        val layoutIds = Regex("id/@\\+id/(tv_[a-z0-9_]+)").findAll(layoutText).map { it.groupValues[1] }.toSet()
        val missingBindings = layoutIds.filter { id -> !bindText.contains("R.id.$id") }
        assertTrue("All STATS layout IDs must have bindings. Missing: $missingBindings", missingBindings.isEmpty())
    }

    @Test
    fun fieldPrimaryFallbackIsNotReferencedInCode() {
        val root = resolveProjectRoot()
        val codeDirs = listOf(File(root, "app/src/main/kotlin"), File(root, "app/src/main/java"))
        for (dir in codeDirs) {
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val content = file.readText()
                assertFalse("${file.name} must not reference field_primary_fallback", content.contains("field_primary_fallback"))
            }
        }
    }
    private fun resolveProjectRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return if (cwd.name == "app") cwd.parentFile else cwd
    }
}
