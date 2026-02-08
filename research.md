# Research: Photo Capture on Rokid Glasses and Attachment to Next Message

## 1. Rokid Photo Button Event

### Hardware

The Rokid AR Lite glasses have a **12MP POV camera** with Low-Light HDR (LLHDR), capable of up to 1680P @ 30FPS video (`docs/ROKID.md:15-16`). There is **no dedicated physical camera/shutter button** on the glasses. The available physical inputs are: Home, Back, Volume up/down, and a temple touchpad (swipe fwd/bwd, tap, double-tap, long-press) (`docs/ROKID.md:33-36`).

Photos can be triggered by:
- Voice command ("Hi Rokid" wake word, then "take photo") (`docs/ROKID.md:39-40`)
- Programmatic capture via the CXR-M phone SDK (see below)
- The glasses' built-in system camera app (outside our app)

### CXR-M SDK Photo API (Phone-Side)

The phone-side SDK (`com.rokid.cxr:client-m:1.0.8`) exposes photo capture methods on `CxrApi`. These were confirmed by decompiling the SDK AAR from the Gradle cache (`~/.gradle/caches/modules-2/files-2.1/com.rokid.cxr/client-m/1.0.8/`).

**`takeGlassPhoto` — Capture and receive raw bytes:**
```java
CxrStatus takeGlassPhoto(int width, int height, int quality, PhotoResultCallback callback)
```
- `width`/`height`: desired resolution
- `quality`: JPEG compression (1-100)
- Callback: `PhotoResultCallback.onPhotoResult(CxrStatus status, byte[] photoData)`
- The phone sends a command over Bluetooth to the glasses system service, which captures via the hardware camera and returns JPEG bytes back over Bluetooth
- No WiFi P2P required — works over the standard Bluetooth channel

**`takeGlassPhoto` — Capture and receive file path:**
```java
CxrStatus takeGlassPhoto(int width, int height, int quality, PhotoPathCallback callback)
```
- Callback: `PhotoPathCallback.onPhotoPath(CxrStatus status, String path)`
- Returns a file path on the glasses filesystem

**`takeGlassPhotoGlobal` — Capture even when another scene is active:**
```java
CxrStatus takeGlassPhotoGlobal(int width, int height, int quality, PhotoResultCallback callback)
```

### CXR-S SDK (Glasses-Side)

The glasses-side SDK (`com.rokid.cxr:cxr-service-bridge:1.0`) does **not** contain camera control methods. It only provides generic messaging (`sendMessage`/`subscribe`), audio streaming, and connection management. Camera control is exclusively done from the phone side via CXR-M.

### Alternative: Android Camera2 API on Glasses

Since the glasses run full Android (minSdk 28), the glasses app could also use the standard Android Camera2 API directly to access the hardware camera. This would bypass the CXR SDK entirely and give the glasses app direct control. The `CAMERA` permission is already declared in the glasses manifest (`glasses-app/src/main/AndroidManifest.xml:13-14`).

### Media Sync API (Bulk Transfer)

The CXR-M SDK also has a file sync system for transferring media stored on the glasses:
```java
boolean startSync(String destPath, CxrMediaType[] types, SyncStatusCallback callback)
```
- `CxrMediaType.PICTURE` for photos
- Requires WiFi P2P (`initWifiP2P2()`) to be active — uses HTTP over the P2P link
- `MediaFilesUpdateListener.onMediaFilesUpdated()` fires when new files appear on glasses
- Designed for bulk gallery sync, not real-time single-photo capture

---

## 2. Host-Side Detection

This section is less relevant for our architecture since photos don't transit through a macOS/Linux host — they flow directly **glasses -> phone app -> OpenClaw Gateway** over Bluetooth. However, for completeness:

### In Our Architecture (Phone as Host)

The phone app receives the photo in one of two ways:

**Path A — `takeGlassPhoto` callback (recommended):**
The phone calls `CxrApi.takeGlassPhoto(width, height, quality, PhotoResultCallback)`. The callback fires with raw JPEG `byte[]` data directly in memory. No filesystem watch needed.

