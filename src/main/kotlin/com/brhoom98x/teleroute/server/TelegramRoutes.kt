package com.brhoom98x.teleroute.server

/** One reachable Telegram front-end: an IP of a given data centre on a given port. */
data class Route(val dc: Int, val ip: String, val port: Int) {
    val label: String get() = "$ip:$port"
}

/** Result of probing a [Route]. A negative latency means the probe failed. */
data class RouteResult(val route: Route, val latencyMs: Int) {
    val ok: Boolean get() = latencyMs >= 0
    val label: String get() = if (ok) "${route.label} - $latencyMs ms" else "${route.label} - timeout"
}

/**
 * Telegram's published front-end addresses.
 *
 * Every IP listed under a DC number terminates on that same data centre, so swapping between
 * them (or between ports) keeps the MTProto session valid. Swapping *across* DCs would not --
 * each DC holds its own auth key -- so [dcFor] is what keeps route substitution safe.
 */
object TelegramRoutes {

    /** Ports Telegram front-ends accept. Some networks throttle 443 but leave 80/5222 alone. */
    val PORTS = listOf(443, 80, 5222)

    private val DC_IPS: Map<Int, List<String>> = mapOf(
        1 to listOf("149.154.175.50", "149.154.175.53"),
        2 to listOf("149.154.167.50", "149.154.167.51"),
        3 to listOf("149.154.175.100", "149.154.175.103"),
        4 to listOf("149.154.167.90", "149.154.167.91", "149.154.167.92"),
        5 to listOf("91.108.56.100", "91.108.56.130", "149.154.171.5")
    )

    /** Every candidate the scanner probes. */
    val ALL: List<Route> = DC_IPS.entries
        .flatMap { (dc, ips) -> ips.flatMap { ip -> PORTS.map { port -> Route(dc, ip, port) } } }

    private val IP_TO_DC: Map<String, Int> =
        DC_IPS.entries.flatMap { (dc, ips) -> ips.map { it to dc } }.toMap()

    /** /24 of a known DC IP -> that DC, used for front-ends we did not hard-code. */
    private val PREFIX_TO_DC: Map<String, Int> =
        IP_TO_DC.entries.associate { (ip, dc) -> ip.substringBeforeLast('.') to dc }

    private val TELEGRAM_NETS: List<Pair<Long, Long>> = listOf(
        "149.154.160.0/20",
        "91.108.4.0/22",
        "91.108.8.0/22",
        "91.108.12.0/22",
        "91.108.16.0/22",
        "91.108.20.0/22",
        "91.108.56.0/22",
        "91.105.192.0/23",
        "95.161.64.0/20",
        "185.76.151.0/24"
    ).map { cidr ->
        val (net, bits) = cidr.split("/")
        val mask = if (bits.toInt() == 0) 0L else (0xFFFFFFFFL shl (32 - bits.toInt())) and 0xFFFFFFFFL
        (ipToLong(net)!! and mask) to mask
    }

    /** The DC a destination belongs to, or null when we cannot place it confidently. */
    fun dcFor(ip: String): Int? {
        IP_TO_DC[ip]?.let { return it }
        if (!isTelegram(ip)) return null
        return PREFIX_TO_DC[ip.substringBeforeLast('.')]
    }

    /** True when the literal IPv4 address belongs to a Telegram network. */
    fun isTelegram(ip: String): Boolean {
        val value = ipToLong(ip) ?: return false
        return TELEGRAM_NETS.any { (net, mask) -> (value and mask) == net }
    }

    fun isIpv4(host: String): Boolean = ipToLong(host) != null

    private fun ipToLong(ip: String): Long? {
        val parts = ip.split(".")
        if (parts.size != 4) return null
        var result = 0L
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            result = (result shl 8) or octet.toLong()
        }
        return result
    }
}
