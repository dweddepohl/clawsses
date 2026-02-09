# Settings Screen UX/UI Redesign Specification

**Task:** SUXR-823
**Date:** 2026-02-09
**Scope:** Phone app settings — `SettingsDialog` composable in `MainScreen.kt:870-1303`

---

## Part 1: Audit of Current Settings UI

### 1.1 Inventory of All Settings

| # | Setting | Type | Location | Default | Persisted |
|---|---------|------|----------|---------|-----------|
| 1 | Debug Mode | Toggle (Switch) | Glasses tab | false | Runtime only |
| 2 | Saved Glasses SN | Display + Clear | Glasses tab | N/A | SharedPrefs (`clawsses_glasses_sn`) |
| 3 | Glasses Connection | Status + Scan/Connect/Disconnect | Glasses tab | Disconnected | Runtime |
| 4 | WiFi P2P | Status + Init button | Glasses tab | Disconnected | Runtime |
| 5 | APK Installation | Button + Progress | Glasses tab | Idle | Runtime |
| 6 | OpenClaw Host | Text input | OpenClaw tab | "10.0.2.2" | SharedPrefs (`openclaw_host`) |
| 7 | OpenClaw Port | Text input | OpenClaw tab | "18789" | SharedPrefs (`openclaw_port`) |
| 8 | Gateway Token | Text input (masked) | OpenClaw tab | "" | SharedPrefs (`openclaw_token`) |
| 9 | Voice Language | Single-select picker | Customize tab | Device default | SharedPrefs (`voice_language`) |

### 1.2 Current Structure

```
AlertDialog
├── Title: "Settings"
├── TabRow: [Glasses] [OpenClaw] [Customize]
├── Tab 0 — Glasses (LazyColumn, single item):
│   ├── Debug Mode toggle (emulator only)
│   ├── Debug instructions (conditional)
│   ├── Saved SN display + Clear button (conditional)
│   ├── Connection status icon + text
│   ├── WiFi P2P status (conditional)
│   ├── FlowRow: [Scan/Stop] [WiFi P2P] [Disconnect]
│   ├── Discovered devices list (conditional)
│   └── APK Installation section (conditional)
│       ├── Title + subtitle
│       ├── Install button
│       └── InstallationSection (state-based progress)
├── Tab 1 — OpenClaw (LazyColumn, single item):
│   ├── Row: Host field (2/3) + Port field (1/3)
│   └── Token field (full width, masked)
├── Tab 2 — Customize (LazyColumn, single item):
│   ├── "Voice Language" title
│   ├── Language selector button
│   └── Expanded language list (conditional)
└── confirmButton: "Close"
```

### 1.3 Problems Identified

#### Container & Navigation

**P1. AlertDialog is the wrong container.**
Settings are a workspace, not an interruption. `AlertDialog` communicates urgency and temporariness. It constrains height, fights with keyboard focus, and has no natural scrollbar affordance. The Glasses tab frequently overflows the visible area, especially during device scanning or APK installation. Users must scroll without any visual hint that more content exists below.

**P2. Tabs create uneven content distribution.**
The Glasses tab contains ~230 lines of composable code with conditional sections that can expand further. The OpenClaw tab has 3 text fields. The Customize tab has 1 dropdown. This creates a lopsided experience — one tab is overwhelming, two are underwhelming. Tabs imply equal-weight content; this violates that contract.

**P3. Tab names are opaque.**
"OpenClaw" is a backend project name that means nothing to most users. "Customize" is generic to the point of meaninglessness — it contains only a voice language setting. "Glasses" conflates three distinct activities: device pairing, connection management, and software deployment.

#### Information Architecture

**P4. Glasses tab mixes actions with settings with deployment.**
Device discovery (scanning, connecting), connection status monitoring, SN management, and APK installation are fundamentally different tasks with different frequencies of use. They're stacked linearly with minimal visual separation. A user who wants to install an APK update must scroll past connection controls they don't need.

**P5. No connection status visibility on the OpenClaw tab.**
Users can change host/port/token but cannot see whether the server is currently connected, connecting, or errored — that feedback is only on the main screen's status bar. Editing server settings in a blind void.

**P6. Developer settings mixed with user settings.**
Debug mode is development tooling. It sits at the top of the Glasses tab on emulators, taking prime visual real estate from the settings actual users need.

#### Interaction Design

**P7. Immediate save on keystroke is destructive.**
Every character typed into host/port/token fields immediately persists to SharedPreferences and can trigger connection state changes. Typing "192.168.1.100" means the app attempts connections to "1", "19", "192", "192.", "192.1", etc. This is both wasteful and confusing — the connection status flickers as partial values are processed.

**P8. Layout instability from conditional content.**
The Glasses tab has at least 5 conditional blocks (`isEmulator`, `hasCachedSn`, `isConnected`, `showDeviceList`, `sdkConnected`). As connection state changes, buttons appear/disappear, sections expand/collapse, and the scroll position shifts unpredictably. The user's spatial mental model breaks.

**P9. No confirmation for destructive actions.**
"Clear saved glasses SN" is a one-tap irreversible action. The next Bluetooth connection will require the full two-attempt pairing flow. No confirmation dialog, no undo.

**P10. Device list has no empty/loading states.**
When scanning starts, `showDeviceList` becomes true but `discoveredDevices` may be empty. There's no "Scanning..." skeleton or empty state message. The user sees... nothing, and wonders if the scan is working.

#### Visual Design

**P11. No section headers in the Glasses tab.**
Debug mode, SN management, connection status, action buttons, device list, and APK installation flow together as one continuous stream. There are no visual markers to help users scan the page and find what they need. The only section header is "Glasses App Installation" (`titleSmall`), buried deep in the scroll.

**P12. Inconsistent spacing rhythm.**
Spacer values: 4dp, 8dp, 16dp used inconsistently. SN section → 16dp gap. Connection status → 8dp gap. Buttons to device list → 8dp gap. Installation title to subtitle → 8dp. No perceptible rhythm or hierarchy in the spacing.

**P13. Weak typography hierarchy.**
Only two effective levels: `titleSmall` (rare) and `bodySmall`/`bodyMedium` (everything). Section boundaries, setting names, setting values, and helper text all look similar. The eye has no landmarks.