**Path B — Media sync notification:**
`CxrApi.setMediaFilesUpdateListener()` notifies the phone when new media files exist on the glasses. The phone then calls `startSync()` to download them over WiFi P2P. Files land at the `destPath` specified in the sync call.

### If a Desktop Host Were Involved

If photos needed to reach a macOS/Linux host (not our case):
- The glasses connect to the phone via Bluetooth, not directly to a desktop
- The phone app would need to relay the photo to the desktop (e.g., via the OpenClaw WebSocket)
- MTP/USB transfer is not relevant since the glasses pair with the phone, not a computer

---

## 3. Integration Path

### Current State of the Codebase

The codebase already has **significant plumbing** for photo attachment. Here's what exists:

| Layer | File | What's There | What's Missing |
|-------|------|-------------|----------------|
| Protocol | `shared/.../Protocol.kt:214-217` | `UserInput.imageBase64: String?` field | Nothing — ready |
| Glasses UI state | `glasses-app/.../HudScreen.kt:140` | `ChatHudState.hasPhoto: Boolean` | Needs `photoBase64: String?` to hold actual data |
| Glasses capture trigger | `glasses-app/.../HudActivity.kt:516-519` | `MoreMenuItem.PHOTO` sets `hasPhoto = true` | Actual camera capture logic |
| Glasses submit | `glasses-app/.../HudActivity.kt:412-426` | Sends `user_input` JSON with placeholder for photo | Actually attaching `imageBase64` to the JSON |
| Glasses transport | `glasses-app/.../PhoneConnectionService.kt:217-222` | `sendImage(base64Image)` method exists | Not called from HudActivity |
| Phone receive | `phone-app/.../MainScreen.kt:178-184` | Parses `imageBase64` from `user_input` messages | Nothing — ready |
| Phone send to OpenClaw | `phone-app/.../OpenClawClient.kt:159,178-180` | `sendMessage(text, imageBase64)` attaches `image` to `chat.send` | Nothing — ready |
| Voice commands | `phone-app/.../VoiceCommandHandler.kt:78` | `"take photo"` registered as special command | Handler logic |

### Recommended Approach: Phone-Initiated Capture via `takeGlassPhoto`

This is the simplest path because:
- The CXR-M SDK on the phone already has `takeGlassPhoto()` ready to use
- Returns raw bytes directly — no file I/O, no WiFi P2P needed
- The phone already has `RokidSdkManager` managing the `CxrApi` instance (`phone-app/.../RokidSdkManager.kt:40`)

**Flow:**

```
1. User selects PHOTO in glasses More menu
   └─ glasses-app/.../HudActivity.kt:516 (MoreMenuItem.PHOTO)

2. Glasses sends "take_photo" request to phone
   └─ New message type in shared/.../Protocol.kt
   └─ Sent via PhoneConnectionService.sendToPhone()

3. Phone receives "take_photo", calls CxrApi.takeGlassPhoto()
   └─ phone-app/.../RokidSdkManager.kt — add takePhoto() method
   └─ phone-app/.../MainScreen.kt — handle "take_photo" message type

4. PhotoResultCallback fires with byte[] JPEG data
   └─ Phone Base64-encodes the bytes
   └─ Phone sends "photo_result" message back to glasses with base64 data

5. Glasses receives "photo_result", stores in HudState
   └─ glasses-app/.../HudActivity.kt — update hasPhoto + photoBase64
   └─ glasses-app/.../HudScreen.kt — show photo indicator in UI

6. User submits message (voice or text)
   └─ glasses-app/.../HudActivity.kt:412-426 (submitInput)
   └─ Attaches photoBase64 to the UserInput JSON
   └─ Sent to phone via existing protocol

7. Phone forwards to OpenClaw with image
   └─ phone-app/.../MainScreen.kt:178-184 — already parses imageBase64
   └─ phone-app/.../OpenClawClient.kt:159 — already sends image param
```

### Alternative Approach: Glasses-Side Camera2 Capture

