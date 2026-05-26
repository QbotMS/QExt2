package com.qext2.primary.field

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.qbot.karoo.core.FieldStatus

class StatsAdvancedFieldPolicyTest {

    @Test
    fun avgAbove80IsRejected() {
        val decision = StatsAdvancedFieldPolicy.sanitizeAvg(92.4)
        assertEquals("WAIT", decision.value)
        assertEquals(FieldStatus.NO_MODEL, decision.status)
        assertEquals("avg_unrealistic", decision.reason)
    }

    @Test
    fun tssWithoutModelIsWaitWithReason() {
        val decision = StatsAdvancedFieldPolicy.sdkTss(0f)
        assertEquals("WAIT", decision.value)
        assertEquals(FieldStatus.NO_DATA, decision.status)
        assertEquals("sdk_field_not_available", decision.reason)
    }

    @Test
    fun tssFromSdkIsShownWhenAvailable() {
        val decision = StatsAdvancedFieldPolicy.sdkTss(137.8f)
        assertEquals("137", decision.value)
        assertEquals(FieldStatus.OK, decision.status)
        assertEquals("sdk_training_stress_score", decision.reason)
    }

    @Test
    fun wprimeNoCpReturnsWaitModel() {
        val d = StatsAdvancedFieldPolicy.localWPrime(false, -1)
        assertEquals("WAIT", d.value)
        assertEquals(FieldStatus.NO_MODEL, d.status)
        assertEquals("wprime_no_cp_or_wprime", d.reason)
    }

    @Test
    fun wprimePowerAboveCpDepletes() {
        val d = StatsAdvancedFieldPolicy.localWPrime(true, 65)
        assertEquals("65%", d.value)
        assertEquals(FieldStatus.OK, d.status)
        assertEquals("local_wprime_balance", d.reason)
    }

    @Test
    fun wprimePowerBelowCpRecovers() {
        val d = StatsAdvancedFieldPolicy.localWPrime(true, 95)
        assertEquals("95%", d.value)
        assertEquals(FieldStatus.OK, d.status)
    }

    @Test
    fun etaNoRouteReturnsWait() {
        val d = StatsAdvancedFieldPolicy.localEta(false, false, 0L)
        assertEquals("WAIT", d.value)
        assertEquals(FieldStatus.NO_DATA, d.status)
        assertEquals("eta_no_route", d.reason)
    }

    @Test
    fun etaRouteNoMotionReturnsWaitModel() {
        val d = StatsAdvancedFieldPolicy.localEta(true, false, 0L)
        assertEquals("WAIT", d.value)
        assertEquals(FieldStatus.NO_MODEL, d.status)
        assertEquals("eta_model_not_ready", d.reason)
    }

    @Test
    fun etaRouteWithDistanceAndSpeedShowsTime() {
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = now + 3600_000L
        val d = StatsAdvancedFieldPolicy.localEta(true, true, cal.timeInMillis)
        assertEquals(FieldStatus.OK, d.status)
        assertEquals("local_eta_prediction", d.reason)
        assertEquals("local_model", d.source)
        assertTrue(d.value.matches(Regex("\\d{2}:\\d{2}")))
    }

    @Test
    fun rsrvNoModelReturnsWait() {
        val d = StatsAdvancedFieldPolicy.localRsrv(false, 100)
        assertEquals("WAIT", d.value)
        assertEquals(FieldStatus.NO_MODEL, d.status)
        assertEquals("rsrv_model_not_ready", d.reason)
    }

    @Test
    fun rsrvShowsPercentWhenReady() {
        val d = StatsAdvancedFieldPolicy.localRsrv(true, 72)
        assertEquals("72%", d.value)
        assertEquals(FieldStatus.OK, d.status)
        assertEquals("local_reserve_estimate", d.reason)
    }

    @Test
    fun localModelGuardsNaNInfinityAndNegative() {
        val wpNeg = StatsAdvancedFieldPolicy.localWPrime(true, -1)
        val wpOver100 = StatsAdvancedFieldPolicy.localWPrime(true, 101)
        val rsrvNeg = StatsAdvancedFieldPolicy.localRsrv(true, -1)
        val rsrvOver = StatsAdvancedFieldPolicy.localRsrv(true, 101)
        assertEquals(FieldStatus.NO_MODEL, wpNeg.status)
        assertEquals(FieldStatus.NO_MODEL, wpOver100.status)
        assertEquals(FieldStatus.NO_MODEL, rsrvNeg.status)
        assertEquals(FieldStatus.NO_MODEL, rsrvOver.status)
    }