**P14. Button styling inconsistency.**
The Glasses tab uses `OutlinedButton` (Scan, Disconnect, Clear SN), `Button` (WiFi P2P, Install), and `TextButton` (Connect per-device). There's no clear system for when each variant is used — it feels arbitrary rather than intentional.

**P15. The "Close" button is misplaced.**
As `confirmButton` in AlertDialog, "Close" sits in the bottom-right — a position that in Android dialogs typically means "positive action" (OK, Save, Confirm). Closing settings is a neutral navigation action, not a confirmation.

---

## Part 2: Redesign Specification

### 2.1 Design Principles

These principles guide every decision below:

1. **Inevitable** — The design should feel like the only possible solution. Nothing arbitrary, nothing decorative.
2. **Quiet** — The interface steps back. Only what matters at this moment is prominent.
3. **Hierarchical through restraint** — Fewer visual elements, each carrying more meaning.
4. **Grouped by intent** — Related things together. "What is the user trying to do?" not "What technology does this configure?"
5. **Progressive disclosure** — Complexity only when requested.
6. **Respectful of attention** — Every pixel either informs or gets out of the way.

### 2.2 Container: Full-Screen Settings Surface

**Change:** Replace `AlertDialog` with a full-screen composable navigated via `AnimatedVisibility` (slide-up) or a `ModalBottomSheet` at 92% height.

**Structure:**
```
Scaffold
├── TopAppBar
│   ├── NavigationIcon: ← (back arrow)
│   └── Title: "Settings"
└── Content: LazyColumn (single continuous scroll)
    ├── Section: Server
    ├── Section: Glasses
    ├── Section: Software Update
    ├── Section: Voice
    └── Section: Developer (emulator only)
```

**Rationale:** _A settings surface must honor the weight of its content. AlertDialog communicates "brief interruption" — but configuring server connections and managing device pairing are deliberate acts that deserve space, stability, and focus. A full-screen surface eliminates scroll fighting, keyboard occlusion issues, and gives every setting room to breathe. (Principle: Inevitable)_

### 2.3 Information Architecture

Replace the 3-tab structure with a single scrollable surface organized into sections by user intent.

#### New Section Hierarchy

| Section | Contains | User Intent |
|---------|----------|-------------|
| **Server** | Host, Port, Token, Connection status, Apply/Reconnect | "Connect to my AI backend" |
| **Glasses** | Connection status, Paired device info, Scan, Disconnect | "Pair and manage my glasses" |
| **Software Update** | Install button, Progress, Version info | "Update the glasses software" |
| **Voice** | Language selector | "Change my voice language" |
| **Developer** | Debug mode toggle, ADB instructions | "Test without hardware" |

**Rationale:** _Tabs force users to guess which drawer holds what they need. A single scrollable list with clear section headers lets users scan the entire settings landscape in one glance. The eye finds its target through typography hierarchy, not through trial-and-error tab switching. (Principle: Quiet, Grouped by intent)_

**Rationale for splitting Glasses into Glasses + Software Update:** _Pairing glasses and updating their software are different tasks done at different times with different frequencies. Pairing happens once; updates happen occasionally. Combining them creates a dense wall of controls where half are irrelevant at any given moment. (Principle: Progressive disclosure)_

### 2.4 Visual Design System

#### Section Headers

```
SECTION HEADER
├── All-caps label, letterSpacing = 1.sp
├── Typography: labelMedium
├── Color: onSurfaceVariant (muted, 60% opacity)
├── Padding: top 32dp, bottom 8dp, horizontal 16dp
└── No underline, no border — just the text and space
```

**Rationale:** _iOS and Material3 both use quiet, uppercase, muted section headers. They're scannable without competing with content. The generous top padding (32dp) creates unmistakable section breaks without needing dividers or boxes. (Principle: Hierarchical through restraint)_

#### Setting Rows

Standard setting row pattern — consistent across all settings:

```
┌─────────────────────────────────────────────────┐
│ [Icon 24dp]  Title                    [Control] │
│              Subtitle (muted)                   │
└─────────────────────────────────────────────────┘
```

- **Row height:** min 56dp (Material3 ListItem standard)
- **Horizontal padding:** 16dp
- **Vertical padding:** 12dp
- **Icon:** 24dp, `onSurfaceVariant` tint, start-aligned
- **Title:** `bodyLarge`, `onSurface` color
- **Subtitle:** `bodySmall`, `onSurfaceVariant` color (60% opacity)
- **Control area:** switch, text value, chevron, or status indicator — end-aligned
- **Divider:** 0.5dp between rows within same section, inset 56dp from start (aligned with text, not icon)

**Rationale:** _A consistent row pattern means the eye only needs to learn one layout. Once you understand where title, subtitle, and control live, every setting becomes instantly parsable. The inset divider is subtle — it separates without imprisoning. (Principle: Inevitable, Quiet)_

#### Cards for Grouped Inputs

Server settings (Host + Port + Token) are grouped into a single `Surface` card:

```
Surface(
    shape = RoundedCornerShape(12.dp),
    tonalElevation = 1.dp  // very subtle lift
)
```

- **Card padding:** 16dp internal
- **Card margin:** 16dp horizontal
- **Corner radius:** 12dp
- **Elevation:** 1dp tonal (not shadow — just a slight color shift)

**Rationale:** _Text inputs that belong together should look like they belong together. A card creates a visual unit without heavy borders. The slight tonal elevation says "this is one thing" without shouting. (Principle: Grouped by intent)_

#### Color Palette

| Token | Usage | Value |
|-------|-------|-------|
| `onSurface` | Primary text, setting titles | Theme default |
| `onSurfaceVariant` | Section headers, subtitles, icons | Theme default (≈60% opacity) |
| `primary` | Active/selected states, links | Theme default |
| Status Green | Connected | `#4CAF50` (keep) |
| Status Amber | Connecting/Scanning | `#FFC107` (keep) |
| Status Red | Error | `#F44336` (keep) |
| Destructive | Delete/Clear actions | `#F44336` text on transparent |

**Rationale:** _Three status colors are enough. Every other color serves hierarchy, not decoration. (Principle: Quiet)_

