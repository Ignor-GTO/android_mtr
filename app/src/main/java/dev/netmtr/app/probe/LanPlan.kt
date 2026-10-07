package dev.netmtr.app.probe

data class LanDevice(
    val address: String,
    val mac: String?,
    val name: String?,
    val rttMs: Double?,
    val gateway: Boolean,
)

data class LanSurvey(
    val localAddress: String?,
    val prefix: Int?,
    val scanned: Int,
    val planned: Int,
    val devices: List<LanDevice>,
    val note: String?,
    val error: String?,
) {
    companion object {
        fun failed(message: String) = LanSurvey(
            localAddress = null,
            prefix = null,
            scanned = 0,
            planned = 0,
            devices = emptyList(),
            note = null,
            error = message,
        )
    }
}

object LanPlan {
    private val macPattern = Regex("""(?i)[0-9a-f]{2}(?::[0-9a-f]{2}){5}""")

    fun targets(localIp: String, prefixLength: Int): List<String> {
        val local = ipv4(localIp) ?: return emptyList()
        val bits = when {
            prefixLength < 24 -> 24
            prefixLength > 30 -> return emptyList()
            else -> prefixLength
        }
        val mask = if (bits == 0) 0L else (-1L shl (32 - bits)) and 0xFFFF_FFFFL
        val network = local and mask
        val broadcast = network or mask.inv() and 0xFFFF_FFFFL
        val hosts = ArrayList<String>((1 shl (32 - bits)) - 2)
        var cursor = network + 1
        while (cursor < broadcast && hosts.size < 254) {
            if (cursor != local) hosts += format(cursor)
            cursor++
        }
        return hosts
    }

    fun narrowedTo24(prefixLength: Int): Boolean = prefixLength in 1..23

    fun parseArp(text: String): Map<String, String> {
        val table = linkedMapOf<String, String>()
        text.lineSequence().drop(1).forEach { line ->
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.size < 4) return@forEach
            val address = fields[0]
            if (ipv4(address) == null) return@forEach
            val mac = fields[3].lowercase()
            if (!macPattern.matches(mac) || mac == "00:00:00:00:00:00") return@forEach
            table[address] = mac
        }
        return table
    }

    fun keepRealDevices(devices: List<LanDevice>, gateway: String?): List<LanDevice> {
        val macCount = devices.mapNotNull { device -> device.mac?.lowercase() }.groupingBy { it }.eachCount()
        return devices.filter { device ->
            val mac = device.mac?.lowercase()
            if (mac == null) return@filter device.rttMs != null
            val shared = (macCount[mac] ?: 1) > 1
            if (!shared) return@filter true
            device.gateway || device.address == gateway
        }
    }

    fun compareAddresses(left: String, right: String): Int {
        val a = ipv4(left) ?: Long.MAX_VALUE
        val b = ipv4(right) ?: Long.MAX_VALUE
        return a.compareTo(b)
    }

    private fun ipv4(address: String): Long? {
        val parts = address.split('.')
        if (parts.size != 4) return null
        var value = 0L
        for (part in parts) {
            val number = part.toIntOrNull() ?: return null
            if (number !in 0..255) return null
            value = (value shl 8) or number.toLong()
        }
        return value
    }

    private fun format(value: Long): String {
        return listOf(24, 16, 8, 0).joinToString(".") { shift ->
            ((value shr shift) and 0xFF).toString()
        }
    }
}
