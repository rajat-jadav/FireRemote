package com.rajat.fireremote.tv

import java.net.Inet4Address
import java.net.NetworkInterface

object Net {
    /** First non-loopback IPv4 address of this device, or null when offline. */
    fun localIp(): String? {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
        for (ni in interfaces) {
            if (!ni.isUp || ni.isLoopback) continue
            for (addr in ni.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    return addr.hostAddress
                }
            }
        }
        return null
    }
}