### 2.5 Section-by-Section Design

---

#### Section A: Server

**Purpose:** Configure and monitor the OpenClaw Gateway connection.

**Layout:**

```
SERVER                          ← section header (muted, caps)

┌─────────────────────────────────────────────┐
│  Host                                       │
│  ┌────────────────────────┐ ┌─────────────┐ │
│  │ 192.168.1.50           │ │ 18789       │ │
│  └────────────────────────┘ └─────────────┘ │
│  Server address               Port          │
│                                             │
│  Token                                      │
│  ┌──────────────────────────────────── 👁 ┐ │
│  │ ••••••••••••••••                       │ │
│  └────────────────────────────────────────┘ │
│  Authentication token for Gateway           │
│                                             │
│  ┌─────────────────────────────────────┐    │
│  │          Apply Changes              │    │
│  └─────────────────────────────────────┘    │
│  (button only visible when values differ    │
│   from last-applied values)                 │
└─────────────────────────────────────────────┘

  ● Connected to gateway                      ← inline status
    ws://192.168.1.50:18789
```

**Interaction changes:**

1. **Deferred save.** Text field changes update local state only. A "Apply Changes" button appears when any field differs from the last-applied value. Pressing Apply persists to SharedPreferences, disconnects, and reconnects. This prevents the keystroke-by-keystroke reconnection problem (P7).

2. **Connection status inline.** A small status row below the card shows current connection state with the same green/amber/red dot pattern, plus the resolved WebSocket URL. Users can see the effect of their changes without leaving settings.

3. **Labels renamed.** "Host / IP" → "Host" (cleaner). "Gateway Token" → "Token" (the section context provides "Gateway"). Label text moves above the field (Material3 exposed style) for scannability.

**Rationale:** _Server configuration is a deliberate act: you enter values, then commit them. Auto-saving on every keystroke disrespects the user's workflow — they're not done typing. The explicit "Apply" button gives the user agency and prevents wasted connection attempts. Showing connection status here closes the feedback loop within the same visual context. (Principle: Respectful of attention, Progressive disclosure)_

---

#### Section B: Glasses

**Purpose:** Manage Bluetooth connection to Rokid glasses.

**Layout (disconnected state):**

```
GLASSES                         ← section header

  ◌  Not connected              ← status row
     Tap Scan to find nearby glasses

  ┌─────────────────────────────────────┐
  │     🔍  Scan for Glasses            │  ← primary action
  └─────────────────────────────────────┘
```

**Layout (scanning state):**

```
GLASSES

  ◐  Scanning...                ← animated indicator
     Looking for nearby glasses

  ┌─────────────────────────────────────┐
  │     ■  Stop Scanning                │
  └─────────────────────────────────────┘

  NEARBY DEVICES                ← sub-header appears

  ┌─────────────────────────────────────┐
  │  Rokid Max Pro              Connect │
  │  AB:CD:EF:12:34 · -42 dBm          │
  ├─────────────────────────────────────┤
  │  Rokid Air 2               Connect │
  │  AB:CD:EF:56:78 · -67 dBm          │
  └─────────────────────────────────────┘

  Scanning... 3 devices found
```

**Layout (connected state):**

```
GLASSES

  ●  Connected                  ← green dot
     Rokid Max Pro

     Bluetooth    ● Connected
     WiFi P2P     ◌ Not connected   [Setup]

  ┌─────────────────────────────────────┐
  │  🔗  Paired Device                  │
  │      SN: RXXX-XXXX-XXXX            │
  │                                     │
  │      [Clear pairing]     ← subtle,  │
  │      Use when switching glasses     │
  └─────────────────────────────────────┘

  ┌─────────────────────────────────────┐
  │         Disconnect                  │  ← outlined, not primary
  └─────────────────────────────────────┘
```

**Interaction changes:**

1. **State-driven layout.** The section's content adapts cleanly to three states: disconnected, scanning, connected. Instead of hiding/showing individual elements (causing P8 layout instability), the entire section renders one of three distinct layouts. Each is stable and predictable.

2. **Scan results in a card.** Discovered devices appear in a contained list card, not sprinkled into the open flow. Each device is a row with name, address, signal strength (human-readable "Strong/Medium/Weak" or dBm for technical users), and a "Connect" action.

3. **Clear pairing requires confirmation.** Tapping "Clear pairing" shows a small inline confirmation: "Clear pairing data? You'll need to re-pair next time." with [Cancel] [Clear] buttons. No separate dialog — the confirmation replaces the button inline.

4. **WiFi P2P status** is an inline row in the connected state, not a separate floating element. The "Setup" button is contextual — only appears when WiFi P2P is not connected.

5. **RSSI displayed meaningfully.** Instead of raw "RSSI: -42", show signal bars or "Strong (-42 dBm)" — the number alone means nothing to non-technical users.

**Rationale:** _Connection management is inherently stateful. Rather than having a stable layout with elements flickering in and out, we embrace the state machine: each state gets a designed, stable layout. The user always sees a coherent screen, never a partially-constructed one. The scan result card contains the list visually, preventing it from pushing other content around. (Principle: Inevitable, Quiet)_

---

#### Section C: Software Update

**Purpose:** Install or update the glasses app.

**Layout (idle, SDK connected):**

```
SOFTWARE UPDATE                 ← section header

  Glasses App
  Push the latest glasses app via Bluetooth

  ┌─────────────────────────────────────┐
  │     ☁↑  Install to Glasses          │
  └─────────────────────────────────────┘
```

**Layout (in progress):**

```
SOFTWARE UPDATE

  Installing Glasses App
  Do not disconnect the glasses

  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━  67%
  Uploading APK...

  ┌─────────────────────────────────────┐
  │            Cancel                   │
  └─────────────────────────────────────┘
```

**Layout (success):**

```
SOFTWARE UPDATE

  ✓  Installation Complete
     Glasses app has been updated

  ┌─────────────────────────────────────┐
  │     ☁↑  Install Again               │
  └─────────────────────────────────────┘
```

**Layout (not available — glasses not connected via SDK):**

