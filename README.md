# Xbox Binding

This binding integrates Microsoft Xbox consoles (Xbox One, Series S, Series X) into openHAB. It controls power, launches apps and games, and monitors the console's status, the currently running title (with cover art) and internal/external storage — using the local SmartGlass protocol together with the Xbox Live cloud API.

Sign-in happens once via a QR code with your Microsoft account; no Azure app registration is required.

## Features

- **Remote power on** (local SmartGlass wake packet + cloud) and **power off** (cloud)
- **App / game launch** by name or Microsoft Store product ID, with a pre-filled, editable app list
- **Status monitoring**: power state, current title / game, and its cover art
- **Storage monitoring**: free / total / used percentage for the internal drive, plus dynamically created channels for every attached drive (e.g. an external SSD)
- **Auto-discovery** of consoles on the local network, including automatic detection of the console's Live ID
- **QR-code sign-in** (Microsoft device-code flow) — no Azure registration and no manual token handling

## Supported Things

| Thing type | Description |
|------------|-------------|
| `xbox`     | A single Xbox console (Xbox One, Series S, Series X) |

> **Xbox 360 is not supported.** It uses a different, older SmartGlass protocol and is not reachable through the Xbox Live cloud (RemoteManagement) API this binding relies on. Only Xbox One and Xbox Series S/X consoles work.

## Prerequisites

On the console:

- **Instant-on** power mode must be enabled (Settings -> General -> Power options -> *Instant-on*) so the console can be woken remotely.
- **Remote features** must be enabled (Settings -> Devices & connections -> Remote features) and the console must be signed in with the same Microsoft account you use for the binding.
- Turn the console on at least once while the binding is running, so it can learn the **Live ID** (required for remote wake). The Live ID is then stored and shown as the *Console ID* property.

## Discovery

