package org.openhab.binding.xbox.internal;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ThingTypeUID;

/**
 * The {@link XboxBindingConstants} class defines common constants which are
 * used across the whole binding.
 *
 * @author Jochen
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

    // Config parameter names
    public static final String CONFIG_HOST               = "host";
    public static final String CONFIG_LIVE_ID            = "liveId";
    public static final String CONFIG_CLIENT_ID          = "clientId";
    public static final String CONFIG_REFRESH_TOKEN      = "refreshToken";
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
}
