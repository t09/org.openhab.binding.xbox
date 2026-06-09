package org.openhab.binding.xbox.internal;

import static org.openhab.binding.xbox.internal.XboxBindingConstants.THING_TYPE_XBOX;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.discovery.AbstractDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.config.discovery.DiscoveryService;
import org.openhab.core.thing.ThingUID;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovers Xbox consoles via SmartGlass (UDP 5050). The console's IP and (from the discovery
 * certificate) the Live ID are stored as Thing config; wake-up later uses the SmartGlass
 * Power-On packet, so no MAC address / Wake-on-LAN is needed.
 */
@NonNullByDefault
@Component(service = DiscoveryService.class, immediate = true)
public class XboxDiscoveryService extends AbstractDiscoveryService {

    private final Logger logger = LoggerFactory.getLogger(XboxDiscoveryService.class);

    private static final int SMARTGLASS_PORT = 5050;
    private static final int TIMEOUT_MS      = 3000;

    public XboxDiscoveryService() {
        super(Collections.singleton(THING_TYPE_XBOX), 30);
    }

    @Override
    protected void startScan() {
        logger.info("Starting Xbox SmartGlass discovery scan...");
        sendToAllInterfaces();
        sendToMulticast();
    }

    // ---- Packet sending ------------------------------------------------------

