package dev.netmtr.app.probe

object LanIdentity {
    private val neighRe = Regex(
        """^(\d+\.\d+\.\d+\.\d+)\s+dev\s+\S+\s+lladdr\s+([0-9a-fA-F:]{17})""",
    )

    fun parseNeigh(text: String): Map<String, String> {
        val table = linkedMapOf<String, String>()
        text.lineSequence().forEach { line ->
            val match = neighRe.find(line.trim()) ?: return@forEach
            val mac = match.groupValues[2].lowercase()
            if (mac == "00:00:00:00:00:00") return@forEach
            table[match.groupValues[1]] = mac
        }
        return table
    }

    fun ptrQuery(ip: String): ByteArray {
        val parts = ip.split('.')
        if (parts.size != 4 || parts.any { it.toIntOrNull() !in 0..255 }) return byteArrayOf()
        val labels = listOf(parts[3], parts[2], parts[1], parts[0], "in-addr", "arpa")
        return dnsQuery(labels, type = 12)
    }

    fun hostnames(packet: ByteArray): List<String> {
        if (packet.size < 12) return emptyList()
        val questions = u16(packet, 4)
        val answers = u16(packet, 6)
        var offset = 12
        repeat(questions) {
            offset = skipName(packet, offset) + 4
            if (offset > packet.size) return emptyList()
        }
        val names = mutableListOf<String>()
        repeat(answers) {
            if (offset + 12 > packet.size) return names
            val (owner, afterName) = readName(packet, offset)
            offset = afterName
            if (offset + 10 > packet.size) return names
            val type = u16(packet, offset)
            val length = u16(packet, offset + 8)
            val data = offset + 10
            if (data + length > packet.size) return names
            when (type) {
                12 -> names += present(readName(packet, data).first)
                1, 16, 28, 33 -> if (owner.isNotBlank()) names += present(owner)
            }
            offset = data + length
        }
        return names.filter { it.isNotBlank() }.distinct()
    }

    fun netbiosQuery(): ByteArray {
        val encoded = ByteArray(32)
        val star = '*'.code
        encoded[0] = ('A'.code + (star shr 4)).toByte()
        encoded[1] = ('A'.code + (star and 0x0F)).toByte()
        for (i in 1 until 16) {
            encoded[i * 2] = 'C'.code.toByte()
            encoded[i * 2 + 1] = 'A'.code.toByte()
        }
        val out = ArrayList<Byte>(70)
        out += 0x80.toByte()
        out += 0
        out += 0
        out += 0
        out += 0
        out += 1
        repeat(6) { out += 0 }
        out += 0x20
        encoded.forEach { out += it }
        out += 0
        out += 0
        out += 0x21
        out += 0
        out += 1
        return out.toByteArray()
    }

    fun parseNetbios(packet: ByteArray): String? {
        if (packet.size < 70) return null
        var offset = 12
        if (offset >= packet.size) return null
        offset = skipName(packet, offset) + 4
        if (offset + 12 >= packet.size) return null
        val count = packet[offset + 10].toInt() and 0xFF
        offset += 11
        var fallback: String? = null
        for (index in 0 until count) {
            if (offset + 18 > packet.size) break
            val text = packet.copyOfRange(offset, offset + 15).toString(Charsets.ISO_8859_1).trim()
            val suffix = packet[offset + 15].toInt() and 0xFF
            if (text.isNotBlank() && text != "*") {
                if (suffix == 0) return text
                if (fallback == null) fallback = text
            }
            offset += 18
        }
        return fallback
    }

    fun vendor(mac: String?): String? {
        val key = mac?.lowercase()?.replace("-", ":")?.split(":")?.take(3)?.joinToString(":") ?: return null
        return vendors[key]
    }

    fun displayMac(mac: String?): String = mac?.uppercase() ?: "—"

    private fun dnsQuery(labels: List<String>, type: Int): ByteArray {
        val out = ArrayList<Byte>(64)
        out += 0
        out += 1
        out += 0
        out += 0
        out += 0
        out += 1
        repeat(6) { out += 0 }
        for (label in labels) {
            val bytes = label.encodeToByteArray()
            out += bytes.size.toByte()
            bytes.forEach { out += it }
        }
        out += 0
        out += (type shr 8).toByte()
        out += (type and 0xFF).toByte()
        out += 0x80.toByte()
        out += 1
        return out.toByteArray()
    }

    private fun present(name: String): String {
        return name.trim().trimEnd('.').removeSuffix(".local").trim()
    }

    private fun u16(packet: ByteArray, offset: Int): Int {
        if (offset + 1 >= packet.size) return 0
        return ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
    }

    private fun skipName(packet: ByteArray, offset: Int): Int = readName(packet, offset).second

