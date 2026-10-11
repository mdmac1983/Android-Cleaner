package io.github.mdmac1983.leancleaner.monitor

import android.net.TrafficStats
import android.os.SystemClock

/** Byte counters since boot, as the kernel reports them, taken at [elapsedMs] (elapsed realtime). */
data class TrafficCounters(
    val totalRx: Long,
    val totalTx: Long,
    val mobileRx: Long,
    val mobileTx: Long,
    val elapsedMs: Long,
) {
    /** Everything that isn't mobile data: Wi-Fi, plus Ethernet and tethering where present. */
    val wifiRx: Long get() = (totalRx - mobileRx).coerceAtLeast(0)
    val wifiTx: Long get() = (totalTx - mobileTx).coerceAtLeast(0)
}

/** Transfer speeds in bytes per second. */
data class TrafficRates(val wifiDown: Long, val wifiUp: Long, val mobileDown: Long, val mobileUp: Long)

data class TrafficReading(val counters: TrafficCounters, val rates: TrafficRates?)

object NetworkTraffic {

    /** Current counters, or null if this device doesn't expose traffic statistics. Needs no permission. */
    fun read(): TrafficCounters? {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        if (rx < 0 || tx < 0) return null // TrafficStats.UNSUPPORTED
        return TrafficCounters(
            totalRx = rx,
            totalTx = tx,
            // UNSUPPORTED (-1) when there's no mobile interface at all.
            mobileRx = TrafficStats.getMobileRxBytes().coerceAtLeast(0),
            mobileTx = TrafficStats.getMobileTxBytes().coerceAtLeast(0),
            elapsedMs = SystemClock.elapsedRealtime(),
        )
    }

    /**
     * Speeds between two readings. Each counter is differenced on its own, so a mobile counter that
     * resets when the mobile interface goes down can't show up as a burst of Wi-Fi traffic.
     */
    fun rates(before: TrafficCounters, after: TrafficCounters): TrafficRates? {
        val ms = after.elapsedMs - before.elapsedMs
        if (ms <= 0) return null
        fun delta(a: Long, b: Long) = (b - a).coerceAtLeast(0)
        val down = delta(before.totalRx, after.totalRx)
        val up = delta(before.totalTx, after.totalTx)
        val mobileDown = delta(before.mobileRx, after.mobileRx).coerceAtMost(down)
        val mobileUp = delta(before.mobileTx, after.mobileTx).coerceAtMost(up)
        fun perSecond(bytes: Long) = bytes * 1000 / ms
        return TrafficRates(
            wifiDown = perSecond(down - mobileDown),
            wifiUp = perSecond(up - mobileUp),
            mobileDown = perSecond(mobileDown),
            mobileUp = perSecond(mobileUp),
        )
    }
}

/** Remembers the previous reading, so each [sample] returns the speeds since the last call. */
class TrafficMeter(private val read: () -> TrafficCounters? = NetworkTraffic::read) {
    private var last: TrafficCounters? = null

    @Synchronized
    fun sample(): TrafficReading? {
        val now = read() ?: return null
        val rates = last?.let { NetworkTraffic.rates(it, now) }
        last = now
        return TrafficReading(now, rates)
    }
}
