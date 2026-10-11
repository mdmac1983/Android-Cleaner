package io.github.mdmac1983.leancleaner.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkTrafficTest {

    private fun counters(totalRx: Long, totalTx: Long, mobileRx: Long = 0, mobileTx: Long = 0, at: Long) =
        TrafficCounters(totalRx, totalTx, mobileRx, mobileTx, at)

    @Test
    fun `wifi and mobile speeds are separated`() {
        val before = counters(totalRx = 10_000, totalTx = 5_000, mobileRx = 4_000, mobileTx = 1_000, at = 1_000)
        // 2 s later: 3 MB more down (1 MB of it mobile), 400 kB more up (100 kB mobile).
        val after = counters(totalRx = 3_010_000, totalTx = 405_000, mobileRx = 1_004_000, mobileTx = 101_000, at = 3_000)
        assertEquals(
            TrafficRates(wifiDown = 1_000_000, wifiUp = 150_000, mobileDown = 500_000, mobileUp = 50_000),
            NetworkTraffic.rates(before, after),
        )
    }

    @Test
    fun `a mobile counter reset is not reported as wifi traffic`() {
        val before = counters(totalRx = 50_000_000, totalTx = 9_000_000, mobileRx = 40_000_000, mobileTx = 8_000_000, at = 0)
        val after = counters(totalRx = 50_100_000, totalTx = 9_000_000, mobileRx = 0, mobileTx = 0, at = 1_000)
        assertEquals(TrafficRates(wifiDown = 100_000, wifiUp = 0, mobileDown = 0, mobileUp = 0), NetworkTraffic.rates(before, after))
    }

    @Test
    fun `counters going backwards give zero, not negative speeds`() {
        val before = counters(totalRx = 1_000, totalTx = 1_000, at = 0)
        val after = counters(totalRx = 500, totalTx = 200, at = 1_000)
        assertEquals(TrafficRates(0, 0, 0, 0), NetworkTraffic.rates(before, after))
    }

    @Test
    fun `no speed without elapsed time`() {
        val c = counters(totalRx = 1, totalTx = 1, at = 5)
        assertNull(NetworkTraffic.rates(c, c))
    }

    @Test
    fun `meter reports speeds from the second sample on`() {
        val readings = ArrayDeque(listOf(counters(0, 0, at = 0), counters(2_000, 1_000, at = 500)))
        val meter = TrafficMeter { readings.removeFirstOrNull() }
        assertNull(meter.sample()!!.rates)
        assertEquals(TrafficRates(4_000, 2_000, 0, 0), meter.sample()!!.rates)
        assertNull(meter.sample())
    }
}