    @SuppressWarnings("null")
    private void sendToAllInterfaces() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) {
                    continue;
                }
                for (InterfaceAddress ia : iface.getInterfaceAddresses()) {
                    InetAddress broadcast = ia.getBroadcast();
                    if (broadcast == null) {
                        continue;
                    }
                    try (DatagramSocket sock = new DatagramSocket()) {
                        sock.setBroadcast(true);
                        sock.setSoTimeout(TIMEOUT_MS);
                        byte[] packet = XboxBindingConstants.discoveryPacket();
                        sock.send(new DatagramPacket(packet, packet.length, broadcast, SMARTGLASS_PORT));
                        logger.debug("SmartGlass discovery sent to {}", broadcast.getHostAddress());
                        receiveResponses(sock);
                    } catch (IOException e) {
                        logger.debug("Discovery error on {}: {}", broadcast.getHostAddress(), e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            logger.error("Cannot enumerate network interfaces: {}", e.getMessage());
        }
    }

    @SuppressWarnings("null")
    private void sendToMulticast() {
        for (String target : new String[]{ "239.255.255.250", "255.255.255.255" }) {
            try (DatagramSocket sock = target.startsWith("239")
                    ? new MulticastSocket() : new DatagramSocket()) {
                sock.setBroadcast(true);
                sock.setSoTimeout(TIMEOUT_MS);
                InetAddress addr = InetAddress.getByName(target);
                byte[] packet = XboxBindingConstants.discoveryPacket();
                sock.send(new DatagramPacket(packet, packet.length, addr, SMARTGLASS_PORT));
                logger.debug("SmartGlass discovery sent to {}", target);
                receiveResponses(sock);
            } catch (IOException e) {
                logger.debug("Discovery error to {}: {}", target, e.getMessage());
            }
        }
    }

    // ---- Response handling ---------------------------------------------------

    private void receiveResponses(DatagramSocket sock) {
        byte[] buf = new byte[2048];
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                sock.receive(pkt);
                handleResponse(pkt);
            } catch (SocketTimeoutException e) {
                break;
            } catch (IOException e) {
                logger.warn("Error receiving discovery response: {}", e.getMessage());
            }
        }
    }

    @SuppressWarnings("null")
    private void handleResponse(DatagramPacket pkt) {
        byte[] data = pkt.getData();
        int    len  = pkt.getLength();
        String ip   = pkt.getAddress().getHostAddress();

        if (len < 8 || data[0] != (byte) 0xDD || data[1] != (byte) 0x01) {
            logger.debug("Ignoring non-discovery response from {}", ip);
            return;
        }

        logger.debug("SmartGlass Discovery Response from {}", ip);

        // Diagnose: vollstaendige Antwort als Hex loggen, um die exakte Position der
        // Live ID / des Zertifikats im Paket zu bestimmen (Live ID steckt im Cert der Antwort).
        if (logger.isDebugEnabled()) {
            StringBuilder hex = new StringBuilder(len * 2);
            for (int i = 0; i < len; i++) {
                hex.append(String.format("%02X", data[i] & 0xFF));
            }
            logger.debug("Raw discovery response ({} bytes) from {}: {}", len, ip, hex);
        }

        @Nullable String consoleName = parseConsoleName(data, len);
        @Nullable String uuid        = consoleName != null ? parseUUID(data, len) : null;

        String thingId = (uuid != null && !uuid.isEmpty())
                ? uuid.replace("-", "") : ip.replace(".", "");
        // Kurzes Label: Konsolenname (z. B. "XBOX-SERIES-X") + IP, OHNE "Xbox Console"-Prefix.
        // MainUI bildet den vorgeschlagenen Item-Namen aus Thing-Label + Channel-Label – ohne den
        // Prefix werden die Vorschlaege kuerzer (kein "Xbox_Console_...").
        String label   = (consoleName != null && !consoleName.isBlank() ? consoleName : "Xbox")
                       + " (" + ip + ")";

        // Live ID stammt aus dem X509-Zertifikat der Discovery-Antwort (Subject CN), NICHT
        // aus der UUID. Sie wird fuer das Power-On-Paket (Fern-Einschalten) benoetigt.
        @Nullable String liveId = LiveIdUtil.parseLiveId(data, len);

        Map<String, Object> props = new HashMap<>();
        props.put(XboxBindingConstants.CONFIG_HOST, ip);
        if (liveId != null && !liveId.isEmpty()) {
            props.put(XboxBindingConstants.CONFIG_LIVE_ID, liveId);
            logger.info("Resolved Xbox Live ID from certificate: {}", liveId);
        } else {
            logger.debug("Could not parse Live ID from discovery certificate for {}", ip);
        }
        logger.info("Discovered Xbox: {} at {}", label, ip);

        ThingUID thingUID = new ThingUID(THING_TYPE_XBOX, thingId);
        DiscoveryResult result = DiscoveryResultBuilder.create(thingUID)
                .withLabel(label)
                .withProperties(props)
                .build();
        thingDiscovered(result);
    }

    // ---- SGString / Discovery Response parsing -------------------------------

    /**
     * Parses ConsoleName from the Discovery Response payload.
     *
     * Response layout after 6-byte header:
     *   +0  uint32 Primary Device Flags
     *   +4  uint16 Device Type
     *   +6  SGString ConsoleName  (uint16 byteCount + byteCount bytes UTF-8)
     */
    private @Nullable String parseConsoleName(byte[] data, int len) {
        int offset = 6 + 4 + 2; // header + flags + type
        return readSGString(data, offset, len);
    }

    /** Parses the UUID that follows ConsoleName in the response. */
    private @Nullable String parseUUID(byte[] data, int len) {
        int nameEnd = sgStringEnd(data, 6 + 4 + 2, len);
        if (nameEnd < 0) {
            return null;
        }
        // Each SGString is followed by a single 0x00 terminator byte that is not part of the
        // declared length – skip it before reading the next string, otherwise the UUID is
        // read one byte off and comes back garbled/empty.
        return readSGString(data, nameEnd + 1, len);
    }

    /**
     * SGString: uint16 byteCount (big-endian) + byteCount bytes of UTF-8.
     * NOT UTF-16LE — Xbox sends plain ASCII/UTF-8 console names.
     */
    private @Nullable String readSGString(byte[] data, int offset, int len) {
        if (offset + 2 > len) {
            return null;
        }
        int byteCount = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
        int start = offset + 2;
        if (byteCount <= 0 || start + byteCount > len) {
            return null;
        }
        return new String(data, start, byteCount, StandardCharsets.UTF_8).trim();
    }

    private int sgStringEnd(byte[] data, int offset, int len) {
        if (offset + 2 > len) {
            return -1;
        }
        int byteCount = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
        int end = offset + 2 + byteCount;
        return (end <= len) ? end : -1;
    }
}
