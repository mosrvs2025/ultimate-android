package app.ultimate.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanTest {
    @Test fun sweepsTheSlash24WithoutThisPhone() {
        val h = Lan.hosts("192.168.1.37")
        assertEquals(253, h.size)
        assertEquals("192.168.1.1", h.first()); assertEquals("192.168.1.254", h.last())
        assertTrue("192.168.1.37" !in h)
    }

    @Test fun classifiesFromAnnouncementsPortsAndNames() {
        assertEquals(DeviceKind.Router, Lan.classify(Device("1", isGateway = true)))
        assertEquals(DeviceKind.Tv, Lan.classify(Device("2", announces = setOf("_googlecast._tcp"))))
        assertEquals(DeviceKind.Printer, Lan.classify(Device("3", openPorts = listOf(631, 9100))))
        assertEquals(DeviceKind.Camera, Lan.classify(Device("4", openPorts = listOf(80, 554))))
        assertEquals(DeviceKind.Phone, Lan.classify(Device("5", openPorts = listOf(62078))))
        assertEquals(DeviceKind.Unknown, Lan.classify(Device("6")))
    }

    @Test fun flagsRiskyServicesAndRouterUpnpButNotThisPhone() {
        val f = Lan.findings(listOf(
            Device("10", name = "TV", openPorts = listOf(5555)),
            Device("1", isGateway = true, openPorts = listOf(80, 1900)),
            Device("9", isThisPhone = true, openPorts = listOf(23)),
        ))
        assertEquals("TV has ADB open (port 5555)", f.first().title)
        assertTrue(f.any { it.title == "Router has UPnP on" })
        assertTrue(f.any { it.title == "Router settings page is unencrypted" })
        assertTrue(f.none { it.ip == "9" })
    }

    @Test fun readsSsdpRepliesAndFriendlyNames() {
        val h = Lan.ssdpHeaders("HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.1:5000/desc.xml\r\nSERVER: Linux UPnP/1.0 MiniUPnPd\r\nST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n")
        assertEquals("http://192.168.1.1:5000/desc.xml", h["location"])
        assertTrue(h["st"]!!.contains("InternetGatewayDevice"))
        assertEquals("Living Room TV", Lan.friendlyName("<root><device><friendlyName>Living Room TV</friendlyName></device></root>"))
    }

    @Test fun mergingKeepsEverythingLearned() {
        val d = Device("5", openPorts = listOf(80)).merge(Device("5", name = "Printer", openPorts = listOf(631, 80), announces = setOf("_ipp._tcp")))
        assertEquals(listOf(80, 631), d.openPorts); assertEquals("Printer", d.name); assertEquals(setOf("_ipp._tcp"), d.announces)
    }
}