```
SOFTWARE UPDATE

  Glasses App
  Connect glasses via Bluetooth to install updates

     ↳ Glasses must be connected        ← muted, not an error
```

**Interaction changes:**

1. **Separated from Glasses section.** Users don't conflate "connecting my glasses" with "updating the glasses software." These are separate mental tasks.

2. **Linear progress bar** replaces the percentage-only text. A `LinearProgressIndicator` with the percentage overlaid gives immediate visual feedback.

3. **Warning text** ("Do not disconnect") is prominent during installation — styled as a caution, not buried as `bodySmall`.

4. **Unavailable state** is clear but non-alarming. Instead of hiding the entire section when SDK isn't connected, show it dimmed with a brief explanation. The user knows the feature exists and what's needed to access it.

**Rationale:** _Software deployment is a high-stakes operation (bricking risk, user anxiety about disconnection). It deserves its own space where status is unmistakable and the user's attention is focused. Hiding it when unavailable makes it undiscoverable; showing it dimmed teaches users the capability exists. (Principle: Progressive disclosure, Respectful of attention)_

---

#### Section D: Voice

**Purpose:** Select speech recognition language.

**Layout:**

```
VOICE                           ← section header

  ┌───────────────────────────────────────┐
  │  🌐  Recognition Language        ▸   │
  │      English (United States)          │
  └───────────────────────────────────────┘
```

Tapping opens a **ModalBottomSheet** with the language list:

```
┌─────────────────────────────────────────────┐
│              Recognition Language            │
│  ─────────────────────────────────────────   │
│                                             │
│  ● Nederlands (Nederland)         nl-NL     │
│  ○ English (United States)        en-US     │
│  ──────────────────────────────────────      │
│  ○ Deutsch (Deutschland)          de-DE     │
│  ○ Español (España)               es-ES     │
│  ○ Français (France)              fr-FR     │
│  ○ Italiano (Italia)              it-IT     │
│  ...                                        │
│                                             │
└─────────────────────────────────────────────┘
```

**Interaction changes:**

1. **BottomSheet for language selection.** The current inline expand/collapse competes for scroll space and feels cramped inside the LazyColumn. A BottomSheet is the platform-standard pattern for single-selection lists on Android. It has proper swipe-to-dismiss, a drag handle, and ample space for the list.

2. **Preferred languages separated.** Dutch and English (US) appear above a thin divider, then all other languages alphabetically below. This respects the existing `PREFERRED_LOCALES` logic but makes it visible through visual separation.

3. **Current selection shown in the row.** The selected language name appears as subtitle text in the setting row, so users never need to open the picker just to see what's selected.

**Rationale:** _A language list can contain 50+ items. Inlining that into a scrollable settings surface creates a scroll-within-scroll conflict and pushes other settings offscreen. A BottomSheet gives the list its own dedicated, dismissible surface. (Principle: Inevitable)_

---

#### Section E: Developer

**Purpose:** Emulator/development tools. Only visible when `isEmulator()` returns true.

**Layout:**

```
DEVELOPER                       ← section header

  ┌───────────────────────────────────────┐
  │  🔧  Debug Mode                 [⬜] │  ← Switch
  │      Use WebSocket instead of         │
  │      Bluetooth                        │
  └───────────────────────────────────────┘

  (when enabled:)
  ┌───────────────────────────────────────┐
  │  ℹ  Glasses connects to port 8081    │
  │                                       │
  │  adb forward tcp:8081 tcp:8081        │  ← monospace, tappable to copy
  └───────────────────────────────────────┘
```

**Interaction changes:**

1. **Moved to bottom.** Developer settings are last. Regular users (on physical devices) never see this section. On emulators, it's still accessible but doesn't steal attention from production settings.

2. **ADB command is tappable to copy.** Long-press or tap copies `adb forward tcp:8081 tcp:8081` to clipboard with a snackbar confirmation. Developers use this command constantly — making it copyable saves context-switching to documentation.

**Rationale:** _Developer tools are for developers. Placing them at the bottom respects the principle that settings should be ordered by frequency and importance of use. The copyable command is a small delight that respects the developer's workflow. (Principle: Quiet, Respectful of attention)_

---

### 2.6 Interaction Patterns

#### Navigation

| Action | Behavior |
|--------|----------|
| Open settings | Slide up from bottom (or expand from icon) with `AnimatedVisibility(slideInVertically)` |
| Close settings | Back arrow in TopAppBar, system back gesture, or swipe down |
| Open language picker | BottomSheet slides up over settings |
| Close language picker | Tap selection, swipe down, or tap outside |

**Rationale:** _Consistent motion language. Settings slide up (they're a layer above the main screen), pickers slide up further (they're a layer above settings). Layers communicate depth. (Principle: Inevitable)_

#### Editing & Saving

| Setting | Save Behavior | Feedback |
|---------|---------------|----------|
| Server fields | Deferred — "Apply Changes" button | Button appears on change, disappears after apply. Snackbar: "Reconnecting..." |
| Debug mode | Immediate | Switch animates, connection status updates |
| Voice language | Immediate on selection | BottomSheet dismisses, row subtitle updates |
| Clear SN | After inline confirmation | Row content updates to show "No paired device" |

**Rationale:** _Not all settings deserve the same save pattern. Stateless preferences (language, toggle) save immediately — the user expects instant feedback. Connection settings are different: they form a group that should be committed atomically, and premature saving causes visible errors. (Principle: Respectful of attention)_

#### Error States

| Error | Current | Proposed |
|-------|---------|----------|
| Connection failed | Red icon + error text in Glasses tab | Red status in Glasses section + subtitle with error message + "Retry" action |
| Installation error | Red icon + error text + conditional "Try Again" | Error card with message + prominent "Retry" button + subtle "Dismiss" |
| No speech recognizer | "No languages available" text | "Speech recognition unavailable on this device" + link to system settings |

#### Confirmation for Destructive Actions

"Clear pairing" interaction:

```
Before tap:
  [Clear pairing]     ← subtle text button, red-tinted

After tap (inline confirmation replaces the button):
  Clear pairing data?
  You'll need to re-pair next time.
  [Cancel]  [Clear]   ← Clear is red/destructive
```

