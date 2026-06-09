package org.openhab.binding.xbox.internal;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Extracts the console's Live ID from a SmartGlass Discovery Response.
 *
 * The Live ID is the Common Name (CN) of the Subject in the X.509 certificate
 * that the console embeds in its discovery response. This is the value required
 * by the SmartGlass Power-On packet (0xDD02) to wake the console remotely.
 *
 * Reference: https://openxbox.org/smartglass-documentation/simple_message/
 *
 * @author Jochen
 */
@NonNullByDefault
public final class LiveIdUtil {

    private LiveIdUtil() {
    }

    /**
     * Parses the Live ID from a raw SmartGlass Discovery Response packet.
     *
     * Discovery Response payload layout (after the 6-byte simple-message header):
     * <pre>
     *   uint32   Primary Device Flags
     *   uint16   Type
     *   SGString ConsoleName        (uint16 length + bytes)
     *   SGString UUID               (uint16 length + bytes)
     *   uint32   Last Error
     *   uint16   Certificate Length
     *   byte[]   Certificate        (X.509 DER, Subject CN = Live ID)
     * </pre>
     *
     * @return the Live ID, or {@code null} if it could not be parsed
     */
    // getSubjectDN() ist deprecated, aber bewusst gewaehlt: liefert java.security.Principal
    // (Bootclasspath) statt javax.security.auth.x500.X500Principal -> kein zusaetzliches
    // OSGi-Import-Package noetig, das den Bundle-Start gefaehrden koennte.
    @SuppressWarnings("deprecation")
    public static @Nullable String parseLiveId(byte[] data, int len) {
        byte[] certBytes = extractCertificate(data, len);
        if (certBytes == null) {
            return null;
        }
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(certBytes));
            // getSubjectDN() returns a java.security.Principal (system package) – avoids an
            // OSGi import of javax.security.auth.x500. DN looks like "CN=F4000..., OU=...".
            String dn = cert.getSubjectDN().getName();
            return extractCn(dn);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Locates the embedded X.509 certificate. Primary path follows the documented layout;
     * a fallback scans for the DER SEQUENCE marker so small layout deviations don't break us.
     */
    private static byte @Nullable [] extractCertificate(byte[] data, int len) {
        // Primary: walk the documented structure.
        // NOTE: each SGString is followed by a single 0x00 terminator byte that is NOT part
        // of the declared length – it must be skipped, otherwise the cert length is misread.
        try {
            int o = 6 + 4 + 2; // header + Primary Device Flags (uint32) + Type (uint16)
            o = sgStringEnd(data, o, len);
            if (o >= 0) {
                o += 1; // ConsoleName null terminator
                o = sgStringEnd(data, o, len);
                if (o >= 0) {
                    o += 1; // UUID null terminator
                    int certLenOffset = o + 4; // skip uint32 Last Error
                    if (certLenOffset + 2 <= len) {
                        int certLen = ((data[certLenOffset] & 0xFF) << 8) | (data[certLenOffset + 1] & 0xFF);
                        int certStart = certLenOffset + 2;
                        if (certLen > 0 && certStart + certLen <= len && isDerSequence(data, certStart)) {
                            return java.util.Arrays.copyOfRange(data, certStart, certStart + certLen);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through to scan
        }

        // Fallback: scan for an X.509 DER SEQUENCE (0x30 0x82 = SEQUENCE, 2-byte length).
        for (int i = 6; i + 4 < len; i++) {
            if (isDerSequence(data, i)) {
                int seqLen = ((data[i + 2] & 0xFF) << 8) | (data[i + 3] & 0xFF);
                int total = 4 + seqLen; // tag + 2 length bytes + content
                if (seqLen > 200 && i + total <= len) {
                    return java.util.Arrays.copyOfRange(data, i, i + total);
                }
            }
        }
        return null;
    }

    /** True if position p starts a DER SEQUENCE with a 2-byte length (0x30 0x82). */
    private static boolean isDerSequence(byte[] data, int p) {
        return p + 1 < data.length && data[p] == (byte) 0x30 && data[p + 1] == (byte) 0x82;
    }

    private static @Nullable String extractCn(@Nullable String dn) {
        if (dn == null) {
            return null;
        }
        for (String part : dn.split(",")) {
            String p = part.trim();
            if (p.regionMatches(true, 0, "CN=", 0, 3)) {
                String cn = p.substring(3).trim();
                return cn.isEmpty() ? null : cn;
            }
        }
        return null;
    }

    private static int sgStringEnd(byte[] data, int offset, int len) {
        if (offset + 2 > len) {
            return -1;
        }
        int byteCount = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
        int end = offset + 2 + byteCount;
        return (end <= len) ? end : -1;
    }
}
