package org.openhab.binding.xbox.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Tests fuer {@link LiveIdUtil}: die Live ID steckt als Subject-CN im X.509-Zertifikat der
 * SmartGlass Discovery-Antwort (0xDD01).
 *
 * @author t09
 */
@NonNullByDefault
class LiveIdUtilTest {

    private static final String LIVE_ID = "FD00112233FFEE66";

    // Selbst signiertes EC-Zertifikat (keytool, secp256r1) mit Subject "CN=FD00112233FFEE66" – genau
    // so traegt eine echte Konsole ihre Live ID in der Discovery-Antwort.
    private static final String CERT_BASE64 = "MIIBUDCB9qADAgECAgkA6npyIvcVJp8wCgYIKoZIzj0EAwMwGzEZMBcGA1UEAxMQ"
            + "RkQwMDExMjIzM0ZGRUU2NjAgFw0yNjEwMjMwNDUzNTVaGA8yMTI2MDkyOTA0NTM1NVowGzEZMBcGA1UEAxMQRkQwMDEx"
            + "MjIzM0ZGRUU2NjBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABAC5V0PZacmSNJmMdBgRNw0hJNOAIELHd1FZPEwek2TK"
            + "doa2i0IAzVAoz5wQYpbhGa1N1caSvDiOAjeiij7RR96jITAfMB0GA1UdDgQWBBRWeo5sOhhNHmu4GHPloUIcRD65CTAK"
            + "BggqhkjOPQQDAwNJADBGAiEA3EG13ujHXRX+YM4bR3PufERt781Fmn1KBicBMVdv0s8CIQC4kp7EcL2BS974U3G9R7Z1"
            + "26vwVp81AkbTgbSUCpfnWA==";

    @Test
    void parsesLiveIdFromDocumentedLayout() {
        byte[] response = discoveryResponse(true);
        assertEquals(LIVE_ID, LiveIdUtil.parseLiveId(response, response.length));
    }

    @Test
    void fallsBackToCertificateScanWhenLayoutDeviates() {
        // Ohne die 0x00-Terminatoren hinter den SGStrings verrutscht der dokumentierte Pfad – der
        // DER-Scan muss das Zertifikat trotzdem finden.
        byte[] response = discoveryResponse(false);
        assertEquals(LIVE_ID, LiveIdUtil.parseLiveId(response, response.length));
    }

    @Test
    void returnsNullForTruncatedResponse() {
        byte[] response = discoveryResponse(true);
        assertNull(LiveIdUtil.parseLiveId(response, response.length - 100));
    }

    @Test
    void returnsNullWithoutCertificate() {
        byte[] response = new byte[40];
        response[0] = (byte) 0xDD;
        response[1] = 0x01;
        assertNull(LiveIdUtil.parseLiveId(response, response.length));
    }

    /**
     * Baut eine Discovery-Antwort wie von der Konsole: Header DD01 | Laenge | Version, dann Flags,
     * Typ, SGString Name, SGString UUID (je mit 0x00-Terminator), Last Error, Zertifikat.
     */
    private static byte[] discoveryResponse(boolean withTerminators) {
        byte[] cert = Base64.getDecoder().decode(CERT_BASE64);
        byte[] name = "XboxOne".getBytes(StandardCharsets.UTF_8);
        byte[] uuid = "DE305D54-75B4-431B-ADB2-EB6B9E546014".getBytes(StandardCharsets.UTF_8);
        int terminator = withTerminators ? 1 : 0;
        byte[] packet = new byte[6 + 4 + 2 + 2 + name.length + terminator + 2 + uuid.length + terminator + 4 + 2
                + cert.length];
        ByteBuffer buffer = ByteBuffer.wrap(packet);
        buffer.putShort((short) 0xDD01);
        buffer.putShort((short) (packet.length - 6)); // Payload-Laenge
        buffer.putShort((short) 2); // Version
        buffer.putInt(0); // Primary Device Flags
        buffer.putShort((short) 1); // Device Type
        buffer.putShort((short) name.length).put(name);
        if (withTerminators) {
            buffer.put((byte) 0);
        }
        buffer.putShort((short) uuid.length).put(uuid);
        if (withTerminators) {
            buffer.put((byte) 0);
        }
        buffer.putInt(0); // Last Error
        buffer.putShort((short) cert.length).put(cert);
        return packet;
    }
}