    @Test
    fun carbNoModelReturnsWait() {
        val d = StatsAdvancedFieldPolicy.localCarb(false, 0)
        assertEquals("WAIT", d.value)
        assertEquals(FieldStatus.NO_MODEL, d.status)
        assertEquals("carb_model_not_ready", d.reason)
    }

    @Test
    fun carbPowerReadyShowsValue() {
        val d = StatsAdvancedFieldPolicy.localCarb(true, 65)
        assertEquals("65g/h", d.value)
        assertEquals(FieldStatus.OK, d.status)
        assertEquals("local_carb_estimate", d.reason)
        assertEquals("local_model", d.source)
    }

    @Test
    fun carbGuardNanInfinityNegative() {
        val neg = StatsAdvancedFieldPolicy.localCarb(true, -5)
        assertEquals(FieldStatus.NO_MODEL, neg.status)
        val over = StatsAdvancedFieldPolicy.localCarb(true, 300)
        assertEquals(FieldStatus.NO_MODEL, over.status)
    }

    @Test
    fun carbBalanceNoIntakeShowsZero() {
        val d = StatsAdvancedFieldPolicy.localCarbBalance(true, 0)
        assertEquals("0g", d.value)
        assertEquals(FieldStatus.OK, d.status)
    }

    @Test
    fun carbBalanceReadyShowsBalance() {
        val pos = StatsAdvancedFieldPolicy.localCarbBalance(true, 42)
        assertEquals("+42g", pos.value)
        val neg = StatsAdvancedFieldPolicy.localCarbBalance(true, -30)
        assertEquals("-30g", neg.value)
        assertEquals(FieldStatus.OK, pos.status)
        assertEquals(FieldStatus.OK, neg.status)
    }

    @Test
    fun fluidNoConfigReturnsWait() {
        val d = StatsAdvancedFieldPolicy.localFluid(false, 0f)
        assertEquals("WAIT", d.value)
        assertEquals(FieldStatus.NO_MODEL, d.status)
        assertEquals("fluid_model_not_ready", d.reason)
    }

    @Test
    fun fluidReadyShowsValue() {
        val d = StatsAdvancedFieldPolicy.localFluid(true, 0.75f)
        assertEquals("0.8L/h", d.value)
        assertEquals(FieldStatus.OK, d.status)
        assertEquals("local_fluid_estimate", d.reason)
    }

    @Test
    fun fluidGuardNanInfinityNegative() {
        val nan = StatsAdvancedFieldPolicy.localFluid(true, Float.NaN)
        val inf = StatsAdvancedFieldPolicy.localFluid(true, Float.POSITIVE_INFINITY)
        val neg = StatsAdvancedFieldPolicy.localFluid(true, -0.1f)
        assertEquals(FieldStatus.NO_MODEL, nan.status)
        assertEquals(FieldStatus.NO_MODEL, inf.status)
        assertEquals(FieldStatus.NO_MODEL, neg.status)
    }

    @Test
    fun wprimeDefaultValuesNotOkWithoutConfirmedSource() {
        val d = StatsAdvancedFieldPolicy.localWPrime(false, -1)
        assertEquals("WAIT", d.value)
        assertEquals(FieldStatus.NO_MODEL, d.status)
        assertEquals("wprime_no_cp_or_wprime", d.reason)
    }

    @Test
    fun wprimeNoAthleteDataIsWaitNoModel() {
        val d = StatsAdvancedFieldPolicy.localWPrime(false, 0)
        assertEquals("WAIT", d.value)
        assertEquals(FieldStatus.NO_MODEL, d.status)
    }

    @Test
    fun batteryWithoutSourceIsNoDataOrWait() {
        val decision = StatsAdvancedFieldPolicy.waitNoData("battery_source_not_connected")
        assertEquals("WAIT", decision.value)
        assertEquals(FieldStatus.NO_DATA, decision.status)
        assertEquals("battery_source_not_connected", decision.reason)
    }

    @Test
    fun noBatterySourceKeepsWaitNoData() {
        val drain = StatsAdvancedFieldPolicy.batteryDrain(
            batterySourceReady = false,
            batteryDrainReady = false,
            dropPctPerHour = 5.2f,
            batterySource = null,
        )
        val left = StatsAdvancedFieldPolicy.batteryLeft(
            batterySourceReady = false,
            batteryEstimateReady = false,
            leftSec = 7200L,
            batterySource = null,
        )
        assertEquals("WAIT", drain.value)
        assertEquals(FieldStatus.NO_DATA, drain.status)
        assertEquals("battery_source_not_connected", drain.reason)
        assertEquals("WAIT", left.value)
        assertEquals(FieldStatus.NO_DATA, left.status)
        assertEquals("battery_source_not_connected", left.reason)
    }