    private fun readName(packet: ByteArray, start: Int, depth: Int = 0): Pair<String, Int> {
        if (depth > 8 || start >= packet.size) return "" to start
        val labels = mutableListOf<String>()
        var index = start
        var end = start
        var jumped = false
        while (index < packet.size) {
            val length = packet[index].toInt() and 0xFF
            if (length == 0) {
                if (!jumped) end = index + 1
                break
            }
            if (length and 0xC0 == 0xC0) {
                if (index + 1 >= packet.size) break
                val pointer = ((length and 0x3F) shl 8) or (packet[index + 1].toInt() and 0xFF)
                if (!jumped) end = index + 2
                labels += readName(packet, pointer, depth + 1).first
                jumped = true
                break
            }
            if (index + 1 + length > packet.size) break
            labels += packet.copyOfRange(index + 1, index + 1 + length).toString(Charsets.UTF_8)
            index += 1 + length
            if (!jumped) end = index
        }
        return labels.filter { it.isNotBlank() }.joinToString(".") to end
    }

    private val vendors = mapOf(
        "00:03:93" to "Apple",
        "00:17:f2" to "Apple",
        "3c:15:c2" to "Apple",
        "a4:83:e7" to "Apple",
        "f0:18:98" to "Apple",
        "00:16:6c" to "Samsung",
        "5c:0a:5b" to "Samsung",
        "8c:71:f8" to "Samsung",
        "c8:14:79" to "Samsung",
        "f0:25:b7" to "Samsung",
        "28:6c:07" to "Xiaomi",
        "34:ce:00" to "Xiaomi",
        "64:09:80" to "Xiaomi",
        "78:11:dc" to "Xiaomi",
        "9c:99:a0" to "Xiaomi",
        "f0:b4:29" to "Xiaomi",
        "3c:5a:b4" to "Google",
        "54:60:09" to "Google",
        "94:eb:2c" to "Google",
        "f4:f5:d8" to "Google",
        "00:1a:11" to "Google",
        "50:c7:bf" to "TP-Link",
        "98:da:c4" to "TP-Link",
        "c0:06:c3" to "TP-Link",
        "e8:de:27" to "TP-Link",
        "14:cc:20" to "TP-Link",
        "b8:27:eb" to "Raspberry Pi",
        "dc:a6:32" to "Raspberry Pi",
        "e4:5f:01" to "Raspberry Pi",
        "18:fe:34" to "Espressif",
        "24:0a:c4" to "Espressif",
        "30:ae:a4" to "Espressif",
        "84:f3:eb" to "Espressif",
        "a4:cf:12" to "Espressif",
        "00:0c:42" to "MikroTik",
        "08:55:31" to "MikroTik",
        "4c:5e:0c" to "MikroTik",
        "6c:3b:6b" to "MikroTik",
        "d4:ca:6d" to "MikroTik",
        "00:1f:33" to "Netgear",
        "20:e5:2a" to "Netgear",
        "9c:3d:cf" to "Netgear",
        "c0:3f:0e" to "Netgear",
        "04:d4:c4" to "ASUS",
        "08:62:66" to "ASUS",
        "1c:87:2c" to "ASUS",
        "2c:4d:54" to "ASUS",
        "10:7b:44" to "ASUS",
        "50:ff:20" to "Keenetic",
        "00:18:e7" to "Keenetic",
        "1c:7e:e5" to "D-Link",
        "28:10:7b" to "D-Link",
        "84:c9:b2" to "D-Link",
        "c8:d3:a3" to "D-Link",
        "00:e0:4c" to "Realtek",
        "00:1e:10" to "Huawei",
        "00:e0:fc" to "Huawei",
        "04:b0:e7" to "Huawei",
        "28:6e:d4" to "Huawei",
        "48:46:fb" to "Huawei",
        "70:72:3c" to "Huawei",
        "88:53:d4" to "Huawei",
        "5c:f3:70" to "Huawei",
        "00:1f:3b" to "Intel",
        "3c:a9:f4" to "Intel",
        "7c:5c:f8" to "Intel",
        "8c:8d:28" to "Intel",
        "a0:36:9f" to "Intel",
        "44:65:0d" to "Amazon",
        "50:dc:e7" to "Amazon",
        "68:37:e9" to "Amazon",
        "74:c2:46" to "Amazon",
        "fc:65:de" to "Amazon",
        "10:68:3f" to "LG",
        "58:a2:b5" to "LG",
        "a8:23:fe" to "LG",
        "00:1d:ba" to "Sony",
        "04:5d:4b" to "Sony",
        "fc:f1:52" to "Sony",
        "00:0c:29" to "VMware",
        "00:50:56" to "VMware",
        "00:17:88" to "Philips Hue",
        "58:6d:8f" to "Cisco",
        "00:1d:7e" to "Cisco",
        "00:25:84" to "Cisco",
    )
}
