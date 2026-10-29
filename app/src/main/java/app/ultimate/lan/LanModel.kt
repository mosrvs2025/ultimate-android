package app.ultimate.lan

/** A port worth checking on a home network, what it usually means, and whether an open one is a risk. */
data class Service(val port: Int, val name: String, val meaning: String, val risk: Risk = Risk.None)

enum class Risk { None, Low, High }

enum class DeviceKind(val label: String) {
    Router("Router"), Phone("Phone or tablet"), Computer("Computer"), Tv("TV or streamer"), Speaker("Speaker"), Printer("Printer"),
    Camera("Camera"), Storage("Network storage"), SmartHome("Smart home device"), Unknown("Device"),
}

data class Device(
    val ip: String,
    val name: String? = null,
    val vendorHint: String? = null,
    val openPorts: List<Int> = emptyList(),
    /** mDNS service types (_googlecast._tcp …) and SSDP device types it announced. */
    val announces: Set<String> = emptySet(),
    val latencyMs: Long? = null,
    val isGateway: Boolean = false,
    val isThisPhone: Boolean = false,
)

data class LanFinding(val ip: String, val risk: Risk, val title: String, val detail: String)

object Lan {
    val SERVICES: List<Service> = listOf(
        Service(21, "FTP", "File transfer without encryption", Risk.High),
        Service(22, "SSH", "Remote command line (encrypted)", Risk.Low),
        Service(23, "Telnet", "Remote command line with no encryption; passwords travel in clear text", Risk.High),
        Service(53, "DNS", "Answers name lookups (normal for routers)"),
        Service(80, "HTTP", "Web page, often a settings page; unencrypted", Risk.Low),
        Service(139, "NetBIOS", "Windows file sharing (old)", Risk.Low),
        Service(443, "HTTPS", "Encrypted web page"),
        Service(445, "SMB", "Windows file sharing", Risk.Low),
        Service(515, "LPD", "Printing"),
        Service(548, "AFP", "Apple file sharing"),
        Service(554, "RTSP", "Live video stream, typical of cameras; often viewable without a password", Risk.High),
        Service(631, "IPP", "Printing"),
        Service(1883, "MQTT", "Smart-home messaging, often without a password", Risk.Low),
        Service(1900, "UPnP", "Lets devices open ports on your router automatically", Risk.Low),
        Service(3389, "RDP", "Windows remote desktop", Risk.High),
        Service(5000, "UPnP/NAS", "Device control or NAS web page"),
        Service(5353, "mDNS", "Announces itself on the network"),
        Service(5555, "ADB", "Android debugging over the network: full control of that device", Risk.High),
        Service(5900, "VNC", "Remote screen sharing, often weakly protected", Risk.High),
        Service(7000, "AirPlay", "AirPlay receiver"),
        Service(8008, "Cast", "Google Cast"),
        Service(8009, "Cast", "Google Cast"),
        Service(8080, "HTTP-alt", "Web page or admin panel; unencrypted", Risk.Low),
        Service(8443, "HTTPS-alt", "Encrypted web page or admin panel"),
        Service(9100, "Printer", "Raw printing"),
        Service(32400, "Plex", "Plex media server"),
        Service(49152, "UPnP", "UPnP device description"),
        Service(62078, "iPhone sync", "Apple device sync"),
    )
    val PORTS: List<Int> = SERVICES.map { it.port }.distinct()
    private val byPort = SERVICES.associateBy { it.port }
    fun service(port: Int): Service? = byPort[port]

    /** Hosts to probe on the /24 around [ip] (the phone's own address excluded). Bigger subnets are limited to this /24. */
    fun hosts(ip: String): List<String> {
        val parts = ip.split('.')
        require(parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) { "Not an IPv4 address: $ip" }
        val prefix = parts.take(3).joinToString(".")
        return (1..254).map { "$prefix.$it" }.filter { it != ip }
    }

    fun classify(d: Device): DeviceKind {
        val a = d.announces.joinToString(" ").lowercase()
        val n = (d.name.orEmpty() + " " + d.vendorHint.orEmpty()).lowercase()
        val p = d.openPorts.toSet()
        return when {
            d.isGateway || "internetgatewaydevice" in a || "router" in n -> DeviceKind.Router
            554 in p || "camera" in n || "_rtsp" in a -> DeviceKind.Camera
            "_ipp" in a || "_printer" in a || 9100 in p || 631 in p || "printer" in n -> DeviceKind.Printer
            "_googlecast" in a || "_airplay" in a || "mediarenderer" in a || 8008 in p || "tv" in n || "chromecast" in n || "roku" in n -> DeviceKind.Tv
            "_raop" in a || "_spotify" in a || "sonos" in n || "speaker" in n -> DeviceKind.Speaker
            "_hap" in a || "_matter" in a || 1883 in p || "hue" in n || "plug" in n -> DeviceKind.SmartHome
            "_smb" in a || "_afp" in a || 548 in p || "nas" in n || "synology" in n || 32400 in p -> DeviceKind.Storage
            62078 in p || "iphone" in n || "ipad" in n || "android" in n || "galaxy" in n || "pixel" in n || d.isThisPhone -> DeviceKind.Phone
            3389 in p || 22 in p || 445 in p || "macbook" in n || "desktop" in n || "laptop" in n -> DeviceKind.Computer
            else -> DeviceKind.Unknown
        }
    }

    fun findings(devices: List<Device>): List<LanFinding> = buildList {
        devices.filter { !it.isThisPhone }.forEach { d ->
            val label = d.name ?: d.ip
            d.openPorts.mapNotNull(::service).filter { it.risk == Risk.High }.forEach { s ->
                add(LanFinding(d.ip, Risk.High, "$label has ${s.name} open (port ${s.port})", s.meaning + ". Turn it off on that device if you don't use it."))
            }
            if (d.isGateway && (1900 in d.openPorts || d.announces.any { "internetgatewaydevice" in it.lowercase() }))
                add(LanFinding(d.ip, Risk.Low, "Router has UPnP on", "Any device on your network can open ports to the internet without asking. Consider turning UPnP off in the router settings."))
            if (d.isGateway && 80 in d.openPorts && 443 !in d.openPorts)
                add(LanFinding(d.ip, Risk.Low, "Router settings page is unencrypted", "Its admin password crosses the network in clear text. Change it from the default and only use it on your own network."))
        }
    }.sortedByDescending { it.risk.ordinal }

    /** Parses one SSDP reply into its headers (keys lowercased). */
    fun ssdpHeaders(reply: String): Map<String, String> = reply.lineSequence().drop(1).mapNotNull { line ->
        val i = line.indexOf(':'); if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
    }.toMap()

    /** The friendlyName inside a UPnP device description. */
    fun friendlyName(xml: String): String? = Regex("<friendlyName>([^<]{1,80})</friendlyName>").find(xml)?.groupValues?.get(1)?.trim()
}