    @Test
    fun batteryDrainReadyShowsValue() {
        val drain = StatsAdvancedFieldPolicy.batteryDrain(
            batterySourceReady = true,
            batteryDrainReady = true,
            dropPctPerHour = 6.5f,
            batterySource = "headunit_polling",
        )
        assertEquals(FieldStatus.OK, drain.status)
        assertEquals("6.5", drain.value)
        assertEquals("headunit_polling", drain.source)
    }

    @Test
    fun batteryTimeLeftReadyShowsValue() {
        val left = StatsAdvancedFieldPolicy.batteryLeft(
            batterySourceReady = true,
            batteryEstimateReady = true,
            leftSec = 5 * 3600L + 15 * 60L,
            batterySource = "headunit_polling",
        )
        assertEquals(FieldStatus.OK, left.status)
        assertEquals("5:15", left.value)
        assertEquals("headunit_polling", left.source)
    }

    @Test
    fun batteryPctOnlyIsNoModelNotReady() {
        val drain = StatsAdvancedFieldPolicy.batteryDrain(
            batterySourceReady = true,
            batteryDrainReady = false,
            dropPctPerHour = null,
            batterySource = "headunit_polling",
        )
        val left = StatsAdvancedFieldPolicy.batteryLeft(
            batterySourceReady = true,
            batteryEstimateReady = false,
            leftSec = null,
            batterySource = "headunit_polling",
        )
        assertEquals(FieldStatus.OK, drain.status)
        assertEquals("—", drain.value)
        assertEquals("battery_tracking_not_enough_data", drain.reason)
        assertEquals(FieldStatus.OK, left.status)
        assertEquals("—", left.value)
        assertEquals("battery_tracking_not_enough_data", left.reason)
    }

    @Test
    fun routeLoadedNoMotionDoesNotFakeUpLeft() {
        val up = StatsAdvancedFieldPolicy.ascentDone(hasRoute = true, routeClimbSourceReady = true, ascentDoneM = 0, ascentLeftM = 0)
        val left = StatsAdvancedFieldPolicy.ascentLeft(hasRoute = true, routeClimbSourceReady = true, ascentDoneM = 0, ascentLeftM = 0)
        assertEquals("0", up.value)
        assertEquals(FieldStatus.OK, up.status)
        assertEquals("flat_route_or_zero_ascent", up.reason)
        assertEquals("0", left.value)
        assertEquals(FieldStatus.OK, left.status)
        assertEquals("flat_route_or_zero_ascent", left.reason)
    }

    @Test
    fun routeWithClimbDataShowsRouteValuesOnly() {
        val up = StatsAdvancedFieldPolicy.ascentDone(hasRoute = true, routeClimbSourceReady = true, ascentDoneM = 320, ascentLeftM = 1180)
        val left = StatsAdvancedFieldPolicy.ascentLeft(hasRoute = true, routeClimbSourceReady = true, ascentDoneM = 320, ascentLeftM = 1180)
        assertEquals(FieldStatus.OK, up.status)
        assertEquals("320", up.value)
        assertEquals("route_loaded_with_climb_data", up.reason)
        assertEquals(FieldStatus.OK, left.status)
        assertEquals("1180", left.value)
        assertEquals("route_loaded_with_climb_data", left.reason)
    }

    @Test
    fun noRouteReturnsWaitNoDataForUpLeft() {
        val up = StatsAdvancedFieldPolicy.ascentDone(hasRoute = false, routeClimbSourceReady = false, ascentDoneM = 0, ascentLeftM = 0)
        val left = StatsAdvancedFieldPolicy.ascentLeft(hasRoute = false, routeClimbSourceReady = false, ascentDoneM = 0, ascentLeftM = 0)
        assertEquals("WAIT", up.value)
        assertEquals(FieldStatus.NO_DATA, up.status)
        assertEquals("route_not_loaded", up.reason)
        assertEquals("WAIT", left.value)
        assertEquals(FieldStatus.NO_DATA, left.status)
        assertEquals("route_not_loaded", left.reason)
    }

