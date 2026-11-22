package org.openhab.binding.xbox.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Tests fuer die SmartGlass-Pakete aus {@link XboxBindingConstants}. Referenz fuer das Format:
 * xbox-smartglass-core ({@code SGString = TerminatedField(PascalString(Int16ub))}) bzw.
 * xbox-remote-power.
 *
 * @author t09
 */
@NonNullByDefault
class XboxBindingConstantsTest {

    private static final String LIVE_ID = "FD00112233FFEE66";

    @Test
    void powerOnPacketMatchesReferenceFormat() {
        // Header DD02 | Payload-Laenge 0x0013 | Version 0, Payload = 0010 + 16 ASCII-Bytes + 00
        String expected = "dd02" + "0013" + "0000" + "0010"
                + HexFormat.of().formatHex(LIVE_ID.getBytes(StandardCharsets.US_ASCII)) + "00";
        assertEquals(expected, HexFormat.of().formatHex(XboxBindingConstants.powerOnPacket(LIVE_ID)));
    }

    @Test
    void powerOnPacketLengthFieldsAreConsistent() {
        byte[] packet = XboxBindingConstants.powerOnPacket(LIVE_ID);
        int payloadLength = ((packet[2] & 0xFF) << 8) | (packet[3] & 0xFF);
        int stringLength = ((packet[6] & 0xFF) << 8) | (packet[7] & 0xFF);
        assertEquals(packet.length - 6, payloadLength, "Header-Laenge muss der tatsaechlichen Payload entsprechen");
        assertEquals(LIVE_ID.length(), stringLength, "SGString-Laenge zaehlt den Terminator nicht mit");
        assertEquals(0, packet[packet.length - 1], "SGString endet mit dem 0x00-Terminator");
    }

    @Test
    void discoveryPacketIsReturnedAsCopy() {
        byte[] packet = XboxBindingConstants.discoveryPacket();
        assertEquals("dd00000a000000000000000300000002", HexFormat.of().formatHex(packet));
        packet[0] = 0;
        assertEquals((byte) 0xDD, XboxBindingConstants.discoveryPacket()[0], "Aenderung an der Kopie wirkt nicht nach");
    }
}
