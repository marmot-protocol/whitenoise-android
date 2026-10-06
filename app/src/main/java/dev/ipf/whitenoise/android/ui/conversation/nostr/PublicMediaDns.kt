package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.whitenoise.android.core.HostSafety
import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/** Validates the complete DNS answer before OkHttp chooses a connection address. */
internal class PublicMediaDns(
    private val resolver: Dns = Dns.SYSTEM,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (HostSafety.isPrivateOrLoopbackHost(hostname)) throw UnknownHostException("Unsafe media host")
        val addresses = resolver.lookup(hostname)
        if (addresses.isEmpty() || addresses.any(HostSafety::isPrivateOrLoopbackAddress)) {
            throw UnknownHostException("Unsafe media address")
        }
        return addresses
    }
}
