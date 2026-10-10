package io.github.mdmac1983.leancleaner.corpse

/**
 * Folders popular apps create directly in shared storage. A folder is only reported when none of
 * the listed packages is installed. Keep entries conservative: a wrong mapping means a false
 * "leftover", so only add folders whose owner is unambiguous.
 */
object KnownFolders {
    val ALL: List<KnownFolder> = listOf(
        KnownFolder("WhatsApp", "WhatsApp", listOf("com.whatsapp", "com.whatsapp.w4b")),
        KnownFolder("WhatsApp Business", "WhatsApp Business", listOf("com.whatsapp.w4b")),
        KnownFolder(
            "Telegram", "Telegram",
            listOf(
                "org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.messenger.beta",
                "org.telegram.plus", "org.thunderdog.challegram",
            ),
        ),
        KnownFolder("viber", "Viber", listOf("com.viber.voip")),
        KnownFolder("Signal", "Signal", listOf("org.thoughtcrime.securesms")),
        KnownFolder("tencent", "WeChat / QQ", listOf("com.tencent.mm", "com.tencent.mobileqq")),
        KnownFolder("Kik", "Kik", listOf("kik.android")),
        KnownFolder("KakaoTalk", "KakaoTalk", listOf("com.kakao.talk")),
        KnownFolder("imo", "imo", listOf("com.imo.android.imoim", "com.imo.android.imoimbeta")),
        KnownFolder("Pictures/Instagram", "Instagram", listOf("com.instagram.android")),
        KnownFolder("Movies/Instagram", "Instagram", listOf("com.instagram.android")),
        KnownFolder("Pictures/Facebook", "Facebook", listOf("com.facebook.katana", "com.facebook.lite")),
        KnownFolder("Pictures/Messenger", "Messenger", listOf("com.facebook.orca", "com.facebook.mlite")),
        KnownFolder("Pictures/Twitter", "X (Twitter)", listOf("com.twitter.android")),
        KnownFolder("zedge", "Zedge", listOf("net.zedge.android")),
        KnownFolder("UCDownloads", "UC Browser", listOf("com.UCMobile.intl", "com.uc.browser.en")),
        KnownFolder("Xender", "Xender", listOf("cn.xender")),
        KnownFolder("SHAREit", "SHAREit", listOf("com.lenovo.anyshare.gps")),
        KnownFolder("snaptube", "Snaptube", listOf("com.snaptube.premium")),
        KnownFolder("CamScanner", "CamScanner", listOf("com.intsig.camscanner")),
        KnownFolder("PicsArt", "Picsart", listOf("com.picsart.studio")),
        KnownFolder("Snapseed", "Snapseed", listOf("com.niksoftware.snapseed")),
        KnownFolder("AzRecorderFree", "AZ Screen Recorder", listOf("com.hecorat.screenrecorder.free")),
        KnownFolder("MagiskManager", "Magisk", listOf("com.topjohnwu.magisk")),
    )
}