The binding scans the local network for consoles via the SmartGlass protocol (UDP port 5050). Discovered consoles appear in the Inbox with their name and IP address; the host and Live ID are filled in automatically. After adding the Thing, complete the one-time sign-in (see [Authentication](#authentication)).

## Thing Configuration

### `xbox`

| Parameter           | Type             | Advanced | Default   | Description |
|---------------------|------------------|----------|-----------|-------------|
| `host`              | text             | no       | —         | IP address or hostname of the console. Leave empty to use a network broadcast. |
| `authenticationUrl` | text             | no       | —         | QR code for the Microsoft sign-in. Filled automatically a few seconds after the Thing is created. |
| `userCode`          | text (read-only) | no       | —         | Verification code, shown in case you cannot scan the QR code. Filled automatically. |
| `clientId`          | text             | yes      | —         | Optional. Your own registered Microsoft/Azure application ID. Leave empty to use the built-in Xbox app sign-in (recommended). |
| `refreshToken`      | text (password)  | yes      | —         | Microsoft OAuth refresh token. Filled automatically after the QR sign-in. |
| `deviceCode`        | text             | yes      | —         | Internal OAuth device code (managed by the binding). |
| `pollingInterval`   | integer (s)      | yes      | `600`     | How often the console status is polled, in seconds. Minimum `5`. |
| `appList`           | text (multiple)  | yes      | see below | The apps offered on the *Launch App* channel, one entry per line as `Name=StoreProductId`. Pre-filled with sensible defaults — just add or remove a line. |

The **Live ID** is *not* a manual parameter: it is detected automatically from the console and exposed as the *Console ID* property.

Default `appList`:

```
Netflix=9WZDNCRFJ3TJ
Disney+=9NXQXXLFST89
Prime Video=9P6RC76MSMMJ
Spotify=9NCBCSZSJRSB
Twitch=9PFJP1Q9R4FK
Apple TV=9MW0ZWQFH0M2
HBO Max=9PJJ1K9DZMRS
Plex=9WZDNCRFJ3Q8
```
<img width="922" height="515" alt="grafik" src="https://github.com/user-attachments/assets/8cbb51fa-9539-457f-bbdc-538fdc27af25" />

## Channels

| Channel              | Item type            | Access | Description |
|----------------------|----------------------|--------|-------------|
| `power`              | Switch               | RW     | Power state. `ON` wakes the console, `OFF` shuts it down. |
| `status`             | String               | R      | Authentication / connection status. |
| `launch`             | String               | W      | Launch an app or game. Send an app name from the list or a raw Store product ID. |
| `currentTitle`       | String               | R      | Name of the currently running app or game. |
| `coverArt`           | Image                | R      | Cover art of the current title. |
| `storageFree`        | Number:DataAmount    | R      | Free space on the default (internal) drive. |
| `storageTotal`       | Number:DataAmount    | R      | Total size of the default (internal) drive. |
| `storageUsedPercent` | Number:Dimensionless | R      | Used space of the default drive, in percent. |

### Per-drive storage channels

For every drive the console reports (internal storage plus any external drive), the binding creates three additional channels at runtime, numbered in order of detection. The drive's name is shown in the channel label, so it is easy to identify when linking items.

| Channel (n = 1, 2, ...) | Item type            | Description |
|-------------------------|----------------------|-------------|
| `storageTotalDrive{n}`  | Number:DataAmount    | Total size of drive *n*. |
| `storageFreeDrive{n}`   | Number:DataAmount    | Free space on drive *n*. |
| `storageUsedDrive{n}`   | Number:Dimensionless | Used space on drive *n*, in percent. |

Storage values are reported in bytes; the default state patterns display them as GiB (`%.1f GiB`) and percent (`%.0f %%`).

**Showing GB instead of GiB:** the binding emits raw bytes, so the displayed unit is set entirely by the state pattern. For decimal gigabytes use a `GB` pattern — per item (no rebuild):

```java
Number:DataAmount Xbox_StorageFree "Free [%.1f GB]" { channel="xbox:xbox:livingroom:storageFree" }
```

Or change `%.1f GiB` to `%.1f GB` on the `storageFree` / `storageTotal` channel types in `thing-types.xml` to make it the binding default (requires a rebuild). Note: the Xbox console shows binary values labeled "GB", so the default `GiB` pattern already matches the number on the console (e.g. 802 GiB); true decimal `GB` yields a larger number (e.g. 861) that will not match the console screen.

## Properties

When the console is reachable, the following read-only properties are shown on the Thing page: *Console ID*, *Console Name*, *Console Type*, *Region*, *Locale*, *TV Configured*, *Streaming Enabled*, *Remote Management* and *Digital Assistant*.

## Authentication

The binding signs in with your Microsoft account using the device-code flow and a built-in Xbox client ID — no Azure registration needed.

1. Create the Thing (via discovery or manually). After a few seconds the **Login QR-Code** appears in the Thing configuration.
2. Scan it with your phone (the code is pre-filled), or open `https://www.microsoft.com/link` on any device and enter the **Verification Code**.
3. Confirm the sign-in. The binding stores the refresh token automatically and the Thing goes **ONLINE**.

The presence information (current title / cover art) reflects what the **signed-in account** is doing on the console. If a different account is playing, its title is not shown.

## App Launch

Send a value to the `launch` channel:

- an **app name** exactly as listed in `appList` (e.g. `Netflix`), or
- a raw **Store product ID** (e.g. `9WZDNCRFJ3TJ`) for any app not in the list.

In the UI the channel offers a drop-down built from `appList`. To add an app, find its Store product ID — it is the last segment of the app's Microsoft Store URL, `https://apps.microsoft.com/detail/<ID>` — and add a `Name=ID` line to `appList`. App launch runs through the Xbox Live cloud, so the console must have remote features enabled and be reachable by the cloud.

## Full Example

### `xbox.things`

```java
Thing xbox:xbox:livingroom "Xbox Series X" [
    host="192.168.1.50",
    pollingInterval=600,
    appList=[ "Netflix=9WZDNCRFJ3TJ", "Disney+=9NXQXXLFST89", "Prime Video=9P6RC76MSMMJ" ]
]
```

> After creating the Thing, complete the one-time QR sign-in (the **Login QR-Code** appears in the Thing config). Because authentication is interactive, creating the Thing through the UI is usually the easiest path.

### `xbox.items`

```java
Switch                Xbox_Power        "Power"               { channel="xbox:xbox:livingroom:power" }
String                Xbox_Status       "Status [%s]"         { channel="xbox:xbox:livingroom:status" }
String                Xbox_Launch       "Launch App"          { channel="xbox:xbox:livingroom:launch" }
String                Xbox_Title        "Now Playing [%s]"    { channel="xbox:xbox:livingroom:currentTitle" }
Image                 Xbox_Cover        "Cover Art"           { channel="xbox:xbox:livingroom:coverArt" }
Number:DataAmount     Xbox_StorageFree  "Free [%.1f GiB]"     { channel="xbox:xbox:livingroom:storageFree" }
Number:DataAmount     Xbox_StorageTotal "Total [%.1f GiB]"    { channel="xbox:xbox:livingroom:storageTotal" }
Number:Dimensionless  Xbox_StorageUsed  "Used [%.0f %%]"      { channel="xbox:xbox:livingroom:storageUsedPercent" }
```

### `xbox.sitemap`

```perl
sitemap xbox label="Xbox" {
    Frame label="Console" {
        Switch    item=Xbox_Power
        Selection item=Xbox_Launch
        Text      item=Xbox_Status
    }
    Frame label="Now Playing" {
        Text  item=Xbox_Title
        Image item=Xbox_Cover
    }
    Frame label="Storage" {
        Text item=Xbox_StorageFree
        Text item=Xbox_StorageTotal
        Text item=Xbox_StorageUsed
    }
}
```

## Notes & Limitations

- A classic Wake-on-LAN magic packet does **not** wake an Xbox; the binding uses the SmartGlass power-on packet plus a cloud wake. Instant-on must be enabled.
- The cloud wake occasionally returns a Microsoft-side `ErrorCallingWNS`; the local SmartGlass packet is the primary wake path and works independently of it.
- App launch and power-off rely on the Xbox Live cloud (remote features). Beyond wake and discovery, local-only control is not supported by current console firmware without the encrypted channel.
- Status reaction time depends on `pollingInterval` (default 600 s); after a power or launch command the binding additionally polls again within a few seconds.

## Building

This is a standalone Maven module. From the binding folder:

```
mvn clean install
```

The bundle is written to `target/org.openhab.binding.xbox-<version>.jar`. Copy it into your openHAB `addons` folder.
