package org.openhab.binding.xbox.internal;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.BodyPublishers;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * The {@link XboxHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author t09
 */
@NonNullByDefault
public class XboxHandler extends BaseThingHandler {

    @SuppressWarnings("null")
    private final Logger logger = LoggerFactory.getLogger(XboxHandler.class);
    private @Nullable ScheduledFuture<?> pollingTask;
    private final Object authLock = new Object();
    private @Nullable ScheduledFuture<?> authPollingTask;
    // Schuetzt editConfiguration()/updateConfiguration() gegen Lost-Update-Races zwischen
    // Auth-Callbacks, Token-Rotation und Live-ID-Lernen. Lock-Reihenfolge: tokenLock -> configLock.
    private final Object configLock = new Object();
    // Sammel-Poll fuer REFRESH-Bursts (s. requestPoll) und Sperre, damit nie zwei Polls parallel
    // laufen (Poll-Intervall, REFRESH und Nach-Befehl-Checks koennen zusammentreffen).
    private final Object pollLock = new Object();
    private @Nullable ScheduledFuture<?> refreshJob;
    private final AtomicBoolean pollRunning = new AtomicBoolean();
    // Kam waehrend eines laufenden Polls eine weitere Anfrage, wird danach ein Poll nachgeholt.
    private final AtomicBoolean pollAgain = new AtomicBoolean();
    private static final long REFRESH_DELAY_MS = 2000;
    // Nach dispose() (Thing geloescht) duerfen noch geplante Tasks/Callbacks nichts mehr aktualisieren.
    private volatile boolean disposed = false;
    private volatile int consecutiveFailures = 0;
    // Ohne Timeouts wartet Javas HttpClient unbegrenzt – ein haengender Request blockierte dann einen
    // Thread des gemeinsamen thingHandler-Pools (5 Threads fuer ALLE Bindings).
    private static final long HTTP_TIMEOUT_SECONDS = 20;
    @SuppressWarnings("null")
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS)).build();
    // Token-Zustand, geschuetzt durch tokenLock. Bewusst NICHT der Handler-Monitor: BaseThingHandler
    // synchronisiert updateState & Co. auf this – ein Refresh mit Netzwerk-I/O wuerde sie blockieren.
    private final Object tokenLock = new Object();
    private volatile @Nullable String xstsToken;
    private volatile @Nullable String userHash;
    private volatile @Nullable String xuid;
    private volatile long tokenExpiry = 0;
    private volatile long tokenObtainedAt = 0;
    // Nach einem fehlgeschlagenen Refresh nicht vor diesem Zeitpunkt erneut versuchen (Backoff).
    private volatile long tokenRetryAfter = 0;
    private static final long TOKEN_RETRY_MS = 60_000;
    // XSTS-Token gelten ~16 h (NotAfter); 5 Min vorher erneuern. Ohne NotAfter: nach 1 h.
    private static final long TOKEN_REFRESH_MARGIN_MS = 5 * 60_000;
    private static final long TOKEN_FALLBACK_LIFETIME_MS = 3_600_000;
    private volatile @Nullable String currentTitleId;
    // Erwarteter Power-Zustand nach einem Schaltbefehl + Ablaufzeitpunkt der "Grace-Periode".
    // Solange die Konsole den Uebergang (Boot ~Sekunden, Shutdown ~1 Min) noch nicht vollzogen
    // hat, wuerde der Poll den gerade gesetzten Wert kurz zuruckschnappen lassen ("nervoeser"
    // Switch). Innerhalb der Grace-Periode halten wir den Wunschzustand, bis die Konsole ihn
    // bestaetigt oder die Frist ablaeuft.
    private volatile @Nullable Boolean pendingPowerState;
    private volatile long pendingPowerUntil = 0;
    private static final long POWER_GRACE_MS = 90_000;
    // Das Power-On-Paket geht mehrfach raus (UDP ist verlustbehaftet), zeitversetzt per scheduler
    // statt Thread.sleep – sonst blockiert handleCommand bzw. ein Pool-Thread.
    private static final int POWER_ON_REPEATS = 5;
    private static final long POWER_ON_REPEAT_MS = 500;
    // Session-Cookies (z. B. uaid) aus der device-code-Antwort. login.live.com verlangt, dass
    // sie beim Token-Polling zurueckgeschickt werden, sonst wird der abgeschlossene Login nicht
    // erkannt (Fehler invalid_grant "user must first sign in"). Javas CookieManager macht das
    // bei host==domain nicht zuverlaessig, daher fangen wir die Cookies manuell ab.
    private volatile @Nullable String authCookies;
    // Laufender Device-Code-Login: der Code steckt nur im Polling-Task (bewusst NICHT mehr in der
    // Config): er gilt ~15 Min und braucht die Session-Cookies oben – nach einem Neustart waere er tot.
    private volatile long deviceCodeIssuedAt = 0;
    // Aktuelles Poll-Intervall des Device-Code-Logins; "slow_down" erhoeht es um 5 s (RFC 8628 §3.5).
    private volatile int authPollInterval = 5;
    // Laeuft ein Code schneller ab, wird NICHT automatisch ein neuer angefordert (Schutz vor einer
    // Endlosschleife, falls Microsoft sofort expired_token liefert).
    private static final long MIN_DEVICE_CODE_LIFETIME_MS = 60_000;
    // Hinweis "keine/mehrere Konsolen im Account" nur einmal pro initialize() loggen.
    private volatile boolean liveIdHintLogged = false;

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
    @SuppressWarnings("null")
    private final String smartglassSessionId = java.util.UUID.randomUUID().toString();

    // Microsoft-Account- (Live-Connect-) Endpunkte fuer den Device-Code-Flow.
    // oauth20_connect.srf liefert user_code + device_code; oauth20_token.srf tauscht/erneuert.
    private static final String LIVE_DEVICE_CODE_URL = "https://login.live.com/oauth20_connect.srf";
    private static final String LIVE_TOKEN_URL = "https://login.live.com/oauth20_token.srf";

    private final XboxStateDescriptionProvider stateDescriptionProvider;

    /**
     * Eintrag der App-Liste: Anzeigename (Dropdown) + Store-Product-ID (oneStoreProductId). Bewusst
     * kein record: der wuerde java.lang.runtime importieren, das ein Karaf-System-Bundle evtl. nicht
     * exportiert (vgl. java.net.http in bnd.bnd) – das Bundle liesse sich dann nicht aufloesen.
     */
    static final class AppEntry {
        private final String name;
        private final String productId;

        AppEntry(String name, String productId) {
            this.name = name;
            this.productId = productId;
        }

        String name() {
            return name;
        }

        String productId() {
            return productId;
        }
    }

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
    @SuppressWarnings("null") // URLEncoder ist nicht null-annotiert, liefert aber nie null
    private static String enc(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Extracts the cookie name=value pairs from a response's Set-Cookie headers and joins them
     * into a single Cookie header value (e.g. "uaid=...; foo=bar").
     */
    @SuppressWarnings("null") // java.net.http ist nicht null-annotiert
    private static String extractCookies(HttpResponse<?> response) {
        StringBuilder sb = new StringBuilder();
        for (String sc : response.headers().allValues("set-cookie")) {
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
        // ausloest. openHAB schickt REFRESH auch selbst: BaseThingHandler.channelLinked() ruft fuer
        // jeden verknuepften Channel handleCommand(REFRESH) auf – requestPoll() fasst das zusammen.
        if (command instanceof RefreshType) {
            logger.debug("REFRESH requested for channel '{}'", channelUID.getId());
            requestPoll(REFRESH_DELAY_MS);
            return;
        }
        if (channelUID.getId().equals(XboxBindingConstants.CHANNEL_POWER)) {
            if (OnOffType.ON.equals(command)) {
                powerOn();
            } else if (OnOffType.OFF.equals(command)) {
                powerOff();
            }
        } else if (channelUID.getId().equals(XboxBindingConstants.CHANNEL_LAUNCH)) {
            if (command instanceof StringType app) {
                launchApp(app.toFullString());
            }
        }
    }

    /**
     * Plant einen Poll ein und fasst Anfragen zusammen: nach dem Initialisieren kommt fuer JEDEN
     * verknuepften Channel ein REFRESH – das ergibt hier genau einen Poll statt einem pro Item. Ist
     * schon ein Poll fruehestens zum gewuenschten Zeitpunkt geplant, passiert nichts; ein spaeter
     * geplanter (z. B. Token-Retry) wird vorgezogen. dispose() bricht den geplanten Poll ab.
     */
    private void requestPoll(long delayMs) {
        synchronized (pollLock) {
            if (disposed) {
                return;
            }
            ScheduledFuture<?> job = refreshJob;
            if (job != null && !job.isDone()) {
                long remaining = job.getDelay(TimeUnit.MILLISECONDS);
                if (remaining > 0 && remaining <= delayMs) {
                    return; // frueher oder gleichzeitig geplant -> reicht
                }
                if (remaining > delayMs) {
                    job.cancel(false); // spaeter geplant -> vorziehen
                }
                // remaining <= 0: laeuft bereits und kann veraltet sein -> zusaetzlich neu planen
            }
            refreshJob = scheduler.schedule(this::checkStatus, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Zentrale State-Aktualisierung mit Logging. Jeder Channel-Update landet so sichtbar im
     * KARAF-Log (DEBUG-Level fuer den Binding-Logger aktivieren:
     * {@code log:set DEBUG org.openhab.binding.xbox}), damit nachvollziehbar ist, dass und mit
     * welchem Wert jeder Channel gepollt wurde.
     */
    private void updateChannel(String channelId, State state) {
        if (disposed) {
            return; // spaeter Callback nach dispose() – sonst Warnung "handler already disposed"
        }
        logger.debug("Polling channel '{}' -> {}", channelId, state);
        updateState(channelId, state);
    }

    /**
     * Live ID der Konsole. Quellen in dieser Reihenfolge: Config (intern gelernt bzw. aeltere Things),
     * sonst die Thing-Property aus der Discovery – die Inbox legt nicht deklarierte Discovery-Werte
     * als Property ab, NICHT in der Config. Fehlt beides, lernt der Poll sie (lokal oder per Cloud).
     */
    private @Nullable String getLiveId() {
        Object configured = getThing().getConfiguration().get(XboxBindingConstants.CONFIG_LIVE_ID);
        if (configured instanceof String id && !id.isBlank()) {
            return id.trim();
        }
        String discovered = lookup(getThing().getProperties(), XboxBindingConstants.CONFIG_LIVE_ID);
        return discovered != null && !discovered.isBlank() ? discovered.trim() : null;
    }

    /**
     * Map-Lookup, dessen Ergebnis als nullable typisiert ist. java.util.Map ist nicht null-annotiert;
     * ein direktes {@code get()} auf {@code Map<String, String>} wuerde der Compiler als @NonNull lesen.
     */
    private static @Nullable String lookup(Map<String, ? extends @Nullable String> map, String key) {
        return map.get(key);
    }

    private void powerOn() {
        String liveId = getLiveId();

        // Eine Xbox wacht NICHT per klassischem WoL-Magic-Packet auf, sondern nur per
        // SmartGlass Power-On-Paket (0xDD02), das die Live ID enthaelt. Voraussetzung: in den
        // Xbox-Energieoptionen "Energiesparmodus" -> "Sofort starten" (Instant-On) aktiv.
        if (liveId == null) {
            logger.warn("Cannot power on Xbox: Live ID not yet known. It is learned from discovery, from "
                    + "the console's local reply or from the Xbox cloud console list.");
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
            // Status kurz danach nachziehen – geplant statt Thread.sleep (Pool-Thread bleibt frei).
            scheduler.schedule(this::checkStatus, 3, TimeUnit.SECONDS);
        });
    }

    /** Merkt den nach einem Schaltbefehl gewuenschten Power-Zustand und zeigt ihn sofort an. */
    private void setPendingPower(boolean on) {
        pendingPowerState = on;
        pendingPowerUntil = System.currentTimeMillis() + POWER_GRACE_MS;
        updateChannel(XboxBindingConstants.CHANNEL_POWER, OnOffType.from(on));
    }

    private void launchApp(String appNameOrId) {
        String liveId = getLiveId();
        if (liveId == null) {
            logger.warn("Cannot launch app {}: Live ID not known yet.", appNameOrId);
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
        for (AppEntry app : resolveAppList()) {
            if (app.name().equalsIgnoreCase(appNameOrId)) {
                return app.productId();
            }
        }
        return appNameOrId; // schon eine Store-Product-ID
    }

    /**
     * Liefert die effektive App-Liste. Quelle: Thing-Config {@code appList}; ist nichts Verwertbares
     * konfiguriert, gelten die eingebauten Defaults aus {@link #buildDefaultApps()}.
     */
    private List<AppEntry> resolveAppList() {
        List<AppEntry> apps = parseAppList(getThing().getConfiguration().get(XboxBindingConstants.CONFIG_APP_LIST));
        return apps.isEmpty() ? buildDefaultApps() : apps;
    }

    /**
     * Parst die Thing-Config {@code appList}: Liste (Formular/YAML) oder Text, Eintraege
     * "Name=StoreProductId" je Zeile bzw. komma-getrennt. Ungueltige Eintraege werden uebersprungen;
     * kommt ein Name doppelt vor, gilt die spaetere Zeile. Reihenfolge bleibt erhalten.
     */
    static List<AppEntry> parseAppList(@Nullable Object cfg) {
        List<AppEntry> apps = new ArrayList<>();
        if (cfg instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String entry) {
                    addAppEntry(apps, entry);
                }
            }
        } else if (cfg instanceof String text) {
            for (String entry : text.split("[,\\n]")) {
                addAppEntry(apps, entry);
            }
        }
        return apps;
    }

    private static void addAppEntry(List<AppEntry> apps, @Nullable String entry) {
        if (entry == null) {
            return;
        }
        int eq = entry.indexOf('=');
        if (eq <= 0) {
            return;
        }
        String name = entry.substring(0, eq).trim();
        String id = entry.substring(eq + 1).trim();
        if (!name.isEmpty() && !id.isEmpty()) {
            apps.removeIf(app -> app.name().equals(name));
            apps.add(new AppEntry(name, id));
        }
    }

    /**
     * Eingebaute Standard-App-Liste (Name -> Store-Product-ID / oneStoreProductId), Stand 2026-06.
     * Per Thing-Config {@code appList} vollstaendig ueberschreibbar (die Defaults sind dort als
     * {@code <default>} vorausgefuellt, daher reicht meist Zeile hinzufuegen/entfernen).
     */
    private static List<AppEntry> buildDefaultApps() {
        List<AppEntry> apps = new ArrayList<>();
        apps.add(new AppEntry("Netflix", "9WZDNCRFJ3TJ"));
        apps.add(new AppEntry("Disney+", "9NXQXXLFST89"));
        apps.add(new AppEntry("Prime Video", "9P6RC76MSMMJ"));
        apps.add(new AppEntry("Spotify", "9NCBCSZSJRSB"));
        apps.add(new AppEntry("Twitch", "9PFJP1Q9R4FK"));
        apps.add(new AppEntry("Apple TV", "9MW0ZWQFH0M2"));
        apps.add(new AppEntry("HBO Max", "9PJJ1K9DZMRS"));
        apps.add(new AppEntry("Plex", "9WZDNCRFJ3Q8"));
        return apps;
    }

    /**
     * Fuellt die Auswahlliste (StateOptions) des launch-Channels aus der App-Liste. Option-Wert =
     * Anzeigename (wird beim Start per {@link #resolveStoreProductId} zur ID aufgeloest). So ist das
     * Dropdown automatisch befuellt; die Liste ist in der Thing-Config {@code appList} ohne Rebuild
     * aenderbar (Config-Aenderung loest dispose()+initialize() aus).
     */
    private void updateLaunchOptions() {
        List<StateOption> options = new ArrayList<>();
        for (AppEntry app : resolveAppList()) {
            options.add(new StateOption(app.name(), app.name()));
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
        String auth = xblAuthHeader();
        if (auth == null) {
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
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                    .header("Authorization", auth)
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
            if (resp.statusCode() == 401) {
                handleUnauthorized("Xbox cloud app-launch");
            }
            logger.warn("Xbox cloud app-launch '{}' failed (HTTP {}): {}", productId, resp.statusCode(),
                    resp.body());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            logger.error("Error launching app '{}' via cloud: {}", productId, e.getMessage());
            return false;
        }
    }

    @Override
    public void initialize() {
        disposed = false; // dispose()+initialize() laufen bei Config-Aenderungen auf derselben Instanz
        updateStatus(ThingStatus.UNKNOWN);
        removeObsoleteConfig();
        updateLaunchOptions();
        String refreshToken = (String) getThing().getConfiguration().get(XboxBindingConstants.CONFIG_REFRESH_TOKEN);

        if (refreshToken != null && !refreshToken.isEmpty()) {
            // Status bleibt UNKNOWN, bis der erste Poll den XSTS-Token geholt hat (-> ONLINE, oder
            // OFFLINE mit konkretem Grund). Frueher stand hier sofort ONLINE – auch bei totem Token.
            updateChannel(XboxBindingConstants.CHANNEL_STATUS, new StringType("Authenticating"));
        } else {
            // Kein Token: immer einen FRISCHEN Device-Code holen (s. Feld deviceCodeIssuedAt).
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING, "Authentication required");
            updateChannel(XboxBindingConstants.CHANNEL_STATUS, new StringType("Authentication required"));
            initiateAuthentication();
        }
        // Pollt auch ohne Login: die lokale Probe liefert Power (UDP-Erreichbarkeit) und lernt die Live ID.
        startPolling();
    }

    /**
     * Aendert die Thing-Konfiguration atomar unter {@code configLock}, damit gleichzeitige
     * Updates (Auth-Callback, Token-Rotation, Live-ID-Lernen) sich nicht ueberschreiben.
     */
    private void modifyConfiguration(Consumer<Configuration> mutator) {
        synchronized (configLock) {
            if (disposed) {
                return;
            }
            Configuration config = editConfiguration();
            mutator.accept(config);
            updateConfiguration(config);
        }
    }

    /** Entfernt den frueheren Config-Parameter {@code deviceCode} aus bestehenden Things. */
    private void removeObsoleteConfig() {
        synchronized (configLock) {
            if (!disposed && getThing().getConfiguration().containsKey(XboxBindingConstants.CONFIG_DEVICE_CODE)) {
                Configuration config = editConfiguration();
                config.remove(XboxBindingConstants.CONFIG_DEVICE_CODE);
                updateConfiguration(config);
            }
        }
    }

    @Override
    public void dispose() {
        disposed = true;
        stopPolling();
        stopAuthPolling(true);
        synchronized (pollLock) {
            ScheduledFuture<?> job = refreshJob;
            if (job != null) {
                job.cancel(true);
                refreshJob = null;
            }
        }
        // Token-/Login-Zustand verwerfen, damit wiederholtes Anlegen/Loeschen sauber bleibt.
        // Bewusst ohne tokenLock, damit dispose() nie auf einen laufenden HTTP-Refresh blockiert.
        xstsToken = null;
        userHash = null;
        tokenExpiry = 0;
        tokenObtainedAt = 0;
        tokenRetryAfter = 0;
        authCookies = null;
        liveIdHintLogged = false;
        // httpClient wird bewusst NICHT geschlossen: aendert der NUTZER die Config bzw. das Thing,
        // ruft openHAB dispose()+initialize() auf DERSELBEN Handler-Instanz auf – ein geschlossener
        // finaler httpClient liesse danach jeden Request mit "closed" scheitern. (Eigene
        // updateConfiguration()-Aufrufe des Bindings loesen dagegen KEIN dispose/initialize aus.)
        super.dispose();
    }

    private void startPolling() {
        stopPolling();
        int interval = resolvePollingInterval();
        logger.debug("Starting status polling every {}s", interval);
        // Erster Poll nach 1 s: holt den Token und liefert sofort Werte (Default-Intervall 600 s).
        pollingTask = scheduler.scheduleWithFixedDelay(this::checkStatus, 1, interval, TimeUnit.SECONDS);
    }

    /** Liest das Poll-Intervall (Sekunden) aus der Konfiguration; Default 600s, Minimum 5s. */
    private int resolvePollingInterval() {
        int interval = 600;
        Object cfg = getThing().getConfiguration().get(XboxBindingConstants.CONFIG_POLLING_INTERVAL);
        if (cfg instanceof Number number) {
            interval = number.intValue();
        } else if (cfg instanceof String text && !text.isBlank()) {
            try {
                interval = Integer.parseInt(text.trim());
            } catch (NumberFormatException e) {
                logger.warn("Invalid pollingInterval '{}', using default 600s", cfg);
            }
        }
        return Math.max(interval, 5);
    }

    private void stopPolling() {
        ScheduledFuture<?> task = pollingTask;
        if (task != null) {
            task.cancel(true);
            pollingTask = null;
        }
    }

    private void checkStatus() {
        if (disposed) {
            return;
        }
        // Nie zwei Polls gleichzeitig. Kommt eine Anfrage waehrend eines laufenden Polls, wird sie
        // gemerkt und danach nachgeholt – NICHT verworfen: der laufende Poll kann veraltet sein (z. B.
        // vor dem Login gestartet), sonst bliebe das Thing bis zum naechsten Intervall UNKNOWN.
        if (!pollRunning.compareAndSet(false, true)) {
            pollAgain.set(true);
            logger.debug("Status poll already running – polling again afterwards");
            return;
        }
        try {
            pollStatus();
        } finally {
            pollRunning.set(false);
        }
        if (pollAgain.getAndSet(false)) {
            requestPoll(REFRESH_DELAY_MS);
        }
    }

    private void pollStatus() {
        // Bevorzugt die Cloud-Status-API (autoritativer Power-State + aktive App). Wenn diese
        // ein Ergebnis liefert, ist der lokale Probe nicht noetig. Faellt sie aus (kein Token,
        // Netzfehler, RemoteManagement aus), wird lokal weitergeprueft.
        if (updateStatusFromCloud() || Thread.currentThread().isInterrupted()) {
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

    // ---- Login (Device-Code-Flow gegen login.live.com) ---------------------------------------

    private void initiateAuthentication() {
        String clientId = resolveClientId();
        logger.info("Initializing Xbox OAuth Device Flow (login.live.com) with client_id={}", clientId);
        try {
            String form = "client_id=" + enc(clientId) + "&scope=" + enc(SCOPE) + "&response_type=device_code";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(LIVE_DEVICE_CODE_URL))
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(BodyPublishers.ofString(form))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (disposed) {
                            return; // Thing inzwischen entfernt
                        }
                        try {
                            String body = response.body();
                            logger.debug("Device code response ({}): {}", response.statusCode(), body);
                            // Session-Cookies fuer das spaetere Token-Polling merken.
                            authCookies = extractCookies(response);
                            JsonObject json = parseObject(body);
                            if (json == null) {
                                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                                        "Unexpected device-code response (HTTP " + response.statusCode() + ")");
                                scheduleAuthRetry();
                                return;
                            }

                            String error = jsonString(json, "error");
                            if (error != null) {
                                String desc = jsonString(json, "error_description");
                                logger.error("Xbox auth device-code request failed: {}", desc != null ? desc : error);
                                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                                        "Auth failed: " + error);
                                return;
                            }

                            String userCode = jsonString(json, "user_code");
                            String verificationUri = jsonString(json, "verification_uri");
                            String code = jsonString(json, "device_code");
                            if (userCode == null || verificationUri == null || code == null) {
                                logger.error("Incomplete Xbox auth device-code response: {}", body);
                                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                                        "Incomplete device-code response from Microsoft");
                                scheduleAuthRetry();
                                return;
                            }
                            double reportedInterval = readNum(json, "interval");
                            int interval = reportedInterval >= 1 ? (int) reportedInterval : 5;
                            deviceCodeIssuedAt = System.currentTimeMillis();

                            // QR-Inhalt: Verifikations-URL mit vorbefuelltem Code (otc), damit das
                            // Handy nach dem Scannen den Code nicht mehr manuell braucht.
                            String qrUrl = verificationUri + "?otc=" + userCode;

                            modifyConfiguration(config -> {
                                config.put(XboxBindingConstants.CONFIG_AUTHENTICATION_URL, qrUrl);
                                config.put(XboxBindingConstants.CONFIG_USER_CODE, userCode);
                            });

                            logger.info("Xbox auth: scan QR / go to {} and enter code: {}", verificationUri, userCode);
                            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING,
                                    "Scan QR or go to " + verificationUri + " – Code: " + userCode);
                            startAuthPolling(code, interval);
                        } catch (Exception e) {
                            logger.error("Error processing auth device-code response: {}", e.getMessage(), e);
                            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                                    "Auth error: " + e.getMessage());
                        }
                    })
                    .exceptionally(e -> {
                        if (disposed) {
                            return null;
                        }
                        logger.error("HTTP request to Microsoft device-code endpoint failed: {}", e.getMessage(), e);
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                                "Cannot reach Microsoft auth endpoint: " + e.getMessage());
                        scheduleAuthRetry();
                        return null;
                    });
        } catch (Exception e) {
            logger.error("Failed to initiate Xbox authentication: {}", e.getMessage(), e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Auth init failed: " + e.getMessage());
        }
    }

    /** Startet (bzw. ersetzt) das Device-Code-Polling; darf auch aus dem Poll-Task selbst kommen. */
    private void startAuthPolling(String code, int interval) {
        synchronized (authLock) {
            if (disposed) {
                return;
            }
            stopAuthPolling(false);
            authPollInterval = interval;
            authPollingTask = scheduler.scheduleWithFixedDelay(() -> pollAuthToken(code), interval, interval,
                    TimeUnit.SECONDS);
        }
    }

    /**
     * Plant einen erneuten Login-Start nach voruebergehendem Netzfehler ein (z. B. Router noch
     * nicht bereit beim openHAB-Start). Wird von {@link #dispose()} und {@link #startAuthPolling}
     * automatisch abgebrochen.
     */
    private void scheduleAuthRetry() {
        synchronized (authLock) {
            if (disposed) {
                return;
            }
            stopAuthPolling(false);
            authPollingTask = scheduler.schedule(() -> {
                if (!disposed) {
                    String refreshToken = (String) getThing().getConfiguration()
                            .get(XboxBindingConstants.CONFIG_REFRESH_TOKEN);
                    if (refreshToken == null || refreshToken.isEmpty()) {
                        initiateAuthentication();
                    }
                }
            }, TOKEN_RETRY_MS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Beendet das Device-Code-Polling. Aus dem Poll-Task selbst mit {@code false} aufrufen – mit
     * {@code true} wuerde sich der laufende Task selbst unterbrechen (interrupt des eigenen Threads).
     */
    private void stopAuthPolling(boolean mayInterrupt) {
        synchronized (authLock) {
            ScheduledFuture<?> task = authPollingTask;
            if (task != null) {
                task.cancel(mayInterrupt);
                authPollingTask = null;
            }
        }
    }

    private void pollAuthToken(String code) {
        String clientId = resolveClientId();
        try {
            String form = "grant_type=urn:ietf:params:oauth:grant-type:device_code"
                    + "&client_id=" + enc(clientId) + "&device_code=" + enc(code);
            // client_id zusaetzlich als Query-Param – so macht es auch die Referenz-Implementierung.
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(LIVE_TOKEN_URL + "?client_id=" + enc(clientId)))
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                    .header("Content-Type", "application/x-www-form-urlencoded");
            // Die in der device-code-Antwort gesetzten Cookies zwingend zuruecksenden.
            String cookies = authCookies;
            if (cookies != null && !cookies.isEmpty()) {
                builder.header("Cookie", cookies);
            }
            HttpRequest request = builder.POST(BodyPublishers.ofString(form)).build();

            @SuppressWarnings("null")
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (disposed) {
                return; // Thing waehrend des Requests entfernt
            }
            JsonObject json = parseObject(response.body());
            if (json == null) {
                logger.debug("Unexpected token poll response (HTTP {}): {}", response.statusCode(), response.body());
                return;
            }

            String error = jsonString(json, "error");
            if (error != null) {
                handleDeviceCodeError(code, error, jsonString(json, "error_description"));
                return;
            }

            String refreshToken = jsonString(json, "refresh_token");
            if (refreshToken == null || refreshToken.isEmpty()) {
                logger.warn("Xbox sign-in response without refresh token (HTTP {}), fields: {}",
                        response.statusCode(), json.keySet());
                return;
            }
            stopAuthPolling(false);
            modifyConfiguration(config -> {
                config.put(XboxBindingConstants.CONFIG_REFRESH_TOKEN, refreshToken);
                config.put(XboxBindingConstants.CONFIG_AUTHENTICATION_URL, "");
                config.put(XboxBindingConstants.CONFIG_USER_CODE, "Authenticated!");
            });
            // Neuer Login: alten Token-Zustand und Backoff verwerfen, damit sofort ein XSTS-Token kommt.
            synchronized (tokenLock) {
                xstsToken = null;
                tokenExpiry = 0;
                tokenObtainedAt = 0;
                tokenRetryAfter = 0;
            }

            logger.info("Xbox successfully authenticated!");
            updateStatus(ThingStatus.UNKNOWN);
            updateChannel(XboxBindingConstants.CHANNEL_STATUS, new StringType("Authenticated"));
            // Direkt pollen: holt den XSTS-Token (-> ONLINE) und liefert Power/Title sofort.
            scheduler.execute(this::checkStatus);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("Error polling for Xbox token: {}", e.getMessage());
        }
    }

    /**
     * Wertet Fehler beim Device-Code-Polling aus (RFC 8628 + login.live.com-Varianten).
     * "authorization_pending" = Nutzer noch nicht fertig -> weiter pollen; "slow_down" -> langsamer.
     */
    private void handleDeviceCodeError(String code, String error, @Nullable String description) {
        if ("authorization_pending".equals(error)) {
            return;
        }
        if ("slow_down".equals(error)) {
            // RFC 8628 §3.5: Intervall fuer diese und alle weiteren Anfragen um 5 s erhoehen.
            logger.debug("Xbox auth poll: slow_down – increasing interval to {}s", authPollInterval + 5);
            startAuthPolling(code, authPollInterval + 5);
            return;
        }
        if ("expired_token".equals(error)) {
            stopAuthPolling(false);
            if (System.currentTimeMillis() - deviceCodeIssuedAt > MIN_DEVICE_CODE_LIFETIME_MS) {
                // Frueher eine Sackgasse: der tote Code blieb in der Config und wurde nach jedem
                // Neustart erneut gepollt. Jetzt einfach einen neuen Code (= neuen QR) anfordern.
                logger.info("Xbox sign-in code expired – requesting a new one.");
                initiateAuthentication();
            } else {
                signInFailed(error, description);
            }
            return;
        }
        if ("authorization_declined".equals(error) || "access_denied".equals(error) || "invalid_grant".equals(error)
                || "bad_verification_code".equals(error)) {
            stopAuthPolling(false);
            signInFailed(error, description);
            return;
        }
        logger.warn("Xbox auth poll returned: {}", description != null ? description : error);
    }

    /** Login endgueltig gescheitert: QR/Code entfernen und am Thing sagen, wie es weitergeht. */
    private void signInFailed(String error, @Nullable String description) {
        logger.warn("Xbox sign-in failed: {}", description != null ? description : error);
        if (disposed) {
            return;
        }
        modifyConfiguration(config -> {
            config.put(XboxBindingConstants.CONFIG_AUTHENTICATION_URL, "");
            config.put(XboxBindingConstants.CONFIG_USER_CODE, "");
        });
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                "Sign-in failed (" + error + ") – disable and re-enable the Thing to start a new sign-in");
        updateChannel(XboxBindingConstants.CHANNEL_STATUS, new StringType("Sign-in failed: " + error));
    }

    // ---- Presence / aktueller Titel ------------------------------------------------------------

    private void fetchPresence() {
        String auth = xblAuthHeader();
        if (auth == null) {
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(java.net.URI.create("https://userpresence.xboxlive.com/users/me?level=all"))
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                    .header("Authorization", auth)
                    .header("x-xbl-contract-version", "3")
                    .header("Accept", "application/json")
                    .header("Accept-Language", "en-US")
                    .GET()
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (disposed) {
                            return;
                        }
                        String body = response.body();
                        if (response.statusCode() == 200 && body != null) {
                            applyPresence(body);
                        } else if (response.statusCode() == 401) {
                            handleUnauthorized("Presence");
                        } else {
                            logger.debug("Presence HTTP {}: {}", response.statusCode(), body);
                        }
                    })
                    .exceptionally(e -> {
                        logger.debug("Error fetching Xbox presence: {}", e.getMessage());
                        return null;
                    });
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
        JsonObject json = parseObject(body);
        JsonArray devices = json != null ? jsonArray(json, "devices") : null;
        String foregroundName = null;
        String foregroundId = null;
        String anyActiveName = null;
        String anyActiveId = null;
        if (devices != null) {
            outer: for (JsonElement deviceElement : devices) {
                JsonObject dev = asObject(deviceElement);
                JsonArray titles = dev != null ? jsonArray(dev, "titles") : null;
                if (titles == null) {
                    continue;
                }
                for (JsonElement titleElement : titles) {
                    JsonObject title = asObject(titleElement);
                    if (title == null || !"active".equalsIgnoreCase(jsonString(title, "state"))) {
                        continue;
                    }
                    String name = jsonString(title, "name");
                    if (name == null || name.isEmpty()) {
                        continue;
                    }
                    String id = jsonString(title, "id");
                    String placement = jsonString(title, "placement");
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
                fetchCoverArt(id);
            }
        } else {
            // kein aktiver Titel -> Dashboard
            updateChannel(XboxBindingConstants.CHANNEL_CURRENT_TITLE, new StringType("Home / Dashboard"));
            currentTitleId = "0";
        }
    }

    // ---- Konsolen-Status (Cloud) ---------------------------------------------------------------

    /**
     * Holt Power-State + aktive App ueber die Cloud-API (xccs.xboxlive.com/consoles/{liveId}).
     *
     * @return true, wenn die Cloud eine verwertbare Antwort lieferte (dann sind die Channels
     *         gesetzt); false, wenn nicht (Token fehlt, Live ID fehlt, Netz-/API-Fehler).
     */
    private boolean updateStatusFromCloud() {
        String auth = xblAuthHeader();
        if (auth == null) {
            return false;
        }
        String liveId = getLiveId();
        if (liveId == null) {
            // Live ID unbekannt (manuell angelegtes Thing; openHAB sieht die Konsole lokal nicht,
            // z. B. Docker-Bridge oder anderes VLAN): aus der Cloud-Konsolenliste des Accounts lernen.
            liveId = learnLiveIdFromCloud(auth);
            if (liveId == null) {
                return false;
            }
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(XCCS_URL + "/consoles/" + enc(liveId)))
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                    .header("Authorization", auth)
                    .header("x-xbl-contract-version", "4")
                    .header("skillplatform", "RemoteManagement")
                    .GET()
                    .build();

            @SuppressWarnings("null")
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 401) {
                handleUnauthorized("Cloud console status");
                return false;
            }
            JsonObject json = resp.statusCode() == 200 ? parseObject(resp.body()) : null;
            if (json == null) {
                logger.debug("Cloud console status HTTP {}: {}", resp.statusCode(), resp.body());
                return false;
            }
            // Kompletten Body loggen, damit die echte JSON-Struktur nachvollziehbar ist.
            logger.debug("Cloud console status raw body: {}", resp.body());
            // Konsolen-Infos (Name, Modell, Region, ...) als Thing-Properties in die UI uebernehmen.
            updateConsoleProperties(json);
            String powerState = jsonString(json, "powerState");
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
            String consoleId = jsonString(json, "id");
            // Speicher immer versuchen: /lists/devices ist ein Cloud-Cache und liefert die zuletzt
            // gemeldeten Speicherwerte oft auch im ConnectedStandby, nicht nur bei powerState=On.
            fetchStorageDevices(consoleId != null ? consoleId : liveId, auth);
            consecutiveFailures = 0;
            logger.debug("Cloud status: powerState={}, focusApp={}", powerState, jsonString(json, "focusAppAumid"));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            logger.debug("Cloud console status failed, falling back to local probe: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Lernt die Live ID aus der Konsolenliste des Accounts ({@code /lists/devices}). Nur eindeutig:
     * bei genau einer Konsole wird sie uebernommen, bei mehreren wird NICHT geraten (Hinweis im Log).
     */
    private @Nullable String learnLiveIdFromCloud(String auth) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(
                            XCCS_URL + "/lists/devices?queryCurrentDevice=false&includeStorageDevices=false"))
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                    .header("Authorization", auth)
                    .header("x-xbl-contract-version", "4")
                    .header("skillplatform", "RemoteManagement")
                    .GET()
                    .build();

            @SuppressWarnings("null")
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 401) {
                handleUnauthorized("Console list");
                return null;
            }
            JsonObject json = resp.statusCode() == 200 ? parseObject(resp.body()) : null;
            JsonArray consoles = json != null ? jsonArray(json, "result") : null;
            if (consoles == null) {
                logger.debug("Console list HTTP {}: {}", resp.statusCode(), resp.body());
                return null;
            }
            String onlyId = null;
            int count = 0;
            StringBuilder found = new StringBuilder();
            for (JsonElement element : consoles) {
                JsonObject console = asObject(element);
                String id = console != null ? jsonString(console, "id") : null;
                if (console == null || id == null || id.isBlank()) {
                    continue;
                }
                count++;
                onlyId = id;
                String name = jsonString(console, "name");
                if (found.length() > 0) {
                    found.append(", ");
                }
                found.append(name != null ? name : "?").append(" = ").append(id);
            }
            if (count == 1 && onlyId != null) {
                storeLiveId(onlyId, "the Xbox cloud console list");
                return onlyId;
            }
            if (!liveIdHintLogged) {
                liveIdHintLogged = true;
                if (count == 0) {
                    logger.warn("No console found in this Xbox account's device list – is 'Remote features' "
                            + "enabled on the console?");
                } else {
                    logger.warn("The Xbox account has {} consoles ({}). Set 'liveId: <id>' in the Thing "
                            + "configuration (Code tab) to pick one.", count, found);
                }
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            logger.debug("Could not fetch the console list: {}", e.getMessage());
            return null;
        }
    }

    /** Speichert eine gelernte Live ID in der Config (interner Wert, kein Formularfeld). */
    private void storeLiveId(String liveId, String source) {
        modifyConfiguration(config -> config.put(XboxBindingConstants.CONFIG_LIVE_ID, liveId));
        logger.info("Learned Xbox Live ID {} from {}", liveId, source);
    }

    /**
     * Uebernimmt die statischen Konsolen-Infos aus der xccs-Statusantwort als Thing-Properties.
     * Diese erscheinen in der openHAB-UI im Information-/Properties-Bereich der Thing-Seite
     * (z. B. Konsolenname, Modell, Region, Streaming-/Remote-Management-Status).
     */
    private void updateConsoleProperties(JsonObject json) {
        if (disposed) {
            return;
        }
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
        String value = jsonString(json, field);
        if (value != null) {
            props.put(label, value);
        }
    }

    // ---- Speicher ------------------------------------------------------------------------------

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
    private void fetchStorageDevices(String consoleId, String auth) {
        if (fetchStorageFromDeviceList(consoleId, auth)) {
            return;
        }
        fetchStorageFromStorageEndpoint(consoleId, auth);
    }

    /**
     * Primaerweg: Speicher aus der Geraeteliste lesen. {@code /lists/devices} liefert ein
     * Array von Konsolen (Schluessel {@code result}, alternativ {@code devices}); die passende
     * Konsole (per {@code id}) enthaelt das eingebettete {@code storageDevices}-Array.
     *
     * @return true, wenn Speicherwerte gesetzt werden konnten.
     */
    private boolean fetchStorageFromDeviceList(String consoleId, String auth) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(
                            XCCS_URL + "/lists/devices?queryCurrentDevice=false&includeStorageDevices=true"))
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                    .header("Authorization", auth)
                    .header("x-xbl-contract-version", "4")
                    .header("skillplatform", "RemoteManagement")
                    .GET()
                    .build();

            @SuppressWarnings("null")
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 401) {
                handleUnauthorized("Device list");
                return true; // kein Fallback mit demselben ungueltigen Token
            }
            JsonObject json = resp.statusCode() == 200 ? parseObject(resp.body()) : null;
            if (json == null) {
                logger.debug("Device list HTTP {}: {}", resp.statusCode(), resp.body());
                return false;
            }
            logger.debug("Device list raw body: {}", resp.body());
            JsonArray devices = jsonArray(json, "result");
            if (devices == null) {
                devices = jsonArray(json, "devices");
            }
            if (devices == null) {
                logger.debug("Device list response has no console array. Keys: {}", json.keySet());
                return false;
            }
            JsonObject chosen = null;
            for (JsonElement element : devices) {
                JsonObject dev = asObject(element);
                if (dev == null) {
                    continue;
                }
                if (chosen == null) {
                    chosen = dev; // erste Konsole als Fallback
                }
                if (consoleId.equalsIgnoreCase(jsonString(dev, "id"))) {
                    chosen = dev; // exakte Konsole gefunden
                    break;
                }
            }
            JsonArray storage = chosen != null ? jsonArray(chosen, "storageDevices") : null;
            if (storage == null || storage.size() == 0) {
                logger.debug("Device list has no storageDevices for console {}.", consoleId);
                return false;
            }
            updateStorage(storage);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            logger.debug("Could not fetch storage via device list: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Fallback: dedizierter Speicher-Endpunkt mit deviceId im PFAD (nicht als Query-Param).
     */
    private void fetchStorageFromStorageEndpoint(String consoleId, String auth) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(XCCS_URL + "/lists/storageDevices/" + enc(consoleId)))
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                    .header("Authorization", auth)
                    .header("x-xbl-contract-version", "4")
                    .header("skillplatform", "RemoteManagement")
                    .GET()
                    .build();

            @SuppressWarnings("null")
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 401) {
                handleUnauthorized("Storage devices");
                return;
            }
            JsonObject json = resp.statusCode() == 200 ? parseObject(resp.body()) : null;
            if (json == null) {
                logger.debug("Storage devices HTTP {}: {}", resp.statusCode(), resp.body());
                return;
            }
            logger.debug("Storage devices raw body: {}", resp.body());
            JsonArray result = jsonArray(json, "result");
            if (result == null) {
                logger.debug("Storage devices response has no 'result' array. Keys: {}", json.keySet());
                return;
            }
            updateStorage(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.debug("Could not fetch storage devices: {}", e.getMessage());
        }
    }

    /**
     * Setzt die Speicher-Channels aus der Geraeteliste. Zwei Ebenen:
     * <ul>
     * <li><b>Aggregat-Channels</b> {@code storageFree/storageTotal/storageUsedPercent}: das interne
     *     Standard-Laufwerk (isDefault) bzw. das erste verfuegbare – bleibt fuer bestehende Items.</li>
     * <li><b>Pro Laufwerk</b>: je Laufwerk drei Channels
     *     {@code storageTotalDrive{n}/storageFreeDrive{n}/storageUsedDrive{n}} (s.
     *     {@link #syncDynamicStorageChannels}). Abgesteckte Laufwerke behalten ihre Channels, die
     *     Werte werden UNDEF – so verwaisen keine Item-Links.</li>
     * </ul>
     */
    private void updateStorage(JsonArray devices) {
        try {
            List<JsonObject> drives = new ArrayList<>();
            for (JsonElement element : devices) {
                JsonObject dev = asObject(element);
                if (dev != null) {
                    drives.add(dev);
                }
            }
            if (drives.isEmpty()) {
                logger.debug("Storage devices list empty (console off or no data yet).");
                return;
            }
            // 1) Aggregat-Channels: Standard-/Default-Laufwerk (oder erstes verfuegbare).
            JsonObject chosen = null;
            for (JsonObject dev : drives) {
                if (chosen == null) {
                    chosen = dev;
                }
                if ("true".equalsIgnoreCase(jsonString(dev, "isDefault"))) {
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
            int[] slots = syncDynamicStorageChannels(drives);
            Set<Integer> present = new HashSet<>();
            int index = 0;
            for (JsonObject dev : drives) {
                int n = slots[index++];
                present.add(n);
                applyStorageValues(dev, XboxBindingConstants.CHANNEL_STORAGE_TOTAL_DRIVE + n,
                        XboxBindingConstants.CHANNEL_STORAGE_FREE_DRIVE + n,
                        XboxBindingConstants.CHANNEL_STORAGE_USED_DRIVE + n);
            }
            // 3) Bekannte, aber gerade fehlende Laufwerke (z. B. SSD abgesteckt): Werte UNDEF.
            for (Channel ch : getThing().getChannels()) {
                int n = driveNumber(ch.getUID().getId(), XboxBindingConstants.CHANNEL_STORAGE_TOTAL_DRIVE);
                if (n > 0 && !present.contains(n)) {
                    updateChannel(XboxBindingConstants.CHANNEL_STORAGE_TOTAL_DRIVE + n, UnDefType.UNDEF);
                    updateChannel(XboxBindingConstants.CHANNEL_STORAGE_FREE_DRIVE + n, UnDefType.UNDEF);
                    updateChannel(XboxBindingConstants.CHANNEL_STORAGE_USED_DRIVE + n, UnDefType.UNDEF);
                }
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

    /**
     * Legt je Laufwerk drei Channels (Total/Free/Used%) an. Die Nummer n ist STABIL an die
     * storageDeviceId gebunden (Channel-Property {@code storageDeviceId}): ein Reihenfolge-Wechsel
     * in der API vertauscht keine Werte, ein abgestecktes Laufwerk behaelt seine Nummer und Channels.
     * Umgebaut wird nur, wenn ein Laufwerk neu ist bzw. Zuordnung oder Name nicht passen – sonst
     * kein {@code updateThing} (keine UI-Churn, Item-Links bleiben). Der Laufwerksname kommt ins
     * Channel-Label, damit er im Item-Vorschlag der UI sichtbar ist.
     *
     * @return Laufwerksnummer je Eintrag von {@code drives} (gleiche Reihenfolge)
     */
    private int[] syncDynamicStorageChannels(List<JsonObject> drives) {
        // Ist-Zustand aus den Total-Channels: Nummer -> storageDeviceId (null = Alt-Channel ohne
        // Property, vor dieser Version angelegt) bzw. Nummer -> Label.
        Map<Integer, @Nullable String> existingKeys = new HashMap<>();
        Map<Integer, @Nullable String> existingLabels = new HashMap<>();
        for (Channel ch : getThing().getChannels()) {
            int n = driveNumber(ch.getUID().getId(), XboxBindingConstants.CHANNEL_STORAGE_TOTAL_DRIVE);
            if (n > 0) {
                existingKeys.put(n, lookup(ch.getProperties(), XboxBindingConstants.PROPERTY_STORAGE_DEVICE_ID));
                existingLabels.put(n, ch.getLabel());
            }
        }
        List<String> keys = new ArrayList<>();
        int index = 0;
        for (JsonObject dev : drives) {
            keys.add(storageDeviceKey(dev, index++));
        }
        int[] slots = assignDriveSlots(existingKeys, keys);

        boolean rebuild = false;
        index = 0;
        for (JsonObject dev : drives) {
            int n = slots[index];
            String key = storageDeviceKey(dev, index++);
            if (!key.equals(existingKeys.get(n)) || !(driveName(dev, n) + " Total").equals(existingLabels.get(n))) {
                rebuild = true;
            }
        }
        if (!rebuild || disposed) {
            return slots; // nichts zu tun
        }
        logger.info("Storage drives changed ({} present) – updating per-drive channels.", drives.size());
        ThingBuilder builder = editThing();
        index = 0;
        for (JsonObject dev : drives) {
            int n = slots[index];
            String key = storageDeviceKey(dev, index++);
            String name = driveName(dev, n);
            addDriveChannel(builder, XboxBindingConstants.CHANNEL_STORAGE_TOTAL_DRIVE + n,
                    XboxBindingConstants.CHANNEL_STORAGE_TOTAL, "Number:DataAmount", name + " Total", key);
            addDriveChannel(builder, XboxBindingConstants.CHANNEL_STORAGE_FREE_DRIVE + n,
                    XboxBindingConstants.CHANNEL_STORAGE_FREE, "Number:DataAmount", name + " Free", key);
            addDriveChannel(builder, XboxBindingConstants.CHANNEL_STORAGE_USED_DRIVE + n,
                    XboxBindingConstants.CHANNEL_STORAGE_USED, "Number:Dimensionless", name + " Used", key);
        }
        if (!disposed) {
            updateThing(builder.build());
        }
        return slots;
    }

    /**
     * Ordnet Laufwerke stabilen Nummern zu. Regeln: (a) bekannte storageDeviceId behaelt ihre Nummer;
     * (b) Alt-Channels ohne storageDeviceId werden per Position uebernommen (Laufwerk i -> i+1, wie
     * bisher – bestehende Item-Links passen weiter); (c) neue Laufwerke bekommen die kleinste freie
     * Nummer. Nummern fehlender Laufwerke bleiben reserviert (deren Channels zeigen UNDEF).
     *
     * @param existing vorhandene Nummer -> storageDeviceId (null = Alt-Channel ohne Property)
     * @param deviceKeys Kennungen der aktuell gemeldeten Laufwerke in API-Reihenfolge
     * @return Nummer je Eintrag von {@code deviceKeys}
     */
    static int[] assignDriveSlots(Map<Integer, @Nullable String> existing, List<String> deviceKeys) {
        int[] slots = new int[deviceKeys.size()];
        Set<Integer> assigned = new HashSet<>();
        for (Integer n : existing.keySet()) {
            String key = existing.get(n);
            int i = key != null ? deviceKeys.indexOf(key) : -1;
            if (i >= 0 && slots[i] == 0) {
                slots[i] = n;
                assigned.add(n);
            }
        }
        for (int i = 0; i < slots.length; i++) {
            int n = i + 1;
            if (slots[i] == 0 && existing.containsKey(n) && existing.get(n) == null && !assigned.contains(n)) {
                slots[i] = n;
                assigned.add(n);
            }
        }
        int next = 1;
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == 0) {
                while (existing.containsKey(next) || assigned.contains(next)) {
                    next++;
                }
                slots[i] = next;
                assigned.add(next);
            }
        }
        return slots;
    }

    /** Baut einen einzelnen Laufwerks-Channel (ersetzt einen vorhandenen gleicher UID). */
    private void addDriveChannel(ThingBuilder builder, String channelId, String channelTypeId, String itemType,
            String label, String storageDeviceKey) {
        ChannelUID uid = new ChannelUID(getThing().getUID(), channelId);
        ChannelTypeUID typeUID = new ChannelTypeUID(XboxBindingConstants.BINDING_ID, channelTypeId);
        Map<String, String> properties = new HashMap<>();
        properties.put(XboxBindingConstants.PROPERTY_STORAGE_DEVICE_ID, storageDeviceKey);
        Channel channel = ChannelBuilder.create(uid, itemType).withType(typeUID).withLabel(label)
                .withProperties(properties).build();
        builder.withoutChannel(uid);
        builder.withChannel(channel);
    }

    /** Laufwerksnummer aus einer Channel-ID wie {@code storageTotalDrive2}; -1 wenn keine. */
    private static int driveNumber(String channelId, String prefix) {
        if (!channelId.startsWith(prefix)) {
            return -1;
        }
        try {
            return Integer.parseInt(channelId.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Stabile Kennung eines Laufwerks: storageDeviceId, sonst (aeltere Contracts) die Position. */
    private static String storageDeviceKey(JsonObject dev, int index) {
        String id = jsonString(dev, "storageDeviceId");
        return id != null && !id.isBlank() ? id : "drive" + (index + 1);
    }

    static String driveName(JsonObject dev, int n) {
        String name = jsonString(dev, "storageDeviceName");
        return name != null && !name.isBlank() ? name : "Drive " + n;
    }

    /** Liest ein numerisches JSON-Feld; probiert mehrere Schluesselnamen, -1 wenn keiner da. */
    private static double readNum(JsonObject obj, String... keys) {
        for (String k : keys) {
            JsonElement element = obj.get(k);
            if (element != null && element.isJsonPrimitive()) {
                try {
                    return element.getAsDouble();
                } catch (NumberFormatException ignored) {
                    // naechsten Schluessel probieren
                }
            }
        }
        return -1;
    }

    // ---- Cloud-Befehle -------------------------------------------------------------------------

    /**
     * Sendet einen Power-Befehl (WakeUp/TurnOff) ueber die Cloud-Command-API.
     */
    private void sendCloudPowerCommand(String command) {
        String liveId = getLiveId();
        if (liveId == null) {
            logger.warn("Cannot send '{}': Live ID not known yet (turn console on once so it can be learned).",
                    command);
            return;
        }
        String auth = xblAuthHeader();
        if (auth == null) {
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
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                    .header("Authorization", auth)
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
                if (resp.statusCode() == 401) {
                    handleUnauthorized("Xbox cloud command");
                }
                logger.warn("Xbox cloud command '{}' failed (HTTP {}): {}", command, resp.statusCode(), resp.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("Error sending cloud power command '{}': {}", command, e.getMessage());
        }
    }

    // ---- Cover-Art -----------------------------------------------------------------------------

    /**
     * Holt das Box-Art-Cover ueber den titlehub-Endpunkt und setzt den coverArt-Channel. Presence
     * liefert nur titleId + Name, KEIN Bild – das kommt aus
     * {@code titlehub.xboxlive.com/users/xuid({xuid})/titles/titleid({titleId})/decoration/detail,image}.
     */
    private void fetchCoverArt(String titleId) {
        String xid;
        String auth;
        synchronized (tokenLock) {
            xid = xuid;
            String token = xstsToken;
            String uhs = userHash;
            auth = (token != null && uhs != null) ? "XBL3.0 x=" + uhs + ";" + token : null;
        }
        if (auth == null || xid == null || titleId.isEmpty() || "0".equals(titleId)) {
            return;
        }
        try {
            String url = "https://titlehub.xboxlive.com/users/xuid(" + xid + ")/titles/titleid(" + titleId
                    + ")/decoration/detail,image";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                    .header("Authorization", auth)
                    .header("x-xbl-contract-version", "2")
                    .header("Accept-Language", "en-US")
                    .GET()
                    .build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (disposed) {
                            return;
                        }
                        String body = response.body();
                        if (response.statusCode() == 200 && body != null) {
                            applyCoverArt(titleId, body);
                        } else if (response.statusCode() == 401) {
                            handleUnauthorized("Cover art (titlehub)");
                        } else {
                            logger.debug("Cover art (titlehub) HTTP {}: {}", response.statusCode(), body);
                        }
                    })
                    .exceptionally(e -> {
                        logger.debug("Error fetching cover art (titlehub): {}", e.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            logger.debug("Error fetching cover art (titlehub): {}", e.getMessage());
        }
    }

    /** Liest die Bild-URL aus der titlehub-Antwort (BoxArt/Poster/Tile bzw. displayImage) und laedt sie. */
    private void applyCoverArt(String titleId, String body) {
        JsonObject json = parseObject(body);
        JsonArray titles = json != null ? jsonArray(json, "titles") : null;
        JsonObject t = titles != null && titles.size() > 0 ? asObject(titles.get(0)) : null;
        if (t == null) {
            currentTitleId = titleId; // Titel hat keinen titlehub-Eintrag -> nicht bei jedem Poll erneut fragen
            return;
        }
        String imgUrl = null;
        JsonArray images = jsonArray(t, "images");
        if (images != null) {
            for (JsonElement element : images) {
                JsonObject img = asObject(element);
                String type = img != null ? jsonString(img, "type") : null;
                if ("BoxArt".equalsIgnoreCase(type) || "Poster".equalsIgnoreCase(type)
                        || "Tile".equalsIgnoreCase(type)) {
                    imgUrl = img != null ? jsonString(img, "url") : null;
                    if (imgUrl != null) {
                        break;
                    }
                }
            }
        }
        if (imgUrl == null) {
            imgUrl = jsonString(t, "displayImage");
        }
        if (imgUrl != null && !imgUrl.isEmpty()) {
            downloadAndSetImage(titleId, imgUrl);
        } else {
            currentTitleId = titleId; // kein Bild vorhanden -> abgehakt
        }
    }

    private void downloadAndSetImage(String titleId, String imgUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(java.net.URI.create(imgUrl))
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS)).GET().build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                    .thenAccept(response -> {
                        if (disposed) {
                            return;
                        }
                        if (response != null && response.statusCode() == 200) {
                            String contentType = response.headers().firstValue("Content-Type").orElse("image/jpeg");
                            byte[] body = response.body();
                            if (body != null && contentType != null) {
                                RawType rawImage = new RawType(body, contentType);
                                updateChannel(XboxBindingConstants.CHANNEL_COVER_ART, rawImage);
                                currentTitleId = titleId;
                            }
                        } else if (response != null) {
                            logger.debug("Cover image download HTTP {} for {}", response.statusCode(), imgUrl);
                        }
                    })
                    .exceptionally(e -> {
                        logger.debug("Error downloading cover image {}: {}", imgUrl, e.getMessage());
                        return null;
                    });
        } catch (IllegalArgumentException e) {
            logger.debug("Invalid cover image URL '{}': {}", imgUrl, e.getMessage());
        }
    }

    // ---- Token-Kette (MSA -> XBL-User -> XSTS) -------------------------------------------------

    /**
     * Liefert den vollstaendigen {@code XBL3.0}-Authorization-Header aus einem gueltigen Token
     * (erneuert bei Bedarf). Wer auf {@code tokenLock} gewartet hat, prueft erneut und nutzt den
     * Token seines Vorgaengers, statt die Login-Kette zu wiederholen. Nach einem Fehlschlag gilt
     * ein Backoff ({@link #TOKEN_RETRY_MS}). Liest {@code userHash} und {@code xstsToken} atomar.
     *
     * @return {@code "XBL3.0 x=<uhs>;<token>"} oder {@code null}, wenn kein gueltiger Token vorliegt
     */
    private @Nullable String xblAuthHeader() {
        synchronized (tokenLock) {
            long now = System.currentTimeMillis();
            if (xstsToken != null && userHash != null && now < tokenExpiry) {
                return "XBL3.0 x=" + userHash + ";" + xstsToken;
            }
            if (now >= tokenRetryAfter) {
                refreshXstsToken();
            }
            String token = xstsToken;
            String uhs = userHash;
            return (token != null && uhs != null && System.currentTimeMillis() < tokenExpiry)
                    ? "XBL3.0 x=" + uhs + ";" + token
                    : null;
        }
    }

    /**
     * Behandelt HTTP 401 eines Xbox-Live-Endpunkts: Token verwerfen. War der Token schon aelter
     * als {@link #TOKEN_RETRY_MS}, wird zeitnah ein Poll zur Erneuerung geplant; bei einem ganz
     * frischen Token kein Sofort-Retry (Schutz vor einer 401-Endlosschleife).
     */
    private void handleUnauthorized(String context) {
        boolean retrySoon;
        synchronized (tokenLock) {
            retrySoon = xstsToken != null && (System.currentTimeMillis() - tokenObtainedAt) >= TOKEN_RETRY_MS;
            xstsToken = null;
            tokenExpiry = 0;
        }
        logger.debug("{} returned HTTP 401 – Xbox token invalidated{}", context,
                retrySoon ? ", scheduling renewal" : "");
        if (retrySoon) {
            requestPoll(1000);
        }
    }

    /** Fuehrt die Login-Kette aus. Nur ueber {@link #xblAuthHeader()} aufrufen (haelt tokenLock). */
    private void refreshXstsToken() {
        if (disposed) {
            return;
        }
        String refreshToken = (String) getThing().getConfiguration().get(XboxBindingConstants.CONFIG_REFRESH_TOKEN);
        if (refreshToken == null || refreshToken.isEmpty()) {
            return;
        }

        try {
            // 1. Get OAuth Access Token
            String clientId = resolveClientId();
            HttpRequest oauthReq = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(LIVE_TOKEN_URL))
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(BodyPublishers.ofString("grant_type=refresh_token&client_id=" + enc(clientId)
                            + "&scope=" + enc(SCOPE) + "&refresh_token=" + enc(refreshToken)))
                    .build();

            @SuppressWarnings("null")
            HttpResponse<String> oauthRes = httpClient.send(oauthReq, HttpResponse.BodyHandlers.ofString());
            if (disposed) {
                return;
            }
            JsonObject oauthJson = parseObject(oauthRes.body());
            String accessToken = oauthJson != null ? jsonString(oauthJson, "access_token") : null;
            if (oauthJson == null || accessToken == null) {
                // Microsofts Fehler (z. B. invalid_grant + Beschreibung) loggen statt einer NPE.
                String error = oauthJson != null ? jsonString(oauthJson, "error") : null;
                String description = oauthJson != null ? jsonString(oauthJson, "error_description") : null;
                logger.warn("Microsoft token refresh failed (HTTP {}): {} {}", oauthRes.statusCode(), error,
                        description != null ? description : oauthRes.body());
                if ("invalid_grant".equals(error)) {
                    refreshTokenRejected(description);
                } else {
                    authFailed(ThingStatusDetail.COMMUNICATION_ERROR, "Token refresh failed (HTTP "
                            + oauthRes.statusCode() + (error != null ? ", " + error : "") + ")");
                }
                return;
            }
            // Microsoft liefert bei jedem Einloesen einen NEUEN Refresh-Token. Laut RFC 6749 §6 muss
            // der Client den alten dann ersetzen – sonst laeuft der urspruengliche irgendwann ab und
            // der Login stirbt still. Eigene updateConfiguration() loest kein Re-Init aus.
            String rotated = jsonString(oauthJson, "refresh_token");
            if (rotated != null && !rotated.isBlank() && !rotated.equals(refreshToken)) {
                modifyConfiguration(config -> config.put(XboxBindingConstants.CONFIG_REFRESH_TOKEN, rotated));
                logger.debug("Stored rotated Microsoft refresh token");
            }

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
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                    .header("Content-Type", "application/json")
                    .POST(BodyPublishers.ofString(userAuthBody.toString()))
                    .build();

            @SuppressWarnings("null")
            HttpResponse<String> userRes = httpClient.send(userReq, HttpResponse.BodyHandlers.ofString());
            if (disposed) {
                return;
            }
            JsonObject userJson = parseObject(userRes.body());
            String userToken = userJson != null ? jsonString(userJson, "Token") : null;
            String uhs = userJson != null ? displayClaim(userJson, "uhs") : null;
            if (userToken == null || uhs == null) {
                logger.warn("Xbox user authentication failed (HTTP {}): {}", userRes.statusCode(), userRes.body());
                authFailed(ThingStatusDetail.COMMUNICATION_ERROR,
                        "Xbox user authentication failed (HTTP " + userRes.statusCode() + ")");
                return;
            }

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
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                    .header("Content-Type", "application/json")
                    .POST(BodyPublishers.ofString(xstsBody.toString()))
                    .build();

            @SuppressWarnings("null")
            HttpResponse<String> xstsRes = httpClient.send(xstsReq, HttpResponse.BodyHandlers.ofString());
            if (disposed) {
                return;
            }
            JsonObject xstsJson = parseObject(xstsRes.body());
            String token = xstsJson != null ? jsonString(xstsJson, "Token") : null;
            if (xstsJson == null || token == null) {
                // HTTP 401 mit XErr, z. B. 2148916233 = kein Xbox-Profil, 2148916238 = Kinderkonto
                // ohne Freigabe -> Nutzer muss am Account etwas aendern (CONFIGURATION_ERROR).
                String xerr = xstsJson != null ? jsonString(xstsJson, "XErr") : null;
                logger.warn("XSTS token request failed (HTTP {}): {}", xstsRes.statusCode(), xstsRes.body());
                authFailed(xerr != null ? ThingStatusDetail.CONFIGURATION_ERROR : ThingStatusDetail.COMMUNICATION_ERROR,
                        "Xbox Live authorization failed (HTTP " + xstsRes.statusCode()
                                + (xerr != null ? ", XErr " + xerr : "") + ")");
                return;
            }
            userHash = uhs;
            xstsToken = token;
            // XUID aus den XSTS-DisplayClaims merken (fuer presence/titlehub-Cover). uhs kommt schon
            // vom User-Token; xid steht erst im XSTS-Token. Ohne XUID entfaellt nur das Cover.
            String xid = displayClaim(xstsJson, "xid");
            if (xid != null) {
                xuid = xid;
            }
            tokenObtainedAt = System.currentTimeMillis();
            tokenExpiry = tokenRefreshDeadline(jsonString(xstsJson, "NotAfter"));
            tokenRetryAfter = 0;
            logger.debug("XSTS token obtained, userHash={}, xuid={}, renew at {}", userHash, xuid,
                    Instant.ofEpochMilli(tokenExpiry));
            if (getThing().getStatus() != ThingStatus.ONLINE) {
                updateStatus(ThingStatus.ONLINE);
            }
            updateChannel(XboxBindingConstants.CHANNEL_STATUS, new StringType("Authenticated"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.warn("Failed to refresh XSTS token: {}", e.getMessage());
            authFailed(ThingStatusDetail.COMMUNICATION_ERROR, "Cannot reach Xbox Live: " + e.getMessage());
        }
    }

    /**
     * Token-Refresh fehlgeschlagen: Backoff setzen und den Grund am Thing anzeigen. Bei
     * Kommunikationsfehlern einen Wiederholungs-Poll einplanen, damit das Thing nach einem
     * Netzausfall nicht erst beim naechsten Poll-Intervall (Default 600 s) wieder ONLINE geht.
     */
    private void authFailed(ThingStatusDetail detail, String message) {
        tokenRetryAfter = System.currentTimeMillis() + TOKEN_RETRY_MS;
        if (disposed) {
            return;
        }
        updateStatus(ThingStatus.OFFLINE, detail, message);
        updateChannel(XboxBindingConstants.CHANNEL_STATUS, new StringType(message));
        if (detail == ThingStatusDetail.COMMUNICATION_ERROR) {
            // +1 s Puffer: Scheduler nutzt nanoTime, tokenRetryAfter currentTimeMillis – sonst
            // koennte der Retry-Poll 1 ms zu frueh laufen und am Backoff abprallen.
            requestPoll(TOKEN_RETRY_MS + 1000);
        }
    }

    /**
     * Microsoft lehnt den gespeicherten Refresh-Token ab (invalid_grant: abgelaufen, widerrufen,
     * Passwort geaendert). Er ist endgueltig unbrauchbar -> verwerfen und automatisch einen neuen
     * QR-Login starten, statt das Thing still mit totem Token weiterlaufen zu lassen.
     */
    private void refreshTokenRejected(@Nullable String description) {
        logger.warn("Microsoft rejected the stored refresh token ({}) – starting a new sign-in.", description);
        xstsToken = null;
        tokenExpiry = 0;
        tokenObtainedAt = 0;
        if (disposed) {
            return;
        }
        modifyConfiguration(config -> config.put(XboxBindingConstants.CONFIG_REFRESH_TOKEN, ""));
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING,
                "Sign-in expired – scan the new QR code");
        updateChannel(XboxBindingConstants.CHANNEL_STATUS, new StringType("Authentication required"));
        initiateAuthentication();
    }

    /** Liest {@code DisplayClaims.xui[0].<key>} (uhs bzw. xid) aus einer XBL-Token-Antwort. */
    private static @Nullable String displayClaim(JsonObject tokenResponse, String key) {
        JsonObject claims = asObject(tokenResponse.get("DisplayClaims"));
        JsonArray xui = claims != null ? jsonArray(claims, "xui") : null;
        JsonObject first = xui != null && xui.size() > 0 ? asObject(xui.get(0)) : null;
        return first != null ? jsonString(first, key) : null;
    }

    /** Zeitpunkt fuer den naechsten Refresh: kurz vor NotAfter des XSTS-Tokens, sonst in 1 h. */
    static long tokenRefreshDeadline(@Nullable String notAfter) {
        long now = System.currentTimeMillis();
        if (notAfter != null) {
            try {
                long expiresAt = Instant.parse(notAfter).toEpochMilli();
                return Math.max(now + TOKEN_RETRY_MS, expiresAt - TOKEN_REFRESH_MARGIN_MS);
            } catch (DateTimeParseException e) {
                // Fallback unten
            }
        }
        return now + TOKEN_FALLBACK_LIFETIME_MS;
    }

    // ---- Lokales SmartGlass (UDP 5050) ---------------------------------------------------------

    private boolean isAlive() {
        String host = (String) getThing().getConfiguration().get(XboxBindingConstants.CONFIG_HOST);
        boolean broadcast = host == null || host.isBlank();
        InetAddress address = getConsoleAddress();
        if (address == null) {
            return false;
        }
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            byte[] discovery = XboxBindingConstants.discoveryPacket();
            socket.send(new DatagramPacket(discovery, discovery.length, address, 5050));

            // Auf eine gueltige Discovery-Antwort (0xDD 0x01) warten. Fremde/eigene Broadcast-
            // Pakete werden ignoriert. Aus der Antwort wird – falls noch nicht bekannt – die
            // Live ID nachgezogen (steckt im Zertifikat der Antwort). Beim Broadcast (kein host)
            // kann JEDE Konsole im Netz antworten: dann nur lernen, wenn genau eine geantwortet hat.
            long deadline = System.currentTimeMillis() + 2000;
            byte[] buf = new byte[2048];
            String firstIp = null;
            byte[] firstReply = null;
            boolean severalConsoles = false;
            while (!severalConsoles) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                socket.setSoTimeout((int) remaining);
                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(resp);
                } catch (SocketTimeoutException e) {
                    break;
                }
                byte[] d = resp.getData();
                int len = resp.getLength();
                if (len < 2 || d[0] != (byte) 0xDD || d[1] != (byte) 0x01) {
                    continue;
                }
                if (!broadcast) {
                    ensureLiveId(d, len);
                    return true;
                }
                String ip = resp.getAddress().getHostAddress();
                if (firstIp == null) {
                    firstIp = ip;
                    firstReply = new byte[len];
                    System.arraycopy(d, 0, firstReply, 0, len);
                } else if (!firstIp.equals(ip)) {
                    severalConsoles = true;
                }
            }
            if (firstReply == null) {
                return false;
            }
            if (severalConsoles) {
                logger.debug("Several consoles answered the broadcast probe – not learning a Live ID (set 'host').");
            } else {
                ensureLiveId(firstReply, firstReply.length);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Zieht die Live ID aus einer Discovery-Antwort (Zertifikat) nach und speichert sie in die
     * Konfiguration, falls noch keine bekannt ist. So lernt das Binding die fuer das Power-On
     * benoetigte Live ID automatisch, sobald die Konsole einmal eingeschaltet war.
     */
    private void ensureLiveId(byte[] data, int len) {
        if (getLiveId() != null) {
            return;
        }
        String liveId = LiveIdUtil.parseLiveId(data, len);
        if (liveId != null && !liveId.isBlank()) {
            storeLiveId(liveId, "the console's discovery certificate");
        }
    }

    /**
     * Loest die konfigurierte Host-Adresse der Konsole auf. Faellt auf den Broadcast
     * 255.255.255.255 zurueck, wenn kein Host gesetzt ist oder die Aufloesung scheitert.
     */
    private @Nullable InetAddress getConsoleAddress() {
        String host = (String) getThing().getConfiguration().get(XboxBindingConstants.CONFIG_HOST);
        try {
            return InetAddress.getByName(host != null && !host.isBlank() ? host.trim() : "255.255.255.255");
        } catch (IOException e) {
            logger.warn("Could not resolve host '{}', falling back to broadcast: {}", host, e.getMessage());
            try {
                return InetAddress.getByName("255.255.255.255");
            } catch (IOException e2) {
                return null;
            }
        }
    }

    /**
     * Sendet das SmartGlass Power-On-Paket (Typ 0xDD02, s. {@link XboxBindingConstants#powerOnPacket})
     * mit der Live ID. Eine schlafende Xbox (Instant-On) wacht nur durch dieses Paket auf, nicht durch
     * ein klassisches WoL-Magic-Packet. Das Paket geht mehrfach an die Konsolen-Adresse UND an den
     * Broadcast – zeitversetzt eingeplant, damit handleCommand sofort zurueckkehrt.
     */
    private void triggerXboxPowerOn(String liveId) {
        logger.info("Triggering Xbox Power On for Live ID {}", liveId);
        byte[] packet = XboxBindingConstants.powerOnPacket(liveId);
        for (int i = 0; i < POWER_ON_REPEATS; i++) {
            scheduler.schedule(() -> sendPowerOnPacket(packet), i * POWER_ON_REPEAT_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void sendPowerOnPacket(byte[] packet) {
        if (disposed) {
            return;
        }
        InetAddress consoleAddr = getConsoleAddress();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            InetAddress broadcast = InetAddress.getByName("255.255.255.255");
            if (consoleAddr != null && !consoleAddr.equals(broadcast)) {
                socket.send(new DatagramPacket(packet, packet.length, consoleAddr, 5050));
            }
            socket.send(new DatagramPacket(packet, packet.length, broadcast, 5050));
            logger.debug("Xbox Power On packet sent ({} bytes)", packet.length);
        } catch (IOException e) {
            logger.warn("Error sending Xbox Power On packet: {}", e.getMessage());
        }
    }

    // ---- JSON-Helfer: Gson ist nicht null-annotiert; fehlende/JSON-null-Felder werden zu null --

    /** Parst einen JSON-Body zu einem Objekt; null bei leerem/ungueltigem Body oder Nicht-Objekt. */
    private static @Nullable JsonObject parseObject(@Nullable String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return asObject(JsonParser.parseString(body));
        } catch (JsonParseException e) {
            return null;
        }
    }

    /** Liefert das Element als Objekt; null wenn es keines ist. */
    private static @Nullable JsonObject asObject(@Nullable JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    /** Liest ein Feld als String (auch Zahlen/Booleans); null wenn es fehlt oder kein Wert ist. */
    private static @Nullable String jsonString(JsonObject obj, String key) {
        JsonElement element = obj.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    /** Liest ein Array-Feld; null wenn es fehlt oder kein Array ist. */
    private static @Nullable JsonArray jsonArray(JsonObject obj, String key) {
        JsonElement element = obj.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }
}