The glasses app captures the photo directly using Android Camera2 API, encodes it to Base64, and sends it with the next `user_input` message.

**Pros:** Lower latency (no round-trip to phone for capture), works in debug/WebSocket mode too.
**Cons:** Must manage Camera2 lifecycle on glasses, may conflict with system camera service, larger code surface on the resource-constrained glasses device.

**Flow:**
```
1. User selects PHOTO in More menu → HudActivity opens Camera2, captures frame
2. JPEG bytes → Base64 → stored in ChatHudState.photoBase64
3. On submitInput(), attach photoBase64 to UserInput JSON
4. Phone receives and forwards as-is (existing code handles this)
```

### Debug Mode Consideration

In debug mode (WebSocket, no real glasses), `takeGlassPhoto` won't work because there's no CXR SDK connection. The glasses-side Camera2 approach would work with an emulator camera. For testing, a stub that returns a placeholder image could be used.

---

## 4. Open Questions and Risks

### Must Resolve Before Implementation

1. **Photo resolution and size limits.** A 12MP JPEG at quality 80 is ~3-4 MB, which Base64-encodes to ~4-5 MB. What are the size limits on:
   - CXR Bluetooth transfer (bandwidth ~2 Mbps for BLE, likely higher for classic BT)?
   - OpenClaw Gateway `chat.send` image parameter?
   - The AI backend (Claude, GPT) image input size limits?
   - Recommended: capture at reduced resolution (e.g., 1280x960) with quality 70-80.

2. **`takeGlassPhoto` availability.** The method exists in the decompiled SDK AAR, but we haven't tested it at runtime. Does it require any additional setup beyond Bluetooth connection (e.g., camera permissions on the glasses system service, specific SDK initialization sequence)?

3. **Conflict with glasses system camera.** The `CxrApi.isGlassCameraInUse()` method suggests the camera may be claimed by other apps/scenes. Need to handle the case where capture fails because the camera is busy.

4. **OpenClaw `chat.send` image format.** The current code sends `image` as a top-level property in the `chat.send` params (`OpenClawClient.kt:178-180`). Need to verify this is the correct format the Gateway expects — is it raw Base64, a data URI (`data:image/jpeg;base64,...`), or something else? Check the OpenClaw reference at `.openclaw-ref/`.

5. **Photo preview on glasses HUD.** The monochrome 480x640 green display can't meaningfully preview a photo. Should the UI just show a "Photo attached" indicator, or attempt a dithered thumbnail? The current `hasPhoto` boolean suggests indicator-only is the plan.

### Nice to Have / Future

6. **Physical button mapping.** Could one of the hardware buttons (e.g., double-tap Home) be mapped to photo capture for faster access than navigating the More menu?

7. **Multiple photos.** Current `hasPhoto: Boolean` only supports one photo. Should `imageBase64` become a list for multi-photo attachment?

8. **Photo taken from glasses built-in camera app.** If the user takes a photo outside our app (via the Rokid system camera), can we detect it via `MediaFilesUpdateListener` and offer to attach it?

9. **Debug mode testing.** Need a strategy for testing photo attachment in the emulator without physical glasses. Options: stub `takeGlassPhoto`, use Camera2 on emulator, or inject test images.

---

## Sources

- Rokid CXR-M SDK decompiled from `~/.gradle/caches/modules-2/files-2.1/com.rokid.cxr/client-m/1.0.8/` — `CxrApi`, `PhotoResultCallback`, `PhotoPathCallback`, `MediaStreamListener`, `SyncStatusCallback`, `MediaFilesUpdateListener`
- Rokid CXR-S SDK decompiled from `~/.gradle/caches/modules-2/files-2.1/com.rokid.cxr/cxr-service-bridge/1.0/` — `CXRServiceBridge`
- Project documentation: `docs/ROKID.md`
- Codebase files referenced inline throughout this document
- [Rokid Glass 2 Docs](https://rokidglass.github.io/glass2-docs/en/)
- [Rokid Developer Portal](https://ar.rokid.com)
- [Rokid Maven Repository](https://maven.rokid.com/repository/maven-public/)