    @Test
    fun routeLoadedMissingClimbSourceReturnsNoModel() {
        val up = StatsAdvancedFieldPolicy.ascentDone(hasRoute = true, routeClimbSourceReady = false, ascentDoneM = 0, ascentLeftM = 0)
        val left = StatsAdvancedFieldPolicy.ascentLeft(hasRoute = true, routeClimbSourceReady = false, ascentDoneM = 0, ascentLeftM = 0)
        assertEquals("WAIT", up.value)
        assertEquals(FieldStatus.NO_MODEL, up.status)
        assertEquals("route_climb_model_not_ready", up.reason)
        assertEquals("WAIT", left.value)
        assertEquals(FieldStatus.NO_MODEL, left.status)
        assertEquals("route_climb_model_not_ready", left.reason)
    }

    @Test
    fun noNaNOrInfinityForBatteryDecisions() {
        val nan = StatsAdvancedFieldPolicy.batteryDrain(true, true, Float.NaN, "headunit_polling")
        val inf = StatsAdvancedFieldPolicy.batteryDrain(true, true, Float.POSITIVE_INFINITY, "headunit_polling")
        assertEquals("—", nan.value)
        assertEquals(FieldStatus.OK, nan.status)
        assertEquals("battery_tracking_not_enough_data", nan.reason)
        assertEquals("—", inf.value)
        assertEquals(FieldStatus.OK, inf.status)
        assertEquals("battery_tracking_not_enough_data", inf.reason)
    }

    @Test
    fun batteryNegativeValuesAreNotShown() {
        val drain = StatsAdvancedFieldPolicy.batteryDrain(true, true, -2.0f, "headunit_polling")
        val left = StatsAdvancedFieldPolicy.batteryLeft(true, true, -120L, "headunit_polling")
        assertEquals("—", drain.value)
        assertEquals(FieldStatus.OK, drain.status)
        assertEquals("battery_tracking_not_enough_data", drain.reason)
        assertEquals("—", left.value)
        assertEquals(FieldStatus.OK, left.status)
        assertEquals("battery_tracking_not_enough_data", left.reason)
    }

    @Test
    fun staticNoMotionWithoutSourceDoesNotFakeBattery() {
        val drain = StatsAdvancedFieldPolicy.batteryDrain(false, false, null, null)
        val left = StatsAdvancedFieldPolicy.batteryLeft(false, false, null, null)
        assertEquals("WAIT", drain.value)
        assertEquals(FieldStatus.NO_DATA, drain.status)
        assertEquals("WAIT", left.value)
        assertEquals(FieldStatus.NO_DATA, left.status)
    }

    @Test
    fun caloriesFromSdkShownOnlyWhenAvailable() {
        val missing = StatsAdvancedFieldPolicy.sdkCalories(0)
        val ok = StatsAdvancedFieldPolicy.sdkCalories(654)
        assertEquals("WAIT", missing.value)
        assertEquals(FieldStatus.NO_DATA, missing.status)
        assertEquals("654", ok.value)
        assertEquals(FieldStatus.OK, ok.status)
    }

    @Test
    fun wprimeNotActivatedByNonJsonContent() {
        val d = StatsAdvancedFieldPolicy.localWPrime(false, -1)
        assertEquals("WAIT", d.value)
        assertEquals(FieldStatus.NO_MODEL, d.status)
        assertEquals("wprime_no_cp_or_wprime", d.reason)
    }

    @Test
    fun etaDoesNotDependOnDeadlineOrSunset() {
        val etaWithRoute = StatsAdvancedFieldPolicy.localEta(true, true, System.currentTimeMillis() + 3600_000L)
        assertEquals(FieldStatus.OK, etaWithRoute.status)
        assertEquals("local_eta_prediction", etaWithRoute.reason)

        val etaNoRoute = StatsAdvancedFieldPolicy.localEta(false, false, 0L)
        assertEquals(FieldStatus.NO_DATA, etaNoRoute.status)
        assertEquals("eta_no_route", etaNoRoute.reason)
    }

    @Test
    fun avgGrossNoDistanceIsNoData() {
        val d = StatsAdvancedFieldPolicy.localAvgGross(0f, 3600L)
        assertEquals("WAIT", d.value)
        assertEquals(FieldStatus.NO_DATA, d.status)
    }

    @Test
    fun avgGrossWithDistanceShowsValue() {
        val d = StatsAdvancedFieldPolicy.localAvgGross(42.5f, 5400L)
        assertEquals(FieldStatus.OK, d.status)
        assertEquals("local_model", d.source)
    }

    @Test
    fun avgGrossAbove80IsRejected() {
        val d = StatsAdvancedFieldPolicy.localAvgGross(200f, 3600L)
        assertEquals(FieldStatus.NO_MODEL, d.status)
    }
}
