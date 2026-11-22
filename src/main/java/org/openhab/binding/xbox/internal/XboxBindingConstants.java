package org.openhab.binding.xbox.internal;

import java.nio.charset.StandardCharsets;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ThingTypeUID;

/**
 * The {@link XboxBindingConstants} class defines common constants which are
 * used across the whole binding.
 *
 * @author t09
 */
@NonNullByDefault
public class XboxBindingConstants {

    public static final String BINDING_ID = "xbox";

    // List of all Thing Type UIDs
    public static final ThingTypeUID THING_TYPE_XBOX = new ThingTypeUID(BINDING_ID, "xbox");

    // Channel IDs
    public static final String CHANNEL_POWER         = "power";
    public static final String CHANNEL_STATUS        = "status";
    public static final String CHANNEL_LAUNCH        = "launch";
    public static final String CHANNEL_CURRENT_TITLE = "currentTitle";
    public static final String CHANNEL_COVER_ART     = "coverArt";
    public static final String CHANNEL_STORAGE_FREE  = "storageFree";
    public static final String CHANNEL_STORAGE_TOTAL = "storageTotal";
    public static final String CHANNEL_STORAGE_USED  = "storageUsedPercent";

    // Prefixe fuer dynamisch pro erkanntem Laufwerk angelegte Channels (1-basiert, je 3 Channels:
    // storageTotalDrive1/storageFreeDrive1/storageUsedDrive1, ...Drive2, ...). Der Laufwerksname
    // steckt im Channel-Label (nicht als eigener Channel). Sie referenzieren die bestehenden
    // Channel-Typen storageTotal/storageFree/storageUsedPercent (inkl. GiB- bzw. %-Anzeige).
    public static final String CHANNEL_STORAGE_TOTAL_DRIVE = "storageTotalDrive";
    public static final String CHANNEL_STORAGE_FREE_DRIVE  = "storageFreeDrive";
    public static final String CHANNEL_STORAGE_USED_DRIVE  = "storageUsedDrive";
    // Channel-Property der Laufwerks-Channels: bindet die Laufwerksnummer n stabil an die
    // storageDeviceId (Reihenfolge-Wechsel in der API vertauschen so keine Werte).
    public static final String PROPERTY_STORAGE_DEVICE_ID = "storageDeviceId";

    // Config parameter names
    public static final String CONFIG_HOST               = "host";
    // liveId ist KEIN deklarierter Parameter (nicht im Formular). Quellen: Thing-Property aus der
    // Discovery, sonst intern gelernt (lokale Probe / Cloud-Geraeteliste) und in der Config gehalten.
    // Zugleich Representation-Property (Inbox erkennt die Konsole wieder).
    public static final String CONFIG_LIVE_ID            = "liveId";
    public static final String CONFIG_CLIENT_ID          = "clientId";
    public static final String CONFIG_REFRESH_TOKEN      = "refreshToken";
    // Frueher Config-Parameter; der Device-Code lebt jetzt nur im Speicher. Nur noch zum
    // Entfernen alter Werte aus bestehenden Things.
    public static final String CONFIG_DEVICE_CODE        = "deviceCode";
    public static final String CONFIG_AUTHENTICATION_URL = "authenticationUrl";
    public static final String CONFIG_USER_CODE          = "userCode";
    public static final String CONFIG_POLLING_INTERVAL   = "pollingInterval";
    // Vorausgefuellte App-Liste fuer den launch-Channel: Eintraege "Name=StoreProductId" (in
    // thing-types.xml als <default> hinterlegt). Quelle der Launch-Dropdown-Optionen.
    public static final String CONFIG_APP_LIST           = "appList";

    // 16-Byte SmartGlass Discovery-Request-Paket (0xDD00). Zentral, da Discovery UND der Handler
    // (lokale Erreichbarkeits-Probe) es brauchen. Nur lesend; discoveryPacket() gibt eine Kopie.
    private static final byte[] DISCOVERY_PACKET = { (byte) 0xDD, (byte) 0x00, (byte) 0x00, (byte) 0x0A,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x03,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x02 };

    /** Liefert eine frische Kopie des SmartGlass Discovery-Request-Pakets (0xDD00). */
    public static byte[] discoveryPacket() {
        return DISCOVERY_PACKET.clone();
    }

    /**
     * Baut das SmartGlass Power-On-Paket (0xDD02) fuer die Live ID.
     *
     * <pre>
     *   DD 02 | uint16 Payload-Laenge | uint16 Version (0)
     *   Payload = SGString(liveId) = uint16 Laenge + ASCII-Bytes + 0x00-Terminator
     * </pre>
     *
     * Der Terminator zaehlt NICHT zur Laenge, gehoert aber zum SGString (xbox-smartglass-core:
     * {@code SGString = TerminatedField(PascalString(Int16ub))}, ebenso xbox-remote-power). Fuer eine
     * 16-stellige Live ID: Header {@code DD02 0013 0000} + Payload {@code 0010 <16 Byte> 00}.
     */
    public static byte[] powerOnPacket(String liveId) {
        byte[] id = liveId.getBytes(StandardCharsets.US_ASCII);
        int payloadLength = 2 + id.length + 1;
        byte[] packet = new byte[6 + payloadLength];
        packet[0] = (byte) 0xDD;
        packet[1] = (byte) 0x02;
        packet[2] = (byte) ((payloadLength >> 8) & 0xFF);
        packet[3] = (byte) (payloadLength & 0xFF);
        // packet[4..5] = Version 0
        packet[6] = (byte) ((id.length >> 8) & 0xFF);
        packet[7] = (byte) (id.length & 0xFF);
        System.arraycopy(id, 0, packet, 8, id.length);
        // letztes Byte bleibt 0x00 = SGString-Terminator
        return packet;
    }
}
