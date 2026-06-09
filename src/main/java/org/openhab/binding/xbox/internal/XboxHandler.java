package org.openhab.binding.xbox.internal;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.BodyPublishers;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.RawType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.openhab.core.types.StateOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The {@link XboxHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Jochen
 */
@NonNullByDefault
public class XboxHandler extends BaseThingHandler {

    @SuppressWarnings("null")
    private final Logger logger = LoggerFactory.getLogger(XboxHandler.class);
    private @Nullable ScheduledFuture<?> pollingTask;
    private @Nullable ScheduledFuture<?> authPollingTask;
    private volatile int consecutiveFailures = 0;
    @SuppressWarnings("null")
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private volatile @Nullable String xstsToken;
    private volatile @Nullable String userHash;
    private volatile @Nullable String xuid;
    private volatile long tokenExpiry = 0;
    private volatile @Nullable String currentTitleId;
    // Erwarteter Power-Zustand nach einem Schaltbefehl + Ablaufzeitpunkt der "Grace-Periode".
    // Solange die Konsole den Uebergang (Boot ~Sekunden, Shutdown ~1 Min) noch nicht vollzogen
    // hat, wuerde der Poll den gerade gesetzten Wert kurz zuruckschnappen lassen ("nervoeser"
    // Switch). Innerhalb der Grace-Periode halten wir den Wunschzustand, bis die Konsole ihn
    // bestaetigt oder die Frist ablaeuft.
    private volatile @Nullable Boolean pendingPowerState;
    private volatile long pendingPowerUntil = 0;
    private static final long POWER_GRACE_MS = 90_000;
    // Session-Cookies (z. B. uaid) aus der device-code-Antwort. login.live.com verlangt, dass
    // sie beim Token-Polling zurueckgeschickt werden, sonst wird der abgeschlossene Login nicht
    // erkannt (Fehler invalid_grant "user must first sign in"). Javas CookieManager macht das
    // bei host==domain nicht zuverlaessig, daher fangen wir die Cookies manuell ab.
    private volatile @Nullable String authCookies;

    // Nativer MSA-Scope fuer den login.live.com-Flow mit der Legacy-Client-ID. Liefert
    // automatisch einen refresh_token. WICHTIG: NICHT der Azure-Scope "XboxLive.signin" –
    // den lehnt login.live.com bei der Token-Ausgabe ab ("scopes unauthorized").
    private static final String SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";

    // Eingebaute Live-Connect-Client-ID der offiziellen Xbox-App. Sie funktioniert mit dem
    // Device-Code-Flow gegen login.live.com OHNE eigene Azure-Registrierung. Der Nutzer muss
    // also normalerweise keine clientId eintragen (das Feld bleibt optional fuer Spezialfaelle).
    private static final String DEFAULT_CLIENT_ID = "000000004C12AE6F";

    // Xbox-Cloud-Steuerungs-API (xccs.xboxlive.com / "SmartGlass RemoteManagement").
    // Liefert Power-State + aktive App und erlaubt zuverlaessiges Ein-/Ausschalten ueber die
    // Cloud (XSTS-Token), unabhaengig vom lokalen verschluesselten SmartGlass-Handshake.
    private static final String XCCS_URL = "https://xccs.xboxlive.com";
    private final String smartglassSessionId = java.util.UUID.randomUUID().toString();

    // Microsoft-Account- (Live-Connect-) Endpunkte fuer den Device-Code-Flow.
    // oauth20_connect.srf liefert user_code + device_code; oauth20_token.srf tauscht/erneuert.
    private static final String LIVE_DEVICE_CODE_URL = "https://login.live.com/oauth20_connect.srf";
    private static final String LIVE_TOKEN_URL = "https://login.live.com/oauth20_token.srf";

    private final XboxStateDescriptionProvider stateDescriptionProvider;

    public XboxHandler(Thing thing, XboxStateDescriptionProvider stateDescriptionProvider) {
        super(thing);
        this.stateDescriptionProvider = stateDescriptionProvider;
    }

    /** Returns the configured client id, or the built-in Live-Connect id if none is set. */
    private String resolveClientId() {
        String clientId = (String) getThing().getConfiguration().get(XboxBindingConstants.CONFIG_CLIENT_ID);
        return (clientId == null || clientId.isBlank()) ? DEFAULT_CLIENT_ID : clientId;
    }

    /** URL-encodes a value for an x-www-form-urlencoded body (UTF-8). */
    private static String enc(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Extracts the cookie name=value pairs from a response's Set-Cookie headers and joins them
     * into a single Cookie header value (e.g. "uaid=...; foo=bar").
     */
    private static String extractCookies(HttpResponse<?> response) {
        java.util.List<String> setCookies = response.headers().allValues("set-cookie");
        if (setCookies.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String sc : setCookies) {
            String pair = sc.split(";", 2)[0].trim();
            if (!pair.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(pair);
            }
        }
        return sb.toString();
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // REFRESH zuerst behandeln, damit es fuer JEDEN Channel (auch power/launch) einen Poll
        // ausloest – frueher wurde es von den channelUID-Zweigen verschluckt.
        if (command instanceof RefreshType) {
            logger.debug("REFRESH requested for channel '{}' – triggering poll", channelUID.getId());
            scheduler.execute(this::checkStatus);
            return;
        }
        if (channelUID.getId().equals(XboxBindingConstants.CHANNEL_POWER)) {
            if (OnOffType.ON.equals(command)) {
                powerOn();
            } else if (OnOffType.OFF.equals(command)) {
                powerOff();
            }
        } else if (channelUID.getId().equals(XboxBindingConstants.CHANNEL_LAUNCH)) {
            if (command instanceof StringType) {
                launchApp(command.toString());
            }
        }
    }

    /**
     * Wird von openHAB aufgerufen, sobald ein Channel mit einem Item verknuepft (also "angelegt")
     * wird. Frueher gab es keinen Override – neu angelegte Channels bekamen daher erst beim
     * naechsten Poll-Intervall (Default 600s, und ueberhaupt nur die wenigen vom Poll bedienten) einen Wert.
     * Jetzt wird jeder verknuepfte Channel im Log vermerkt und sofort ein Poll angestossen.
     */
    @Override
    public void channelLinked(ChannelUID channelUID) {
        super.channelLinked(channelUID);
        logger.info("Channel '{}' linked – triggering immediate poll to populate its state", channelUID.getId());
        scheduler.execute(this::checkStatus);
    }

    /**
     * Zentrale State-Aktualisierung mit Logging. Jeder Channel-Update landet so sichtbar im
     * KARAF-Log (DEBUG-Level fuer den Binding-Logger aktivieren:
     * {@code log:set DEBUG org.openhab.binding.xbox}), damit nachvollziehbar ist, dass und mit
     * welchem Wert jeder Channel gepollt wurde.
     */
    private void updateChannel(String channelId, State state) {
        logger.debug("Polling channel '{}' -> {}", channelId, state);
        updateState(channelId, state);
    }

    private void powerOn() {
        String liveId = (String) getThing().getConfiguration().get(XboxBindingConstants.CONFIG_LIVE_ID);

        // Eine Xbox wacht NICHT per klassischem WoL-Magic-Packet auf, sondern nur per
        // SmartGlass Power-On-Paket (0xDD02), das die Live ID enthaelt. Voraussetzung: in den
        // Xbox-Energieoptionen "Energiesparmodus" -> "Sofort starten" (Instant-On) aktiv.
        if (liveId == null || liveId.isBlank()) {
            logger.warn("Cannot power on Xbox: Live ID not yet known. It is auto-detected while "
                    + "the console is on – turn it on once so the binding can learn the Live ID.");
            return;
        }
        // Wunschzustand merken, damit der Poll waehrend des Bootens nicht zurueckschnappt.
        setPendingPower(true);
        // Kombiniert: lokales 0xDD02-Paket (schnell, ohne Internet) UND Cloud-WakeUp (zuverlaessig).
        triggerXboxPowerOn(liveId);
        scheduler.execute(() -> sendCloudPowerCommand("WakeUp"));
        // Status kurz nach dem Einschalten nachziehen.
        scheduler.schedule(this::checkStatus, 5, TimeUnit.SECONDS);
    }

    private void powerOff() {
        // Wunschzustand merken (Shutdown dauert ~1 Min, sonst flackert der Switch zurueck auf ON).
        setPendingPower(false);
        // Ausschalten geht zuverlaessig nur ueber die Cloud (lokales Off braucht den
        // verschluesselten SmartGlass-Connect-Handshake, den dieses Binding nicht aufbaut).
        scheduler.execute(() -> {
            sendCloudPowerCommand("TurnOff");
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            checkStatus();
        });
    }

    /** Merkt den nach einem Schaltbefehl gewuenschten Power-Zustand und zeigt ihn sofort an. */
    private void setPendingPower(boolean on) {
        pendingPowerState = on;
        pendingPowerUntil = System.currentTimeMillis() + POWER_GRACE_MS;
        updateChannel(XboxBindingConstants.CHANNEL_POWER, OnOffType.from(on));
    }

    private void launchApp(String appNameOrId) {
        String liveId = (String) getThing().getConfiguration().get(XboxBindingConstants.CONFIG_LIVE_ID);
        if (liveId == null || liveId.isEmpty()) {
            logger.warn("Cannot launch app {}: Live ID not configured.", appNameOrId);
            return;
        }

        String productId = resolveStoreProductId(appNameOrId);
        logger.info("Launching App '{}' (Store Product ID: {}) on Xbox Live ID {}", appNameOrId, productId, liveId);

        scheduler.execute(() -> {
            // App-Start laeuft ueber die xccs RemoteManagement-API (Shell/ActivateApplication...).
            // Einen lokalen SmartGlass-Fallback gibt es bewusst NICHT: ein App-Start verlangt – wie
            // Ausschalten/Media – den verschluesselten Connect-Kanal, den dieses Binding nicht aufbaut.
            // Nur Wake (0xDD02) und Discovery (0xDD00) gehen lokal unverschluesselt.
            if (!launchAppViaCloud(liveId, productId)) {
                logger.warn("App launch '{}' failed: cloud command not accepted (no token / offline / "
                        + "RemoteManagement disabled). Local launch is not supported.", productId);
            }

            // Aktiven Titel kurz nach dem Start nachziehen (currentTitle/coverArt aktualisieren).
            scheduler.schedule(this::checkStatus, 4, TimeUnit.SECONDS);
        });
    }

    /**
     * Bildet einen App-Namen (wie in der App-Liste konfiguriert) auf die Store-Product-ID
     * (oneStoreProductId) ab. Ist bereits eine ID angegeben (kein bekannter Name), wird sie
     * unveraendert durchgereicht – so laesst sich jede beliebige App per ID starten.
     */
    private String resolveStoreProductId(String appNameOrId) {
        for (Map.Entry<String, String> e : resolveAppMap().entrySet()) {
            if (e.getKey().equalsIgnoreCase(appNameOrId)) {
                return e.getValue();
            }
        }
        return appNameOrId; // schon eine Store-Product-ID
    }

    /**
     * Liefert die effektive App-Liste (Name -> Store-Product-ID). Quelle: Thing-Config
     * {@code appList} (Eintraege "Name=Id", je Zeile bzw. komma-getrennt). Ist nichts konfiguriert,
     * gelten die eingebauten Defaults aus {@link #buildDefaultApps()}. Reihenfolge bleibt erhalten.
     */
    private Map<String, String> resolveAppMap() {
        Map<String, String> map = new LinkedHashMap<>();
        Object cfg = getThing().getConfiguration().get(XboxBindingConstants.CONFIG_APP_LIST);
        List<String> entries = new ArrayList<>();
        if (cfg instanceof List) {
            for (Object o : (List<?>) cfg) {
                if (o != null) {
                    entries.add(o.toString());
                }
            }
        } else if (cfg instanceof String && !((String) cfg).isBlank()) {
            for (String s : ((String) cfg).split("[,\\n]")) {
                entries.add(s);
            }
        }
        for (String entry : entries) {
            int eq = entry.indexOf('=');
            if (eq > 0) {
                String name = entry.substring(0, eq).trim();
                String id = entry.substring(eq + 1).trim();
                if (!name.isEmpty() && !id.isEmpty()) {
                    map.put(name, id);
                }
            }
        }
        return map.isEmpty() ? buildDefaultApps() : map;
    }

    /**
     * Eingebaute Standard-App-Liste (Name -> Store-Product-ID / oneStoreProductId), Stand 2026-06.
     * Per Thing-Config {@code appList} vollstaendig ueberschreibbar (die Defaults sind dort als
     * {@code <default>} vorausgefuellt, daher reicht meist Zeile hinzufuegen/entfernen).
     */
    private static Map<String, String> buildDefaultApps() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("Netflix", "9WZDNCRFJ3TJ");
        m.put("Disney+", "9NXQXXLFST89");
        m.put("Prime Video", "9P6RC76MSMMJ");
        m.put("Spotify", "9NCBCSZSJRSB");
        m.put("Twitch", "9PFJP1Q9R4FK");
        m.put("Apple TV", "9MW0ZWQFH0M2");
        m.put("HBO Max", "9PJJ1K9DZMRS");
        m.put("Plex", "9WZDNCRFJ3Q8");
        return m;
    }

    /**
     * Fuellt die Auswahlliste (StateOptions) des launch-Channels aus der App-Liste. Option-Wert =
     * Anzeigename (wird beim Start per {@link #resolveStoreProductId} zur ID aufgeloest). So ist das
     * Dropdown automatisch befuellt; die Liste ist in der Thing-Config {@code appList} ohne Rebuild
     * aenderbar (Config-Aenderung loest dispose()+initialize() aus).
     */
    private void updateLaunchOptions() {
        List<StateOption> options = new ArrayList<>();
        for (String name : resolveAppMap().keySet()) {
            options.add(new StateOption(name, name));
        }
        stateDescriptionProvider.setStateOptions(
                new ChannelUID(getThing().getUID(), XboxBindingConstants.CHANNEL_LAUNCH), options);
    }

    /**
     * Startet eine App zuverlaessig ueber die Cloud-Command-API (xccs) mit dem Shell-Kommando
     * {@code ActivateApplicationWithOneStoreProductId}: die Konsole holt die App anhand der
     * Store-Product-ID in den Vordergrund (bzw. installiert/startet sie).
     */
    private boolean launchAppViaCloud(String liveId, String productId) {
        if (System.currentTimeMillis() > tokenExpiry || xstsToken == null) {
            refreshXstsToken();
        }
        if (xstsToken == null) {
            logger.warn("Cannot launch '{}' via cloud: no valid Xbox token.", productId);
            return false;
        }
        try {
            JsonObject param = new JsonObject();
            param.addProperty("oneStoreProductId", productId);
            JsonArray params = new JsonArray();
            params.add(param);

            JsonObject body = new JsonObject();
            body.addProperty("destination", "Xbox");
            body.addProperty("type", "Shell");
            body.addProperty("command", "ActivateApplicationWithOneStoreProductId");
            body.addProperty("sessionId", smartglassSessionId);
            body.addProperty("sourceId", "com.microsoft.smartglass");
            body.add("parameters", params);
            body.addProperty("linkedXboxId", liveId);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(XCCS_URL + "/commands"))
                    .header("Authorization", "XBL3.0 x=" + userHash + ";" + xstsToken)
                    .header("x-xbl-contract-version", "4")
                    .header("skillplatform", "RemoteManagement")
                    .header("Content-Type", "application/json")
                    .POST(BodyPublishers.ofString(body.toString()))
                    .build();

            @SuppressWarnings("null")
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 == 2) {
                logger.info("Xbox cloud app-launch '{}' accepted.", productId);
                return true;
            }
            logger.warn("Xbox cloud app-launch '{}' failed (HTTP {}): {}", productId, resp.statusCode(),
                    resp.body());
            return false;
        } catch (Exception e) {
            logger.error("Error launching app '{}' via cloud: {}", productId, e.getMessage());
            return false;
        }
    }

    @Override
    public void initialize() {
        updateLaunchOptions();
        startPolling();
        String refreshToken = (String) getThing().getConfiguration().get(XboxBindingConstants.CONFIG_REFRESH_TOKEN);
        String deviceCode   = (String) getThing().getConfiguration().get(XboxBindingConstants.CONFIG_DEVICE_CODE);

        if (refreshToken != null && !refreshToken.isEmpty()) {
            updateStatus(ThingStatus.ONLINE);
            updateChannel(XboxBindingConstants.CHANNEL_STATUS, new StringType("Authenticated"));
            // Sofort einen XSTS-Token holen und einmal pollen, damit die Channels nicht erst
            // nach dem Poll-Intervall (Default 600s, oder gar nicht) Werte bekommen.
            scheduler.execute(() -> {
                refreshXstsToken();
                checkStatus();
            });
        } else if (deviceCode != null && !deviceCode.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING, "Authentication in progress");
            updateChannel(XboxBindingConstants.CHANNEL_STATUS, new StringType("Authentication in progress"));
            startAuthPolling(deviceCode, 5);
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING, "Authentication required");
            updateChannel(XboxBindingConstants.CHANNEL_STATUS, new StringType("Authentication required"));
            initiateAuthentication();
        }
    }

    @Override
    public void dispose() {
        stopPolling();
        // Auch den Auth-Polling-Task stoppen (lief frueher beim dispose weiter) und
        // Token-Zustand verwerfen, damit wiederholtes Anlegen/Loeschen sauber bleibt.
        if (authPollingTask != null) {
            authPollingTask.cancel(true);
            authPollingTask = null;
        }
        xstsToken = null;
        userHash = null;
        authCookies = null;
        tokenExpiry = 0;
        // httpClient wird bewusst NICHT geschlossen: openHAB ruft bei Config-Updates (und das
        // Binding triggert via updateConfiguration() oft welche – Live-ID-Autofill, Token speichern)
        // dispose()+initialize() auf DERSELBEN Handler-Instanz auf. Ein Schliessen des finalen
        // httpClient wuerde danach jeden Request mit "closed" scheitern lassen.
        super.dispose();
    }

    private void startPolling() {
        stopPolling();
        int interval = resolvePollingInterval();
        logger.debug("Starting status polling every {}s", interval);
        pollingTask = scheduler.scheduleWithFixedDelay(this::checkStatus, 10, interval, TimeUnit.SECONDS);
    }

    /** Liest das Poll-Intervall (Sekunden) aus der Konfiguration; Default 600s, Minimum 5s. */
    private int resolvePollingInterval() {
        int interval = 600;
        Object cfg = getThing().getConfiguration().get(XboxBindingConstants.CONFIG_POLLING_INTERVAL);
        if (cfg instanceof Number) {
            interval = ((Number) cfg).intValue();
        } else if (cfg instanceof String && !((String) cfg).isBlank()) {
            try {
                interval = Integer.parseInt(((String) cfg).trim());
            } catch (NumberFormatException e) {
                logger.warn("Invalid pollingInterval '{}', using default 600s", cfg);
            }
        }
        return Math.max(interval, 5);
    }

    private void stopPolling() {
        if (pollingTask != null) {
            pollingTask.cancel(true);
            pollingTask = null;
        }
    }

    private void checkStatus() {
        // Bevorzugt die Cloud-Status-API (autoritativer Power-State + aktive App). Wenn diese
        // ein Ergebnis liefert, ist der lokale Probe nicht noetig. Faellt sie aus (kein Token,
        // Netzfehler, RemoteManagement aus), wird lokal weitergeprueft.
        if (updateStatusFromCloud()) {
            return;
        }

        // Lokaler Fallback: Erreichbarkeit ueber Host-UDP-Probe, Presence ueber XSTS-Token.
        boolean online = isAlive();
        if (online) {
            consecutiveFailures = 0;
            if (!isPowerSuppressed(true)) {
                updateChannel(XboxBindingConstants.CHANNEL_POWER, OnOffType.ON);
            }
            fetchPresence();
        } else {
            consecutiveFailures++;
            if (consecutiveFailures >= 3 && !isPowerSuppressed(false)) {
                updateChannel(XboxBindingConstants.CHANNEL_POWER, OnOffType.OFF);
                updateChannel(XboxBindingConstants.CHANNEL_CURRENT_TITLE, new StringType("Offline"));
            }
        }
    }

    /**
     * Returns true while a power command is still settling and the console has not yet reached
     * the requested state. During this window the poll must not flip the Power channel.
     * Clears the pending state once the console confirms it or the grace period expires.
     */
    private boolean isPowerSuppressed(boolean observedOn) {
        Boolean pending = pendingPowerState;
        if (pending == null) {
            return false;
        }
        if (System.currentTimeMillis() > pendingPowerUntil) {
            pendingPowerState = null; // Frist abgelaufen -> wieder dem echten Status folgen
            return false;
        }
        if (pending.booleanValue() == observedOn) {
            pendingPowerState = null; // Wunschzustand erreicht -> Grace beenden, Wert uebernehmen
            return false;
        }
        return true; // noch im Uebergang -> abweichenden Wert unterdruecken
    }

    private void initiateAuthentication() {
        String clientId = resolveClientId();
        logger.info("Initializing Xbox OAuth Device Flow (login.live.com) with client_id={}", clientId);
        try {
            String form = "client_id=" + enc(clientId) + "&scope=" + enc(SCOPE) + "&response_type=device_code";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(LIVE_DEVICE_CODE_URL))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(BodyPublishers.ofString(form))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        try {
                            String body = response.body();
                            logger.debug("Device code response ({}): {}", response.statusCode(), body);
                            // Session-Cookies fuer das spaetere Token-Polling merken.
                            authCookies = extractCookies(response);
                            JsonObject json = JsonParser.parseString(body).getAsJsonObject();

                            if (json.has("error")) {
                                String error = json.get("error").getAsString();
                                String desc  = json.has("error_description")
                                        ? json.get("error_description").getAsString() : error;
                                logger.error("Xbox auth device-code request failed: {}", desc);
                                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                                        "Auth failed: " + error);
                                return;
                            }

                            String userCode       = json.get("user_code").getAsString();
                            String verificationUri = json.get("verification_uri").getAsString();
                            String deviceCode     = json.get("device_code").getAsString();
                            int    interval       = json.get("interval").getAsInt();

                            // QR-Inhalt: Verifikations-URL mit vorbefuelltem Code (otc), damit das
                            // Handy nach dem Scannen den Code nicht mehr manuell braucht.
                            String qrUrl = verificationUri + "?otc=" + userCode;

                            Configuration config = editConfiguration();
                            config.put(XboxBindingConstants.CONFIG_AUTHENTICATION_URL, qrUrl);
                            config.put(XboxBindingConstants.CONFIG_USER_CODE, userCode);
                            config.put(XboxBindingConstants.CONFIG_DEVICE_CODE, deviceCode);
                            updateConfiguration(config);

                            logger.info("Xbox auth: scan QR / go to {} and enter code: {}", verificationUri, userCode);
                            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING,
                                    "Scan QR or go to " + verificationUri + " – Code: " + userCode);
                            startAuthPolling(deviceCode, interval);
                        } catch (Exception e) {
                            logger.error("Error processing auth device-code response: {}", e.getMessage(), e);
                            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                                    "Auth error: " + e.getMessage());
                        }
                    })
                    .exceptionally(e -> {
                        logger.error("HTTP request to Microsoft device-code endpoint failed: {}", e.getMessage(), e);
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                                "Cannot reach Microsoft auth endpoint: " + e.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            logger.error("Failed to initiate Xbox authentication: {}", e.getMessage(), e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Auth init failed: " + e.getMessage());
        }
    }

    private void startAuthPolling(String deviceCode, int interval) {
        if (authPollingTask != null) {
            authPollingTask.cancel(true);
        }
        authPollingTask = scheduler.scheduleWithFixedDelay(() -> pollAuthToken(deviceCode), interval, interval,
                TimeUnit.SECONDS);
    }

    private void pollAuthToken(String deviceCode) {
        String clientId = resolveClientId();
        try {
            String form = "grant_type=urn:ietf:params:oauth:grant-type:device_code"
                    + "&client_id=" + enc(clientId) + "&device_code=" + enc(deviceCode);
            // client_id zusaetzlich als Query-Param – so macht es auch die Referenz-Implementierung.
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(LIVE_TOKEN_URL + "?client_id=" + enc(clientId)))
                    .header("Content-Type", "application/x-www-form-urlencoded");
            // Die in der device-code-Antwort gesetzten Cookies zwingend zuruecksenden.
            String cookies = authCookies;
            if (cookies != null && !cookies.isEmpty()) {
                builder.header("Cookie", cookies);
            }
            HttpRequest request = builder.POST(BodyPublishers.ofString(form)).build();

            @SuppressWarnings("null")
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();

            if (json.has("error")) {
                String error = json.get("error").getAsString();
                // "authorization_pending"/"slow_down" sind normal, solange der Nutzer den Code
                // noch eingibt -> einfach weiter pollen. Harte Fehler beenden das Polling.
                if (!"authorization_pending".equals(error) && !"slow_down".equals(error)) {
                    String desc = json.has("error_description") ? json.get("error_description").getAsString() : error;
                    if ("authorization_declined".equals(error) || "expired_token".equals(error)
                            || "invalid_grant".equals(error)) {
                        logger.error("Xbox auth failed: {}", desc);
                        if (authPollingTask != null) {
                            authPollingTask.cancel(true);
                        }
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                                "Auth failed: " + error);
                    } else {
                        logger.warn("Xbox auth poll returned: {}", desc);
                    }
                }
                return;
            }

            if (json.has("access_token")) {
                String refreshToken = json.get("refresh_token").getAsString();
                Configuration config = editConfiguration();
                config.put(XboxBindingConstants.CONFIG_REFRESH_TOKEN, refreshToken);
                config.put(XboxBindingConstants.CONFIG_AUTHENTICATION_URL, "");
                config.put(XboxBindingConstants.CONFIG_USER_CODE, "Authenticated!");
                config.put(XboxBindingConstants.CONFIG_DEVICE_CODE, "");
                updateConfiguration(config);

                if (authPollingTask != null) {
                    authPollingTask.cancel(true);
                }
                updateStatus(ThingStatus.ONLINE);
                updateChannel(XboxBindingConstants.CHANNEL_STATUS, new StringType("Authenticated"));
                logger.info("Xbox successfully authenticated!");
                // Direkt XSTS-Token holen und Status pollen, damit Power/Title sofort kommen.
                scheduler.execute(() -> {
                    refreshXstsToken();
                    checkStatus();
                });
            }
        } catch (Exception e) {
            logger.error("Error polling for Xbox token: {}", e.getMessage());
        }
    }

    private void fetchPresence() {
        if (System.currentTimeMillis() > tokenExpiry || xstsToken == null) {
            refreshXstsToken();
        }
        if (xstsToken == null) {
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(java.net.URI.create("https://userpresence.xboxlive.com/users/me?level=all"))
                    .header("Authorization", "XBL3.0 x=" + userHash + ";" + xstsToken)
                    .header("x-xbl-contract-version", "3")
                    .header("Accept", "application/json")
                    .header("Accept-Language", "en-US")
                    .GET()
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(HttpResponse::body)
                    .thenAccept(this::applyPresence);
        } catch (Exception e) {
            logger.error("Error fetching Xbox presence: {}", e.getMessage());
        }
    }

    /**
     * Wertet die Presence-Antwort aus und setzt currentTitle (+ Cover). Struktur laut MS-Doku:
     * {@code { devices:[ { titles:[ { id, name, state:"active", placement:"full|fill|snapped" } ] } ] }}
     * – die Titel liegen unter {@code devices[].titles[]} (NICHT top-level). Gesucht wird der aktive
     * Vordergrund-Titel (placement full/fill); "snapped" ist nur die angedockte Neben-App.
     */
    private void applyPresence(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String foregroundName = null;
            String foregroundId = null;
            String anyActiveName = null;
            String anyActiveId = null;
            if (json.has("devices") && json.get("devices").isJsonArray()) {
                JsonArray devices = json.getAsJsonArray("devices");
                outer: for (int d = 0; d < devices.size(); d++) {
                    JsonObject dev = devices.get(d).getAsJsonObject();
                    if (!dev.has("titles") || !dev.get("titles").isJsonArray()) {
                        continue;
                    }
                    JsonArray titles = dev.getAsJsonArray("titles");
                    for (int t = 0; t < titles.size(); t++) {
                        JsonObject title = titles.get(t).getAsJsonObject();
                        String state = title.has("state") ? title.get("state").getAsString() : "";
                        if (!"active".equalsIgnoreCase(state)) {
                            continue;
                        }
                        String name = title.has("name") ? title.get("name").getAsString() : "";
                        if (name.isEmpty()) {
                            continue;
                        }
                        String id = title.has("id") ? title.get("id").getAsString() : "";
                        String placement = title.has("placement") ? title.get("placement").getAsString() : "";
                        if ("full".equalsIgnoreCase(placement) || "fill".equalsIgnoreCase(placement)) {
                            foregroundName = name;
                            foregroundId = id;
                            break outer; // aktiver Vordergrund-Titel -> fertig
                        }
                        if (anyActiveName == null) {
                            anyActiveName = name;
                            anyActiveId = id;
                        }
                    }
                }
            }
            String name = foregroundName != null ? foregroundName : anyActiveName;
            String id = foregroundId != null ? foregroundId : anyActiveId;
            if (name != null) {
                updateChannel(XboxBindingConstants.CHANNEL_CURRENT_TITLE, new StringType(name));
                if (id != null && !id.equals(currentTitleId)) {
                    currentTitleId = id;
                    fetchCoverArt(id);
                }
            } else {
                // kein aktiver Titel -> Dashboard
                updateChannel(XboxBindingConstants.CHANNEL_CURRENT_TITLE, new StringType("Home / Dashboard"));
                currentTitleId = "0";
            }
        } catch (Exception e) {
            logger.error("Error parsing Xbox presence: {}", e.getMessage());
        }
    }

    /**
     * Holt Power-State + aktive App ueber die Cloud-API (xccs.xboxlive.com/consoles/{liveId}).
     *
     * @return true, wenn die Cloud eine verwertbare Antwort lieferte (dann sind die Channels
     *         gesetzt); false, wenn nicht (Token fehlt, Live ID fehlt, Netz-/API-Fehler).
     */
    private boolean updateStatusFromCloud() {
        String liveId = (String) getThing().getConfiguration().get(XboxBindingConstants.CONFIG_LIVE_ID);
        if (liveId == null || liveId.isBlank()) {
            return false;
        }
        if (System.currentTimeMillis() > tokenExpiry || xstsToken == null) {
            refreshXstsToken();
        }
        if (xstsToken == null) {
            return false;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(XCCS_URL + "/consoles/" + liveId))
                    .header("Authorization", "XBL3.0 x=" + userHash + ";" + xstsToken)
                    .header("x-xbl-contract-version", "4")
                    .header("skillplatform", "RemoteManagement")
                    .GET()
                    .build();

            @SuppressWarnings("null")
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                logger.debug("Cloud console status HTTP {}: {}", resp.statusCode(), resp.body());
                return false;
            }
            // Kompletten Body loggen, damit die echte JSON-Struktur (insb. storageDevices und deren
            // Feldnamen) nachvollziehbar ist, falls Storage-Channels NULL bleiben.
            logger.debug("Cloud console status raw body: {}", resp.body());
            JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
            // Konsolen-Infos (Name, Modell, Region, ...) als Thing-Properties in die UI uebernehmen.
            updateConsoleProperties(json);
            String powerState = json.has("powerState") ? json.get("powerState").getAsString() : "Unknown";
            boolean on = "On".equalsIgnoreCase(powerState);

            if (isPowerSuppressed(on)) {
                // Grace-Periode laeuft und Konsole hat den Wunschzustand noch nicht erreicht ->
                // Power-Channel NICHT umschalten (verhindert das Zuruckschnappen des Switches).
                logger.debug("Cloud status: powerState={} (suppressed during power transition)", powerState);
                return true;
            }
            updateChannel(XboxBindingConstants.CHANNEL_POWER, OnOffType.from(on));

            if (on) {
                // focusAppAumid aus /consoles/{id} ist bei Spielen leer (per Log bestaetigt). Den
                // aktiven Titel + Cover holen wir daher aus der Presence-API (was der angemeldete
                // Account gerade spielt) – fetchPresence() setzt currentTitle/coverArt selbst.
                fetchPresence();
            } else {
                updateChannel(XboxBindingConstants.CHANNEL_CURRENT_TITLE, new StringType("Offline"));
            }
            // Speicher kommt NICHT aus /consoles/{id} (Body enthaelt keine storageDevices),
            // sondern aus dem separaten /lists/devices-Endpunkt. Dafuer die echte Konsolen-ID
            // aus der Statusantwort verwenden (kann von der konfigurierten Live ID abweichen).
            String consoleId = json.has("id") ? json.get("id").getAsString() : liveId;
            // Speicher immer versuchen: /lists/devices ist ein Cloud-Cache und liefert die zuletzt
            // gemeldeten Speicherwerte oft auch im ConnectedStandby, nicht nur bei powerState=On.
            fetchStorageDevices(consoleId);
            consecutiveFailures = 0;
            logger.debug("Cloud status: powerState={}, focusApp={}", powerState,
                    json.has("focusAppAumid") ? json.get("focusAppAumid").getAsString() : "");
            return true;
        } catch (Exception e) {
            logger.debug("Cloud console status failed, falling back to local probe: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Uebernimmt die statischen Konsolen-Infos aus der xccs-Statusantwort als Thing-Properties.
     * Diese erscheinen in der openHAB-UI im Information-/Properties-Bereich der Thing-Seite
     * (z. B. Konsolenname, Modell, Region, Streaming-/Remote-Management-Status).
     */
    private void updateConsoleProperties(JsonObject json) {
        try {
            Map<String, String> props = editProperties();
            putProp(props, json, "id", "Console ID");
            putProp(props, json, "name", "Console Name");
            putProp(props, json, "consoleType", "Console Type");
            putProp(props, json, "region", "Region");
            putProp(props, json, "locale", "Locale");
            putProp(props, json, "isTvConfigured", "TV Configured");
            putProp(props, json, "consoleStreamingEnabled", "Streaming Enabled");
            putProp(props, json, "remoteManagementEnabled", "Remote Management");
            putProp(props, json, "digitalAssistantRemoteControlEnabled", "Digital Assistant");
            updateProperties(props);
        } catch (Exception e) {
            logger.debug("Could not update console properties: {}", e.getMessage());
        }
    }

    /** Setzt eine Property aus einem JSON-Feld, sofern vorhanden und nicht null. */
    private void putProp(Map<String, String> props, JsonObject json, String field, String label) {
        if (json.has(field) && !json.get(field).isJsonNull()) {
            props.put(label, json.get(field).getAsString());
        }
    }

    /**
     * Holt die internen Speicher-Geraete der Konsole. Strategie (in dieser Reihenfolge):
     *
     * <ol>
     * <li><b>Geraeteliste</b> {@code GET /lists/devices?includeStorageDevices=true}: liefert je
     *     Konsole ein eingebettetes {@code storageDevices}-Array. Das ist ein Cloud-Cache und
     *     enthaelt die zuletzt gemeldeten Werte oft auch im ConnectedStandby – der zuverlaessige
     *     Weg.</li>
     * <li><b>Fallback</b> {@code GET /lists/storageDevices/{deviceId}} (deviceId im PFAD):
     *     dedizierter Endpunkt. Die fruehere Query-Variante {@code ?deviceId=...} lieferte in der
     *     Praxis nur ein leeres {@code result} mit verdoppelter deviceId (Server-seitig falsch
     *     interpretiert) – daher steht die deviceId jetzt im Pfad.</li>
     * </ol>
     */
    private void fetchStorageDevices(String consoleId) {
        if (xstsToken == null) {
            return;
        }
        if (fetchStorageFromDeviceList(consoleId)) {
            return;
        }
        fetchStorageFromStorageEndpoint(consoleId);
    }

    /**
     * Primaerweg: Speicher aus der Geraeteliste lesen. {@code /lists/devices} liefert ein
     * Array von Konsolen (Schluessel {@code result}, alternativ {@code devices}); die passende
     * Konsole (per {@code id}) enthaelt das eingebettete {@code storageDevices}-Array.
     *
     * @return true, wenn Speicherwerte gesetzt werden konnten.
     */
    private boolean fetchStorageFromDeviceList(String consoleId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(
                            XCCS_URL + "/lists/devices?queryCurrentDevice=false&includeStorageDevices=true"))
                    .header("Authorization", "XBL3.0 x=" + userHash + ";" + xstsToken)
                    .header("x-xbl-contract-version", "4")
                    .header("skillplatform", "RemoteManagement")
                    .GET()
                    .build();

            @SuppressWarnings("null")
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                logger.debug("Device list HTTP {}: {}", resp.statusCode(), resp.body());
                return false;
            }
            logger.debug("Device list raw body: {}", resp.body());
            JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
            JsonArray devices = null;
            if (json.has("result") && json.get("result").isJsonArray()) {
                devices = json.getAsJsonArray("result");
            } else if (json.has("devices") && json.get("devices").isJsonArray()) {
                devices = json.getAsJsonArray("devices");
            }
            if (devices == null) {
                logger.debug("Device list response has no console array. Keys: {}", json.keySet());
                return false;
            }
            JsonObject chosen = null;
            for (int i = 0; i < devices.size(); i++) {
                JsonObject dev = devices.get(i).getAsJsonObject();
                if (chosen == null) {
                    chosen = dev; // erste Konsole als Fallback
                }
                String id = dev.has("id") && !dev.get("id").isJsonNull() ? dev.get("id").getAsString() : "";
                if (consoleId.equalsIgnoreCase(id)) {
                    chosen = dev; // exakte Konsole gefunden
                    break;
                }
            }
            if (chosen == null || !chosen.has("storageDevices") || !chosen.get("storageDevices").isJsonArray()) {
                logger.debug("Device list has no storageDevices for console {}.", consoleId);
                return false;
            }
            JsonArray storage = chosen.getAsJsonArray("storageDevices");
            if (storage.size() == 0) {
                logger.debug("Device list storageDevices array empty for console {}.", consoleId);
                return false;
            }
            updateStorage(storage);
            return true;
        } catch (Exception e) {
            logger.debug("Could not fetch storage via device list: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Fallback: dedizierter Speicher-Endpunkt mit deviceId im PFAD (nicht als Query-Param).
     */
    private void fetchStorageFromStorageEndpoint(String consoleId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(XCCS_URL + "/lists/storageDevices/" + enc(consoleId)))
                    .header("Authorization", "XBL3.0 x=" + userHash + ";" + xstsToken)
                    .header("x-xbl-contract-version", "4")
                    .header("skillplatform", "RemoteManagement")
                    .GET()
                    .build();

            @SuppressWarnings("null")
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                logger.debug("Storage devices HTTP {}: {}", resp.statusCode(), resp.body());
                return;
            }
            logger.debug("Storage devices raw body: {}", resp.body());
            JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
            if (!json.has("result") || !json.get("result").isJsonArray()) {
                logger.debug("Storage devices response has no 'result' array. Keys: {}", json.keySet());
                return;
            }
            updateStorage(json.getAsJsonArray("result"));
        } catch (Exception e) {
            logger.debug("Could not fetch storage devices: {}", e.getMessage());
        }
    }

    /**
     * Setzt die Speicher-Channels aus der Geraeteliste. Zwei Ebenen:
     * <ul>
     * <li><b>Aggregat-Channels</b> {@code storageFree/storageTotal/storageUsedPercent}: das interne
     *     Standard-Laufwerk (isDefault) bzw. das erste verfuegbare – bleibt fuer bestehende Items.</li>
     * <li><b>Pro Laufwerk</b>: fuer JEDES erkannte Laufwerk werden dynamisch drei Channels
     *     {@code storageTotalDrive{n}/storageFreeDrive{n}/storageUsedDrive{n}} angelegt und gesetzt.
     *     Der Laufwerksname steht im Channel-Label (taucht so im Item-Vorschlag der UI auf).</li>
     * </ul>
     */
    private void updateStorage(JsonArray devices) {
        try {
            if (devices.size() == 0) {
                logger.debug("Storage devices list empty (console off or no data yet).");
                return;
            }
            // 1) Aggregat-Channels: Standard-/Default-Laufwerk (oder erstes verfuegbare).
            JsonObject chosen = null;
            for (int i = 0; i < devices.size(); i++) {
                JsonObject dev = devices.get(i).getAsJsonObject();
                if (chosen == null) {
                    chosen = dev;
                }
                if (dev.has("isDefault") && dev.get("isDefault").getAsBoolean()) {
                    chosen = dev;
                    break;
                }
            }
            if (chosen != null) {
                logger.debug("Aggregate storage device JSON: {}", chosen);
                applyStorageValues(chosen, XboxBindingConstants.CHANNEL_STORAGE_TOTAL,
                        XboxBindingConstants.CHANNEL_STORAGE_FREE, XboxBindingConstants.CHANNEL_STORAGE_USED);
            }
            // 2) Pro Laufwerk eigene Channels (Total/Free/Used%) anlegen/aktualisieren.
            syncDynamicStorageChannels(devices);
            for (int i = 0; i < devices.size(); i++) {
                int n = i + 1;
                applyStorageValues(devices.get(i).getAsJsonObject(),
                        XboxBindingConstants.CHANNEL_STORAGE_TOTAL_DRIVE + n,
                        XboxBindingConstants.CHANNEL_STORAGE_FREE_DRIVE + n,
                        XboxBindingConstants.CHANNEL_STORAGE_USED_DRIVE + n);
            }
        } catch (Exception e) {
            logger.debug("Could not parse storage devices: {}", e.getMessage());
        }
    }

    /**
     * Liest total/free aus einem Laufwerks-JSON und setzt die uebergebenen Channels (Total/Free als
     * Bytes -> Number:DataAmount, Used als berechnete Prozent). Feldnamen variieren je nach
     * Contract-Version/Region -> moeglichst viele Varianten abdecken.
     */
    private void applyStorageValues(JsonObject dev, String totalCh, String freeCh, String usedCh) {
        double total = readNum(dev, "totalSpaceBytes", "totalSpace", "TotalSpaceBytes", "totalBytes",
                "capacityBytes", "totalSpaceInBytes");
        double free = readNum(dev, "freeSpaceBytes", "freeSpace", "FreeSpaceBytes", "freeBytes",
                "availableSpaceBytes", "freeSpaceInBytes");
        if (total <= 0) {
            logger.debug("Storage device has no usable total-space field (keys: {}).", dev.keySet());
            return;
        }
        updateChannel(totalCh, new QuantityType<>(total + " B"));
        if (free >= 0) {
            updateChannel(freeCh, new QuantityType<>(free + " B"));
            double usedPct = (total - free) / total * 100.0;
            updateChannel(usedCh, new QuantityType<>(usedPct + " %"));
        }
    }

    /** True, wenn die Channel-ID zu einem dynamisch pro Laufwerk angelegten Channel gehoert. */
    private boolean isDynamicStorageChannel(String id) {
        return id.startsWith(XboxBindingConstants.CHANNEL_STORAGE_TOTAL_DRIVE)
                || id.startsWith(XboxBindingConstants.CHANNEL_STORAGE_FREE_DRIVE)
                || id.startsWith(XboxBindingConstants.CHANNEL_STORAGE_USED_DRIVE);
    }

    /**
     * Legt fuer jedes erkannte Laufwerk drei Channels (Total/Free/Used%) an bzw. entfernt sie wieder.
     * Es wird NUR umgebaut, wenn sich die Menge der gewuenschten Laufwerks-Channels geaendert hat
     * (z. B. externe SSD an-/abgesteckt) – sonst kein {@code updateThing} (keine UI-Churn, Item-Links
     * bleiben erhalten). Der Laufwerksname kommt ins Channel-Label, damit er im Item-Vorschlag
     * sichtbar ist; der Live-Name selbst wird nicht als eigener Channel gefuehrt.
     */
    private synchronized void syncDynamicStorageChannels(JsonArray devices) {
        int count = devices.size();
        Set<String> desired = new LinkedHashSet<>();
        for (int i = 1; i <= count; i++) {
            desired.add(XboxBindingConstants.CHANNEL_STORAGE_TOTAL_DRIVE + i);
            desired.add(XboxBindingConstants.CHANNEL_STORAGE_FREE_DRIVE + i);
            desired.add(XboxBindingConstants.CHANNEL_STORAGE_USED_DRIVE + i);
        }
        Set<String> existing = new LinkedHashSet<>();
        for (Channel ch : getThing().getChannels()) {
            String id = ch.getUID().getId();
            if (isDynamicStorageChannel(id)) {
                existing.add(id);
            }
        }
        if (desired.equals(existing)) {
            return; // nichts zu tun
        }
        logger.info("Storage drive count changed to {} – rebuilding per-drive channels.", count);
        ThingBuilder builder = editThing();
        for (Channel ch : getThing().getChannels()) {
            if (isDynamicStorageChannel(ch.getUID().getId())) {
                builder.withoutChannel(ch.getUID());
            }
        }
        for (int i = 0; i < count; i++) {
            JsonObject dev = devices.get(i).getAsJsonObject();
            String name = dev.has("storageDeviceName") && !dev.get("storageDeviceName").isJsonNull()
                    ? dev.get("storageDeviceName").getAsString()
                    : ("Laufwerk " + (i + 1));
            int n = i + 1;
            addDriveChannel(builder, XboxBindingConstants.CHANNEL_STORAGE_TOTAL_DRIVE + n,
                    XboxBindingConstants.CHANNEL_STORAGE_TOTAL, "Number:DataAmount", name + " Total");
            addDriveChannel(builder, XboxBindingConstants.CHANNEL_STORAGE_FREE_DRIVE + n,
                    XboxBindingConstants.CHANNEL_STORAGE_FREE, "Number:DataAmount", name + " Free");
            addDriveChannel(builder, XboxBindingConstants.CHANNEL_STORAGE_USED_DRIVE + n,
                    XboxBindingConstants.CHANNEL_STORAGE_USED, "Number:Dimensionless", name + " Used");
        }
        updateThing(builder.build());
    }

    /** Baut einen einzelnen Laufwerks-Channel und haengt ihn an den ThingBuilder. */
    private void addDriveChannel(ThingBuilder builder, String channelId, String channelTypeId, String itemType,
            String label) {
        ChannelUID uid = new ChannelUID(getThing().getUID(), channelId);
        ChannelTypeUID typeUID = new ChannelTypeUID(XboxBindingConstants.BINDING_ID, channelTypeId);
        Channel channel = ChannelBuilder.create(uid, itemType).withType(typeUID).withLabel(label).build();
        builder.withChannel(channel);
    }

    /** Liest ein numerisches JSON-Feld; probiert mehrere Schluesselnamen, -1 wenn keiner da. */
    private double readNum(JsonObject obj, String... keys) {
        for (String k : keys) {
            if (obj.has(k) && !obj.get(k).isJsonNull()) {
                try {
                    return obj.get(k).getAsDouble();
                } catch (NumberFormatException ignored) {
                    // naechsten Schluessel probieren
                }
            }
        }
        return -1;
    }

    /**
     * Sendet einen Power-Befehl (WakeUp/TurnOff) ueber die Cloud-Command-API.
     */
    private void sendCloudPowerCommand(String command) {
        String liveId = (String) getThing().getConfiguration().get(XboxBindingConstants.CONFIG_LIVE_ID);
        if (liveId == null || liveId.isBlank()) {
            logger.warn("Cannot send '{}': Live ID not known yet (turn console on once so it can be learned).",
                    command);
            return;
        }
        if (System.currentTimeMillis() > tokenExpiry || xstsToken == null) {
            refreshXstsToken();
        }
        if (xstsToken == null) {
            logger.warn("Cannot send '{}': no valid Xbox token.", command);
            return;
        }
        try {
            JsonObject body = new JsonObject();
            body.addProperty("destination", "Xbox");
            body.addProperty("type", "Power");
            body.addProperty("command", command);
            body.addProperty("sessionId", smartglassSessionId);
            body.addProperty("sourceId", "com.microsoft.smartglass");
            JsonArray params = new JsonArray();
            params.add(new JsonObject());
            body.add("parameters", params);
            body.addProperty("linkedXboxId", liveId);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(XCCS_URL + "/commands"))
                    .header("Authorization", "XBL3.0 x=" + userHash + ";" + xstsToken)
                    .header("x-xbl-contract-version", "4")
                    .header("skillplatform", "RemoteManagement")
                    .header("Content-Type", "application/json")
                    .POST(BodyPublishers.ofString(body.toString()))
                    .build();

            @SuppressWarnings("null")
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 == 2) {
                logger.info("Xbox cloud command '{}' accepted.", command);
            } else {
                logger.warn("Xbox cloud command '{}' failed (HTTP {}): {}", command, resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            logger.error("Error sending cloud power command '{}': {}", command, e.getMessage());
        }
    }

    /**
     * Holt das Box-Art-Cover ueber den titlehub-Endpunkt und setzt den coverArt-Channel. Presence
     * liefert nur titleId + Name, KEIN Bild – das kommt aus
     * {@code titlehub.xboxlive.com/users/xuid({xuid})/titles/titleid({titleId})/decoration/detail,image}.
     */
    private void fetchCoverArt(String titleId) {
        String xid = xuid;
        String token = xstsToken;
        if (token == null || xid == null || titleId.isEmpty() || "0".equals(titleId)) {
            return;
        }
        try {
            String url = "https://titlehub.xboxlive.com/users/xuid(" + xid + ")/titles/titleid(" + titleId
                    + ")/decoration/detail,image";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .header("Authorization", "XBL3.0 x=" + userHash + ";" + token)
                    .header("x-xbl-contract-version", "2")
                    .header("Accept-Language", "en-US")
                    .GET()
                    .build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(HttpResponse::body)
                    .thenAccept(this::applyCoverArt);
        } catch (Exception e) {
            logger.debug("Error fetching cover art (titlehub): {}", e.getMessage());
        }
    }

    /** Liest die Bild-URL aus der titlehub-Antwort (BoxArt/Poster/Tile bzw. displayImage) und laedt sie. */
    private void applyCoverArt(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (!json.has("titles") || !json.get("titles").isJsonArray()) {
                return;
            }
            JsonArray titles = json.getAsJsonArray("titles");
            if (titles.size() == 0) {
                return;
            }
            JsonObject t = titles.get(0).getAsJsonObject();
            String imgUrl = null;
            if (t.has("images") && t.get("images").isJsonArray()) {
                JsonArray images = t.getAsJsonArray("images");
                for (int i = 0; i < images.size(); i++) {
                    JsonObject img = images.get(i).getAsJsonObject();
                    String type = img.has("type") ? img.get("type").getAsString() : "";
                    if ("BoxArt".equalsIgnoreCase(type) || "Poster".equalsIgnoreCase(type)
                            || "Tile".equalsIgnoreCase(type)) {
                        if (img.has("url") && !img.get("url").isJsonNull()) {
                            imgUrl = img.get("url").getAsString();
                            break;
                        }
                    }
                }
            }
            if (imgUrl == null && t.has("displayImage") && !t.get("displayImage").isJsonNull()) {
                imgUrl = t.get("displayImage").getAsString();
            }
            if (imgUrl != null && !imgUrl.isEmpty()) {
                downloadAndSetImage(imgUrl);
            }
        } catch (Exception e) {
            logger.debug("Error parsing cover art (titlehub): {}", e.getMessage());
        }
    }

    private void downloadAndSetImage(String imgUrl) {
        httpClient.sendAsync(HttpRequest.newBuilder().uri(java.net.URI.create(imgUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray())
                .thenAccept(response -> {
                    if (response != null && response.statusCode() == 200) {
                        String contentType = response.headers().firstValue("Content-Type").orElse("image/jpeg");
                        byte[] body = response.body();
                        if (body != null && contentType != null) {
                            RawType rawImage = new RawType(body, contentType);
                            updateChannel(XboxBindingConstants.CHANNEL_COVER_ART, rawImage);
                        }
                    }
                });
    }

    private synchronized void refreshXstsToken() {
        String refreshToken = (String) getThing().getConfiguration().get(XboxBindingConstants.CONFIG_REFRESH_TOKEN);
        if (refreshToken == null || refreshToken.isEmpty()) {
            return;
        }

        try {
            // 1. Get OAuth Access Token
            String clientId = resolveClientId();
            HttpRequest oauthReq = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(LIVE_TOKEN_URL))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(BodyPublishers.ofString("grant_type=refresh_token&client_id=" + enc(clientId)
                            + "&scope=" + enc(SCOPE) + "&refresh_token=" + enc(refreshToken)))
                    .build();

            @SuppressWarnings("null")
            HttpResponse<String> oauthRes = httpClient.send(oauthReq, HttpResponse.BodyHandlers.ofString());
            JsonObject oauthJson = JsonParser.parseString(oauthRes.body()).getAsJsonObject();
            String accessToken = oauthJson.get("access_token").getAsString();

            // 2. Xbox User Token
            JsonObject userAuthBody = new JsonObject();
            JsonObject props = new JsonObject();
            props.addProperty("AuthMethod", "RPS");
            props.addProperty("SiteName", "user.auth.xboxlive.com");
            // login.live.com-Token (MBI_SSL) wird mit "t=" geprefixt; "d=" gilt nur fuer
            // Azure-Access-Tokens. Falscher Prefix -> XBL-User-Auth schlaegt fehl.
            props.addProperty("RpsTicket", "t=" + accessToken);
            userAuthBody.add("Properties", props);
            userAuthBody.addProperty("RelyingParty", "http://auth.xboxlive.com");
            userAuthBody.addProperty("TokenType", "JWT");

            HttpRequest userReq = HttpRequest.newBuilder()
                    .uri(java.net.URI.create("https://user.auth.xboxlive.com/user/authenticate"))
                    .header("Content-Type", "application/json")
                    .POST(BodyPublishers.ofString(userAuthBody.toString()))
                    .build();

            @SuppressWarnings("null")
            HttpResponse<String> userRes = httpClient.send(userReq, HttpResponse.BodyHandlers.ofString());
            JsonObject userJson = JsonParser.parseString(userRes.body()).getAsJsonObject();
            String userToken = userJson.get("Token").getAsString();
            userHash = userJson.get("DisplayClaims").getAsJsonObject().getAsJsonArray("xui").get(0).getAsJsonObject()
                    .get("uhs").getAsString();

            // 3. XSTS Token
            JsonObject xstsBody = new JsonObject();
            JsonObject xstsProps = new JsonObject();
            JsonArray tokens = new JsonArray();
            tokens.add(userToken);
            xstsProps.add("UserTokens", tokens);
            xstsProps.addProperty("SandboxId", "RETAIL");
            xstsBody.add("Properties", xstsProps);
            xstsBody.addProperty("RelyingParty", "http://xboxlive.com");
            xstsBody.addProperty("TokenType", "JWT");

            HttpRequest xstsReq = HttpRequest.newBuilder()
                    .uri(java.net.URI.create("https://xsts.auth.xboxlive.com/xsts/authorize"))
                    .header("Content-Type", "application/json")
                    .POST(BodyPublishers.ofString(xstsBody.toString()))
                    .build();

            @SuppressWarnings("null")
            HttpResponse<String> xstsRes = httpClient.send(xstsReq, HttpResponse.BodyHandlers.ofString());
            JsonObject xstsJson = JsonParser.parseString(xstsRes.body()).getAsJsonObject();
            if (!xstsJson.has("Token")) {
                logger.error("XSTS token request failed ({}): {}", xstsRes.statusCode(), xstsRes.body());
                return;
            }
            xstsToken = xstsJson.get("Token").getAsString();
            // XUID aus den XSTS-DisplayClaims merken (fuer presence/titlehub-Cover). uhs kommt schon
            // vom User-Token; xid steht erst im XSTS-Token.
            try {
                JsonObject xui = xstsJson.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0)
                        .getAsJsonObject();
                if (xui.has("xid")) {
                    xuid = xui.get("xid").getAsString();
                }
            } catch (Exception ignored) {
                // xid ist optional – ohne XUID entfaellt nur der Cover-Art-Abruf
            }
            tokenExpiry = System.currentTimeMillis() + 3600000; // 1 hour buffer
            logger.debug("XSTS token obtained, userHash={}, xuid={}, presence enabled", userHash, xuid);

        } catch (Exception e) {
            logger.error("Failed to refresh XSTS token: {}", e.getMessage());
        }
    }

    private boolean isAlive() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(2000);
            socket.setBroadcast(true);
            InetAddress address = getConsoleAddress();
            byte[] discovery = XboxBindingConstants.discoveryPacket();
            socket.send(new DatagramPacket(discovery, discovery.length, address, 5050));

            // Auf eine gueltige Discovery-Antwort (0xDD 0x01) warten. Fremde/eigene Broadcast-
            // Pakete werden ignoriert. Aus der Antwort wird – falls noch nicht bekannt – die
            // Live ID nachgezogen (steckt im Zertifikat der Antwort).
            long deadline = System.currentTimeMillis() + 2000;
            byte[] buf = new byte[2048];
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                socket.receive(resp);
                byte[] d = resp.getData();
                if (resp.getLength() < 2 || d[0] != (byte) 0xDD || d[1] != (byte) 0x01) {
                    continue;
                }
                ensureLiveId(d, resp.getLength());
                return true;
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Zieht die Live ID aus einer Discovery-Antwort (Zertifikat) nach und speichert sie in die
     * Konfiguration, falls noch keine gesetzt ist. So lernt das Binding die fuer das Power-On
     * benoetigte Live ID automatisch, sobald die Konsole einmal eingeschaltet war.
     */
    private void ensureLiveId(byte[] data, int len) {
        String current = (String) getThing().getConfiguration().get(XboxBindingConstants.CONFIG_LIVE_ID);
        if (current != null && !current.isBlank()) {
            return;
        }
        String liveId = LiveIdUtil.parseLiveId(data, len);
        if (liveId != null && !liveId.isBlank()) {
            Configuration config = editConfiguration();
            config.put(XboxBindingConstants.CONFIG_LIVE_ID, liveId);
            updateConfiguration(config);
            logger.info("Auto-filled Xbox Live ID from certificate: {}", liveId);
        }
    }

    /**
     * Loest die konfigurierte Host-Adresse der Konsole auf. Faellt auf den Broadcast
     * 255.255.255.255 zurueck, wenn kein Host gesetzt ist oder die Aufloesung scheitert.
     */
    private InetAddress getConsoleAddress() {
        String host = (String) getThing().getConfiguration().get(XboxBindingConstants.CONFIG_HOST);
        try {
            if (host != null && !host.isEmpty()) {
                return InetAddress.getByName(host);
            }
            return InetAddress.getByName("255.255.255.255");
        } catch (IOException e) {
            logger.warn("Could not resolve host '{}', falling back to broadcast: {}", host, e.getMessage());
            try {
                return InetAddress.getByName("255.255.255.255");
            } catch (IOException e2) {
                return InetAddress.getLoopbackAddress();
            }
        }
    }

    /**
     * Sendet das SmartGlass Power-On-Paket (Typ 0xDD02) mit der Live ID. Eine schlafende Xbox
     * (Instant-On) wacht nur durch dieses Paket auf, nicht durch ein klassisches WoL-Magic-Packet.
     * Das Paket geht mehrfach an die Konsolen-Adresse UND an den Broadcast.
     */
    private void triggerXboxPowerOn(String liveId) {
        logger.info("Triggering Xbox Power On for Live ID {}", liveId);
        try {
            byte[] idBytes = liveId.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            // Payload = SGString(liveId): uint16-Laenge (big-endian) + ASCII-Bytes. KEIN
            // Null-Terminator! (Frueher wurde faelschlich ein 0x00 angehaengt und in die
            // Laenge gezaehlt -> Payload 19 statt 18 Byte; strenge Firmware verwarf das Paket,
            // die Konsole wachte nicht auf.) Entspricht jetzt xbox-smartglass-core.
            byte[] payload = new byte[2 + idBytes.length];
            payload[0] = (byte) ((idBytes.length >> 8) & 0xFF);
            payload[1] = (byte) (idBytes.length & 0xFF);
            System.arraycopy(idBytes, 0, payload, 2, idBytes.length);
            // Header: DD 02 | payloadLen (uint16) | Version (uint16 = 0).
            byte[] packet = new byte[6 + payload.length];
            packet[0] = (byte) 0xDD;
            packet[1] = 0x02;
            packet[2] = (byte) ((payload.length >> 8) & 0xFF);
            packet[3] = (byte) (payload.length & 0xFF);
            packet[4] = 0;
            packet[5] = 0;
            System.arraycopy(payload, 0, packet, 6, payload.length);

            InetAddress consoleAddr = getConsoleAddress();
            InetAddress broadcast = InetAddress.getByName("255.255.255.255");
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setBroadcast(true);
                for (int i = 0; i < 5; i++) {
                    socket.send(new DatagramPacket(packet, packet.length, consoleAddr, 5050));
                    socket.send(new DatagramPacket(packet, packet.length, broadcast, 5050));
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                logger.info("Xbox Power On packets sent (LiveID {})", liveId);
            }
        } catch (IOException e) {
            logger.error("Error sending Xbox Power On packet: {}", e.getMessage());
        }
    }

}