**Rationale:** _A separate dialog for a minor destructive action is heavyweight. An inline confirmation is fast, contextual, and doesn't break spatial awareness. But the confirmation must exist — one-tap destruction of pairing data violated the principle of respect. (Principle: Respectful of attention)_

### 2.7 Micro-Interactions

| Interaction | Animation |
|-------------|-----------|
| Section appears (e.g., developer section on emulator) | `AnimatedVisibility(fadeIn + expandVertically)` |
| Apply Changes button appears | `AnimatedVisibility(fadeIn)`, 200ms |
| Scan → device found | Device row slides in from bottom, 150ms stagger |
| Installation progress | `LinearProgressIndicator` with animated determinate progress |
| Connection status change | Dot color crossfades, 300ms |
| Language selected | Row subtitle crossfades to new language name |
| Copy ADB command | Snackbar: "Copied to clipboard" |

### 2.8 Accessibility

- All interactive elements have `contentDescription`
- Minimum touch targets: 48dp x 48dp
- Setting rows: semantics `Role.Button` for clickable rows
- Status indicators: screen reader announces state ("Connected to Rokid Max Pro", not just color)
- Language list: grouped semantics with "Preferred languages" and "All languages" headings

---

## Part 3: Before/After ASCII Mockups

### Mockup 1: Overall Structure (Current vs. Proposed)

**BEFORE — AlertDialog with tabs:**

```
┌──────────────────────────────────────┐
│  Settings                            │
│  ┌──────────┬──────────┬──────────┐  │
│  │ Glasses  │ OpenClaw │Customize │  │
│  └──────────┴──────────┴──────────┘  │
│                                      │
│  (Tab 0: everything jammed in)       │
│                                      │
│  Debug Mode              [====]      │
│  Saved SN: encrypted                 │
│  [Clear saved glasses SN       ]     │
│  Use if switching...                 │
│                                      │
│  ● Connected: Rokid Max Pro          │
│  📶 WiFi P2P: Connected             │
│                                      │
│  [Scan] [WiFi P2P] [Disconnect]     │
│                                      │
│  Glasses App Installation            │
│  Install via Bluetooth + WiFi P2P    │
│  [Install app to glasses       ]     │
│                                      │
│  (scroll for more...)               │
│                                      │
│                          [Close]     │
└──────────────────────────────────────┘
```

**AFTER — Full-screen scrollable sections:**

```
┌──────────────────────────────────────┐
│  ←  Settings                         │
├──────────────────────────────────────┤
│                                      │
│  SERVER                              │
│                                      │
│  ┌────────────────────────────────┐  │
│  │  Host              Port        │  │
│  │  ┌──────────────┐ ┌────────┐  │  │
│  │  │ 192.168.1.50 │ │ 18789  │  │  │
│  │  └──────────────┘ └────────┘  │  │
│  │                                │  │
│  │  Token                         │  │
│  │  ┌────────────────────── 👁 ┐  │  │
│  │  │ ••••••••••••••           │  │  │
│  │  └──────────────────────────┘  │  │
│  └────────────────────────────────┘  │
│                                      │
│  ●  Connected · ws://192.168.1.50    │
│                                      │
│                                      │
│  GLASSES                             │
│                                      │
│  ●  Connected                        │
│     Rokid Max Pro                    │
│                                      │
│     Bluetooth  ●  WiFi P2P  ●       │
│                                      │
│  ┌────────────────────────────────┐  │
│  │  Paired Device                 │  │
│  │  SN: RXXX-XXXX-XXXX           │  │
│  │            [Clear pairing]     │  │
│  └────────────────────────────────┘  │
│                                      │
│  [       Disconnect              ]   │
│                                      │
│                                      │
│  SOFTWARE UPDATE                     │
│                                      │
│  ┌────────────────────────────────┐  │
│  │  ☁↑  Install to Glasses       │  │
│  └────────────────────────────────┘  │
│                                      │
│                                      │
│  VOICE                               │
│                                      │
│  ┌────────────────────────────────┐  │
│  │ 🌐 Recognition Language    ▸  │  │
│  │    English (United States)     │  │
│  └────────────────────────────────┘  │
│                                      │
│                                      │
│  DEVELOPER                           │
│                                      │
│  ┌────────────────────────────────┐  │
│  │ 🔧 Debug Mode          [══]  │  │
│  │    WebSocket instead of BT     │  │
│  └────────────────────────────────┘  │
│                                      │
└──────────────────────────────────────┘
```

### Mockup 2: Glasses Section State Machine

**BEFORE — single layout with conditional visibility:**

```
┌─ Glasses tab (all states mixed) ─────┐
│                                      │
│  Debug Mode              [====]      │  ← only on emulator
│  Glasses app connects...             │  ← only when debug on
│                                      │
│  Saved SN: RXXX-XXX                 │  ← only when SN cached
│  [Clear saved glasses SN       ]     │  ← only when SN cached
│  Use if switching...                 │  ← only when SN cached
│                                      │
│  ● Connected: Rokid Max Pro          │  ← always
│  📶 WiFi P2P: Connected             │  ← only when BT connected
│                                      │
│  [Scan] [WiFi P2P] [Disconnect]     │  ← buttons appear/vanish
│                                      │
│  Found 2 device(s):                  │  ← only when scanning
│  Rokid Max Pro    [Connect]          │
│  AB:CD:EF (RSSI: -42)               │
│                                      │
│  Glasses App Installation            │  ← only when SDK connected
│  Install via Bluetooth + WiFi P2P    │
│  [Install app to glasses       ]     │
│                                      │
└──────────────────────────────────────┘
```

**AFTER — three distinct state layouts:**

