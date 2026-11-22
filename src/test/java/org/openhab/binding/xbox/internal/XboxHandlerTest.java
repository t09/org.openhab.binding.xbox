package org.openhab.binding.xbox.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

/**
 * Tests fuer die zustandslosen Helfer des {@link XboxHandler}: stabile Laufwerksnummern
 * ({@code assignDriveSlots}), App-Liste ({@code parseAppList}), Laufwerksnamen ({@code driveName})
 * und XSTS-Token-Refresh-Fristen ({@code tokenRefreshDeadline}).
 *
 * @author t09
 */
@NonNullByDefault
class XboxHandlerTest {

    // ---- Laufwerksnummern ----------------------------------------------------------------------

    @Test
    void newDrivesAreNumberedInApiOrder() {
        assertArrayEquals(new int[] { 1, 2 }, XboxHandler.assignDriveSlots(existing(), keys("A", "B")));
    }

    @Test
    void legacyChannelsWithoutDeviceIdAreAdoptedByPosition() {
        // Channels von vor der Umstellung haben keine storageDeviceId -> Nummer bleibt wie bisher.
        assertArrayEquals(new int[] { 1, 2 }, XboxHandler.assignDriveSlots(existing(null, null), keys("A", "B")));
    }

    @Test
    void knownDrivesKeepTheirNumberWhenApiOrderChanges() {
        assertArrayEquals(new int[] { 2, 1 }, XboxHandler.assignDriveSlots(existing("A", "B"), keys("B", "A")));
    }

    @Test
    void unpluggedDriveKeepsItsNumberReserved() {
        // B fehlt gerade, C ist neu: C darf NICHT B's Nummer 2 erben (sonst falsche Werte am Item).
        assertArrayEquals(new int[] { 1, 3 }, XboxHandler.assignDriveSlots(existing("A", "B"), keys("A", "C")));
    }

    @Test
    void returningDriveGetsItsOldNumber() {
        assertArrayEquals(new int[] { 3 }, XboxHandler.assignDriveSlots(existing("A", "B", "C"), keys("C")));
    }

    @Test
    void legacyChannelIsAdoptedNextToKnownDrive() {
        assertArrayEquals(new int[] { 1, 2 }, XboxHandler.assignDriveSlots(existing(null, "B"), keys("A", "B")));
    }

    // ---- App-Liste -----------------------------------------------------------------------------

    @Test
    void parsesAppListFromConfigList() {
        List<Object> cfg = new ArrayList<>();
        cfg.add("Netflix=9WZDNCRFJ3TJ");
        cfg.add(" Plex = 9WZDNCRFJ3Q8 ");
        cfg.add(42); // kein Text -> ignoriert
        assertEquals("Netflix=9WZDNCRFJ3TJ,Plex=9WZDNCRFJ3Q8", format(XboxHandler.parseAppList(cfg)));
    }

    @Test
    void parsesAppListFromCommaAndNewlineSeparatedText() {
        assertEquals("A=1,B=2,C=3", format(XboxHandler.parseAppList("A=1,B=2\nC=3")));
    }

    @Test
    void skipsInvalidAppEntries() {
        assertEquals("ok=1", format(XboxHandler.parseAppList("NoEquals,=id,name=, ok=1")));
    }

    @Test
    void laterDuplicateAppNameWins() {
        assertEquals("B=2,A=3", format(XboxHandler.parseAppList("A=1,B=2,A=3")));
    }

    @Test
    void emptyOrMissingAppListYieldsNoEntries() {
        assertEquals("", format(XboxHandler.parseAppList(null)));
        assertEquals("", format(XboxHandler.parseAppList("")));
    }

    // ---- Laufwerksnamen ------------------------------------------------------------------------

    @Test
    void driveNameUsesReportedNameWhenPresent() {
        JsonObject dev = new JsonObject();
        dev.addProperty("storageDeviceName", "Internal");
        assertEquals("Internal", XboxHandler.driveName(dev, 1));
    }

    @Test
    void driveNameFallsBackToEnglishDriveLabelWhenMissingOrBlank() {
        JsonObject empty = new JsonObject();
        assertEquals("Drive 2", XboxHandler.driveName(empty, 2));

        JsonObject blank = new JsonObject();
        blank.addProperty("storageDeviceName", "   ");
        assertEquals("Drive 3", XboxHandler.driveName(blank, 3));
    }

    // ---- XSTS-Token-Fristen --------------------------------------------------------------------

    @Test
    void tokenRefreshDeadlineUsesFiveMinuteMarginBeforeNotAfter() {
        Instant notAfter = Instant.now().plusSeconds(16 * 3600);
        long deadline = XboxHandler.tokenRefreshDeadline(notAfter.toString());
        assertEquals(notAfter.toEpochMilli() - 5 * 60_000L, deadline);
    }

    @Test
    void tokenRefreshDeadlineClampsSoonOrPastExpiryToRetryWindow() {
        long before = System.currentTimeMillis();
        long deadline = XboxHandler.tokenRefreshDeadline(Instant.now().minusSeconds(60).toString());
        long after = System.currentTimeMillis();
        assertTrue(deadline >= before + 60_000L && deadline <= after + 60_000L);
    }

    @Test
    void tokenRefreshDeadlineFallsBackToOneHourWhenMissingOrInvalid() {
        long before = System.currentTimeMillis();
        long fromNull = XboxHandler.tokenRefreshDeadline(null);
        long fromInvalid = XboxHandler.tokenRefreshDeadline("not-a-timestamp");
        long after = System.currentTimeMillis();
        assertTrue(fromNull >= before + 3_600_000L && fromNull <= after + 3_600_000L);
        assertTrue(fromInvalid >= before + 3_600_000L && fromInvalid <= after + 3_600_000L);
    }

    // ---- Helfer --------------------------------------------------------------------------------

    /** Vorhandene Laufwerks-Channels: Position i = Nummer i+1, Wert = storageDeviceId (null = alt). */
    private static Map<Integer, @Nullable String> existing(@Nullable String... keysBySlot) {
        Map<Integer, @Nullable String> map = new HashMap<>();
        for (int i = 0; i < keysBySlot.length; i++) {
            map.put(i + 1, keysBySlot[i]);
        }
        return map;
    }

    private static List<String> keys(String first) {
        List<String> keys = new ArrayList<>();
        keys.add(first);
        return keys;
    }

    private static List<String> keys(String first, String second) {
        List<String> keys = keys(first);
        keys.add(second);
        return keys;
    }

    private static String format(List<XboxHandler.AppEntry> apps) {
        String result = "";
        for (XboxHandler.AppEntry app : apps) {
            result = result + (result.isEmpty() ? "" : ",") + app.name() + "=" + app.productId();
        }
        return result;
    }
}