```
State: DISCONNECTED                    State: SCANNING
┌──────────────────────┐               ┌──────────────────────┐
│  GLASSES             │               │  GLASSES             │
│                      │               │                      │
│  ◌  Not connected    │               │  ◐  Scanning...      │
│     Tap Scan to find │               │     Looking for      │
│     nearby glasses   │               │     nearby glasses   │
│                      │               │                      │
│  ┌────────────────┐  │               │  ┌────────────────┐  │
│  │ 🔍 Scan for    │  │               │  │ ■ Stop Scan    │  │
│  │   Glasses      │  │               │  └────────────────┘  │
│  └────────────────┘  │               │                      │
│                      │               │  NEARBY DEVICES      │
└──────────────────────┘               │  ┌────────────────┐  │
                                       │  │ Rokid Max  [→] │  │
State: CONNECTED                       │  │ Strong -42dBm  │  │
┌──────────────────────┐               │  ├────────────────┤  │
│  GLASSES             │               │  │ Rokid Air  [→] │  │
│                      │               │  │ Medium -67dBm  │  │
│  ●  Connected        │               │  └────────────────┘  │
│     Rokid Max Pro    │               │                      │
│                      │               │  2 devices found     │
│  BT ●    WiFi ◌     │               └──────────────────────┘
│             [Setup]  │
│                      │
│  ┌────────────────┐  │
│  │ Paired Device  │  │
│  │ SN: RXXX-XXXX  │  │
│  │ [Clear pairing]│  │
│  └────────────────┘  │
│                      │
│  [  Disconnect    ]  │
│                      │
└──────────────────────┘
```

### Mockup 3: Server Section — Deferred Save Pattern

**BEFORE — immediate save on keystroke:**

```
┌─ OpenClaw tab ───────────────────────┐
│                                      │
│  ┌──────────────────┐ ┌──────────┐   │
│  │ Host / IP        │ │ Port     │   │
│  │ 10.0.2.2         │ │ 18789    │   │
│  └──────────────────┘ └──────────┘   │
│                                      │
│  ┌──────────────────────────── 👁 ┐  │
│  │ Gateway Token                  │  │
│  │ ••••••••••                     │  │
│  └────────────────────────────────┘  │
│                                      │
│  (no status, no save button,         │
│   values save on every keystroke)    │
│                                      │
└──────────────────────────────────────┘
```

**AFTER — deferred save with inline status:**

```
┌──────────────────────────────────────┐
│                                      │
│  SERVER                              │
│                                      │
│  ┌────────────────────────────────┐  │
│  │  Host              Port        │  │
│  │  ┌──────────────┐ ┌────────┐  │  │
│  │  │ 192.168.1.50 │ │ 18789  │  │  │
│  │  └──────────────┘ └────────┘  │  │
│  │                                │  │
│  │  Token                         │  │
│  │  ┌────────────────────── 👁 ┐  │  │
│  │  │ ••••••••••••••           │  │  │
│  │  └──────────────────────────┘  │  │
│  │                                │  │
│  │  ┌──────────────────────────┐  │  │
│  │  │     Apply Changes        │  │  │  ← only when dirty
│  │  └──────────────────────────┘  │  │
│  └────────────────────────────────┘  │
│                                      │
│  ●  Connected                        │  ← live status
│     ws://192.168.1.50:18789          │
│                                      │
└──────────────────────────────────────┘

After applying:

│  ◐  Reconnecting...                  │  ← status updates live
│     ws://192.168.1.50:18789          │
```

### Mockup 4: Voice Language — BottomSheet Picker

**BEFORE — inline expand in Customize tab:**

```
┌─ Customize tab ──────────────────────┐
│                                      │
│  Voice Language                      │
│                                      │
│  ┌────────────────────────── ▼ ──┐   │
│  │ 🌐 English (United States)    │   │
│  └───────────────────────────────┘   │
│                                      │
│  (when expanded, pushes tab content  │
│   and creates scroll-within-scroll): │
│                                      │
│  ◉ English (United States)  en-US    │
│  ○ Nederlands (Nederland)   nl-NL    │
│  ○ Deutsch (Deutschland)    de-DE    │
│  ○ Español (España)         es-ES    │
│  ○ Français (France)        fr-FR    │
│  ... (50+ more, all inline)          │
│                                      │
└──────────────────────────────────────┘
```

**AFTER — setting row + BottomSheet:**

```
Settings surface:                      BottomSheet (on tap):
┌──────────────────────┐               ┌──────────────────────┐
│                      │               │  ━━━  (drag handle)  │
│  VOICE               │               │                      │
│                      │               │  Recognition         │
│  ┌────────────────┐  │               │  Language             │
│  │🌐 Recognition  │  │               │                      │
│  │  Language    ▸  │  │    ──►        │  PREFERRED            │
│  │  English (US)   │  │               │  ● Nederlands (NL)   │
│  └────────────────┘  │               │  ○ English (US)      │
│                      │               │  ──────────────────   │
│                      │               │  ALL LANGUAGES        │
│                      │               │  ○ Deutsch (DE)      │
└──────────────────────┘               │  ○ Español (ES)      │
                                       │  ○ Français (FR)     │
                                       │  ...                  │
                                       └──────────────────────┘
```

### Mockup 5: Software Update — Progress States

**BEFORE — buried in Glasses tab:**

```
┌──────────────────────────────────────┐
│  ...connection controls above...     │
│                                      │
│  Glasses App Installation            │
│  Install via Bluetooth + WiFi P2P    │
│  [Install app to glasses       ]     │
│                                      │
│  ⟳ Uploading APK...                 │
│  67%                                 │
│  [Cancel                       ]     │
│                                      │
└──────────────────────────────────────┘
```

**AFTER — dedicated section with progress bar:**

```
┌──────────────────────────────────────┐
│                                      │
│  SOFTWARE UPDATE                     │
│                                      │
│  Installing Glasses App              │
│                                      │
│  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━  67%  │
│  Uploading APK...                    │
│                                      │
│  ⚠ Do not disconnect the glasses    │
│                                      │
│  ┌────────────────────────────────┐  │
│  │           Cancel               │  │
│  └────────────────────────────────┘  │
│                                      │
└──────────────────────────────────────┘
```

### Mockup 6: Search State (Not Recommended)

With only 9 settings across 5 sections, a search/filter feature would add complexity without value. Per Hick's Law, the time to make a decision increases logarithmically with the number of choices — but 5 sections with clear labels are already within the threshold of immediate scannability (Miller's Law: 7±2 chunks). Search becomes valuable at ~20+ settings.

However, if settings grow in the future, here is the proposed search pattern:

**Search state (future-proofing, not for initial implementation):**

```
┌──────────────────────────────────────┐
│  ←  Settings                         │
├──────────────────────────────────────┤
│  ┌────────────────────────────────┐  │
│  │  🔍  Search settings...       │  │
│  └────────────────────────────────┘  │
│                                      │
│  (all sections visible below)        │
│  SERVER                              │
│  ...                                 │
└──────────────────────────────────────┘

After typing "tok":
┌──────────────────────────────────────┐
│  ←  Settings                         │
├──────────────────────────────────────┤
│  ┌────────────────────────────────┐  │
│  │  🔍  tok                    ✕ │  │
│  └────────────────────────────────┘  │
│                                      │
│  1 result                            │
│                                      │
│  SERVER                              │
│  ┌────────────────────────────────┐  │
│  │  Token                         │  │
│  │  ┌────────────────────── 👁 ┐  │  │
│  │  │ ••••••••••••••           │  │  │
│  │  └──────────────────────────┘  │  │
│  └────────────────────────────────┘  │
│                                      │
│  No search state (empty query):      │
│  "Type to filter settings"          │
│                                      │
│  No results state:                   │
│  "No settings match 'xyz'"          │
└──────────────────────────────────────┘
```

**Rationale:** _Search adds a layer of indirection. For a small settings surface, direct scanning is faster than typing a query. Premature search is anti-pattern — it signals that the information architecture has failed to make content findable through structure alone. Only add search when settings exceed the scannability threshold. (Principle: Hick's Law, progressive disclosure)_

---

## Part 4: Priority Matrix

Each recommendation tagged by implementation priority:

### P0 — Critical Clarity Fixes

These address problems that actively confuse or harm users.

| # | Recommendation | Addresses | UX Principle |
|---|----------------|-----------|--------------|
| P0-1 | Replace AlertDialog with full-screen Scaffold | P1 (wrong container) | Fitts's Law — larger target area, no constrained scrolling |
| P0-2 | Deferred save for server fields (Apply button) | P7 (keystroke-by-keystroke reconnection) | Error prevention (Nielsen heuristic #5) |
| P0-3 | Add confirmation for Clear SN | P9 (destructive without confirmation) | Error prevention, undo affordance |
| P0-4 | Show connection status inline in Server section | P5 (no feedback on OpenClaw tab) | Visibility of system status (Nielsen heuristic #1) |
| P0-5 | Add scanning/empty state to device list | P10 (no feedback during scan) | Visibility of system status |

### P1 — Meaningful Improvements

These significantly improve usability and comprehension.

| # | Recommendation | Addresses | UX Principle |
|---|----------------|-----------|--------------|
| P1-1 | Replace tabs with single scrollable sections | P2, P3 (uneven tabs, opaque names) | Hick's Law — eliminate tab selection decision |
| P1-2 | Rename sections by user intent (Server, Glasses, etc.) | P3 (opaque tab names) | Recognition over recall (Nielsen heuristic #6) |
| P1-3 | Separate Software Update from Glasses | P4 (mixed concerns) | Proximity principle (Gestalt) — group related, separate unrelated |
| P1-4 | State-driven Glasses layouts (3 distinct states) | P8 (layout instability) | Consistency & standards (Nielsen heuristic #4) |
| P1-5 | Add section headers with consistent styling | P11, P13 (no visual landmarks) | Visual hierarchy, chunking (Miller's Law) |
| P1-6 | Consistent spacing system (32dp/16dp/12dp/8dp) | P12 (inconsistent spacing) | Proximity principle (Gestalt) |
| P1-7 | Standardize button styling system | P14 (inconsistent button variants) | Consistency & standards |
| P1-8 | Move Developer section to bottom | P6 (dev settings in prime position) | Progressive disclosure, frequency-based ordering |
| P1-9 | BottomSheet for language picker | Scroll-within-scroll, cramped picker | Fitts's Law — larger touch targets in dedicated surface |

### P2 — Polish & Delight

These refine the experience but aren't blocking usability.

| # | Recommendation | Addresses | UX Principle |
|---|----------------|-----------|--------------|
| P2-1 | Human-readable signal strength ("Strong -42dBm") | Raw RSSI unintelligible to most users | Recognition over recall |
| P2-2 | Copyable ADB command in Developer section | Developer workflow friction | Efficiency of use (Nielsen heuristic #7) |
| P2-3 | Micro-interaction animations (crossfade, slide-in) | Static state transitions feel abrupt | Aesthetic-usability effect |
| P2-4 | Card elevation for grouped inputs | Visual grouping could be clearer | Proximity principle (Gestalt) |
| P2-5 | Back arrow navigation instead of Close button | P15 (misplaced Close button) | Consistency with platform conventions |
| P2-6 | Show Software Update dimmed when unavailable | Feature undiscoverable when hidden | Progressive disclosure |
| P2-7 | Preferred languages separator in picker | Flat list doesn't highlight preferred options | Chunking (Miller's Law) |
| P2-8 | LinearProgressIndicator for installation | Text-only percentage is harder to parse | Pre-attentive processing — visual bars perceived faster than numbers |
| P2-9 | Accessibility: contentDescription, 48dp targets, semantics | Not currently addressed | WCAG 2.1 compliance |
| P2-10 | Snackbar feedback on Apply/Copy actions | No explicit confirmation of save | Visibility of system status |

### Implementation Phasing

| Phase | Priorities | Description |
|-------|-----------|-------------|
| **Phase 1** | P0-1 through P0-5 | Fix the critical confusion and destructive interaction issues |
| **Phase 2** | P1-1 through P1-9 | Restructure IA, visual hierarchy, and consistency |
| **Phase 3** | P2-1 through P2-10 | Polish animations, micro-interactions, accessibility hardening |

---

## Part 5: Implementation Guidance (Informational)

### 4.1 Component Architecture

Extract `SettingsDialog` from `MainScreen.kt` into dedicated files:

```
phone-app/src/main/java/com/clawsses/phone/ui/settings/
├── SettingsScreen.kt            # Full-screen scaffold + LazyColumn
├── ServerSection.kt             # Server card, apply button, status
├── GlassesSection.kt            # State-driven glasses management
├── SoftwareUpdateSection.kt     # APK installation (extracted)
├── VoiceSection.kt              # Language row + BottomSheet trigger
└── DeveloperSection.kt          # Debug toggle (emulator only)
```

**Rationale:** The current 430-line `SettingsDialog` composable violates single-responsibility. Each section is independent and testable in isolation. This also makes the state parameters for each section explicit rather than passing everything to one god-composable.

### 4.2 State Management for Deferred Save

```kotlin
// In ServerSection.kt
data class ServerFormState(
    val host: String,
    val port: String,
    val token: String
)

// "dirty" = form state differs from applied state
val isDirty = formState != appliedState

// Apply commits form → applied, persists, reconnects
fun apply() {
    appliedState = formState
    prefs.edit()
        .putString("openclaw_host", formState.host)
        .putString("openclaw_port", formState.port)
        .putString("openclaw_token", formState.token)
        .apply()
    openClawClient.disconnect() // triggers reconnect
}
```

### 4.3 Glasses State Machine Rendering

```kotlin
// In GlassesSection.kt
@Composable
fun GlassesSection(state: ConnectionState, ...) {
    when (state) {
        is Disconnected -> DisconnectedContent(onScan = ...)
        is Scanning -> ScanningContent(devices = ..., onStop = ..., onConnect = ...)
        is Connecting -> ConnectingContent(deviceName = ...)
        is Connected -> ConnectedContent(device = ..., wifiP2P = ..., onDisconnect = ...)
        is Error -> ErrorContent(message = ..., onRetry = ...)
    }
}
```

This maps the existing sealed class states directly to composable functions, eliminating conditional visibility flags.

### 4.4 Migration Path

This redesign preserves all existing functionality and state management. The changes are purely in the view layer:

1. Extract `SettingsDialog` into `SettingsScreen` (new file)
2. Replace `AlertDialog` with `Scaffold`
3. Replace `TabRow` with section headers in `LazyColumn`
4. Extract each tab's content into section composables
5. Add deferred save logic to server section
6. Add inline confirmation to Clear SN
7. Replace inline language list with `ModalBottomSheet`
8. Add `LinearProgressIndicator` to installation progress
9. Reorder: Developer section moves to bottom

No changes to:
- SharedPreferences keys or persistence logic
- Manager classes (`GlassesConnectionManager`, `OpenClawClient`, `VoiceLanguageManager`, `ApkInstaller`)
- Protocol or data models
- Connection logic or state machines

---

## Part 6: Rationale Index

Every design decision with the UX principle that justifies it:

| Decision | UX Principle | Why |
|----------|-------------|-----|
| Full-screen surface instead of AlertDialog | **Fitts's Law** — larger interactive area reduces targeting effort | Settings are a workspace, not an interruption. The container must match the content's nature. Larger surface = easier touch targets, no scroll fighting. |
| Single scroll instead of tabs | **Hick's Law** — eliminate the tab-selection decision | Users scan one surface instead of guessing which tab holds their target. Removing the choice of which tab to open reduces decision time to zero. |
| Sections grouped by user intent | **Proximity (Gestalt)** — related items grouped spatially | "Connect to server", "Manage glasses", "Update software" are the user's mental tasks. Technology categories ("OpenClaw", "Glasses") are implementation details. |
| Deferred save for server fields | **Error prevention (Nielsen #5)** — prevent errors before they occur | Keystroke-by-keystroke saving wastes connection attempts and confuses the user with flickering status. Atomic commit prevents partial-value errors. |
| State-driven glasses layouts | **Consistency & standards (Nielsen #4)** — predictable, stable layouts | Three distinct states deserve three distinct designs. Conditional visibility creates unstable layouts; state-driven rendering creates stable, predictable ones. |
| Software Update as separate section | **Progressive disclosure** — show complexity only when relevant | Software deployment is a distinct, infrequent task. Burying it in connection management conflates two mental models. Separation reduces cognitive load. |
| BottomSheet for language picker | **Fitts's Law** — larger touch targets in a dedicated surface | A 50+ item list needs its own surface. Inlining creates scroll conflicts. BottomSheet is the platform-standard pattern for selection lists. |
| Developer section at bottom | **Progressive disclosure** — frequency-based ordering | Developer tools serve developers, not users. On physical devices this section doesn't exist. On emulators it's accessible but doesn't compete with production settings. |
| Inline confirmation for Clear SN | **Error prevention (Nielsen #5)** — proportional confirmation | A full dialog is too heavy for a minor action; no confirmation is too reckless for a destructive one. Inline confirmation is proportional to the action's impact. |
| Connection status in Server section | **Visibility of system status (Nielsen #1)** — feedback near the action | Users editing server settings need to see connection feedback. Placing it elsewhere creates a feedback gap that violates the visibility heuristic. |
| Section headers: muted, caps, generous spacing | **Chunking (Miller's Law)** — 7±2 meaningful groups | Headers are landmarks that chunk the settings into scannable groups. Muted styling ensures they guide without competing with content. |
| Consistent setting row pattern | **Consistency & standards (Nielsen #4)** — learn once, apply everywhere | One layout pattern for all settings means the eye only needs to learn one structure. Consistency eliminates per-row cognitive overhead. |
| Signal strength labels on devices | **Recognition over recall (Nielsen #6)** — meaningful labels over raw data | "RSSI: -42" means nothing to most users. "Strong" is immediately useful. The raw value can appear alongside for technical users who want precision. |
| Copyable ADB command | **Efficiency of use (Nielsen #7)** — accelerators for expert users | Developers use this command every session. Making it tappable-to-copy saves them from memorizing or context-switching to documentation. |
| Linear progress bar for installation | **Pre-attentive processing** — visual bars perceived faster than numbers | A percentage number alone requires conscious cognitive processing. A visual bar is perceived pre-attentively. Both together serve fast-thinking and analytical modes. |
| Unavailable states shown dimmed | **Progressive disclosure** — reveal capabilities at the right moment | Hiding features when unavailable makes them undiscoverable. Showing them dimmed teaches users the capability exists and what's needed to unlock it. |

---

*"Simplicity is not the absence of clutter — that's a consequence of simplicity. Simplicity is somehow essentially describing the purpose and place of an object and product."*
— Jony Ive
