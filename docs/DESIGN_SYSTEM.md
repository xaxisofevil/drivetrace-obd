# DriveTrace design system

The visual language, why each choice was made, and what a future change has to stay consistent
with. Code lives in `app/src/main/kotlin/com/ericbarone/drivetrace/ui/theme/` (tokens) and
`ui/components/Instrument.kt` (the component vocabulary). Those files are the machine-readable
copy of this document, not an independent source of truth; if they disagree, this document is
wrong and should be fixed, or the code drifted and should be pulled back.

## 1. What this thing actually is

Before picking anything, the honest description of the artifact:

- It is read from a phone mount, in a moving vehicle, frequently at night.
- The most common reading is a glance of a second or two, to answer "is it still working".
- The second most common reading is stationary and attentive: a trip report after a drive.
- The data is numeric, changes continuously, and belongs to distinct vehicle systems.
- It is a diagnostic instrument for a specific mechanical question, not a lifestyle app.

Everything below follows from that list. Where a Material 3 default conflicts with it, the
default loses.

## 2. References drawn from

Named, so a future change can check itself against the same sources rather than against taste.

**Negative-polarity (light-on-dark) displays.** Standard practice in automotive clusters and in
glass cockpits (Garmin G1000, Boeing 787 primary flight displays) specifically because a bright
panel destroys the operator's dark adaptation at night and reflects off glazing. Every serious
OBD tool ships dark by default for the same reason: Torque Pro, Car Scanner ELM OBD2, and
Harry's LapTimer all default to dark. This is the single biggest reason the app locks to dark
rather than following the system setting.

**Colin Ware, *Information Visualization: Perception for Design*** — hue is a pre-attentive
channel, so a category can be identified without reading its label, but the channel saturates
somewhere around eight simultaneous categories. That sets the accent budget at six and forces
the "which system is this" question to be answered by colour rather than by text.

**Redundant encoding / WCAG 1.4.1 (Use of Color).** Colour is never the only carrier of a state.
Every status in this app pairs its colour with a drawn glyph, an accent bar, or both, so the
state survives colour blindness, direct sunlight, and a greyscale screenshot in a bug report.

**ISA-101 and EEMUA 191 (industrial HMI practice).** Normal operation is achromatic; colour is
reserved for the abnormal. This is why there is no green "everything is fine" wash across the
UI, and why the primary readouts are white rather than branded. When everything is coloured,
nothing is.

**Racing telemetry channel colouring (MoTeC i2, AiM RaceStudio).** Channel groups get fixed,
persistent colours across every screen and every session, so an engineer parses a trace by hue
before reading a legend. Same idea here, applied to PID categories: mixture is always teal,
airpath is always violet, forever.

**Fitts's Law, plus NHTSA's visual-manual driver distraction guidelines.** The guidelines treat a
single glance of roughly two seconds as the budget for an in-vehicle task. A missed tap costs a
whole extra glance, and a vibrating cabin with an unbraced hand effectively shrinks a target
below its drawn size. Primary actions are therefore 56dp, above Material's 48dp minimum.

**Tufte's data-ink ratio.** Applied narrowly: 1dp hairline borders instead of shadows and fills,
no decorative chrome, no card that exists only to hold one line of text.

**OEM cluster telltale convention.** The cold-engine indicator is blue in essentially every
production vehicle. That is why the thermal category is blue rather than the "obvious" red, and
why heat is expressed by a value crossing into the status channel instead of by the category
colour.

## 3. Colour

Defined in `ui/theme/Color.kt`. Two channels, and they never borrow from each other.

### Surfaces and text

| Token | Hex | Use |
|---|---|---|
| `Ink` | `#06090E` | Window background |
| `Panel` | `#0D131B` | Standard panel / card fill |
| `PanelRaised` | `#141C26` | A tile nested inside a panel |
| `PanelActive` | `#1A2431` | Selected / pressed panel |
| `Hairline` | `#202B39` | 1dp rules, panel borders |
| `HairlineBright` | `#33455A` | Border of an active or outlined control |
| `Chalk` | `#F2F6FA` | Primary readouts and headings (~18:1 on Ink) |
| `Mist` | `#9AA9BA` | Body copy, secondary values (~9:1) |
| `Ash` | `#7C8B9E` | Labels, units, captions (~5.4:1; only at >=11sp semibold) |
| `Slate` | `#4A5768` | Disabled text, inactive glyphs |

`Ink` is a deep blue-black rather than `#000000` on purpose. Pure black smears visibly during
scroll on OLED, and it makes the hairline borders that define every panel edge impossible to
resolve.

### Category accents: which vehicle system is this

One hue per system, fixed forever, applied to the numeral and the panel accent bar. The mapping
below is the contract; a new PID goes into an existing category rather than getting a new colour.

| Category | Token | Hex | PIDs (see `docs/DATA_SCHEMA.md`) |
|---|---|---|---|
| **MOTION** | `AccentMotion` | `#F2F6FA` (Chalk) | Engine RPM, Vehicle Speed, Engine Load, Engine Absolute Load, Throttle Position |
| **MIXTURE** | `AccentMixture` | `#2ED3C6` | Short/Long Term Fuel Trim (both banks), Fuel-Air Commanded Equivalence Ratio, Mass Air Flow, Fuel Consumption Rate, Fuel Rail Pressure, Fuel Level, and derived MPG |
| **AIRPATH** | `AccentAirpath` | `#8E7BFF` | Intake Manifold Pressure, Intake Manifold Pressure Desired, Turbocharger A/B Compressor Inlet Pressure, Barometric Pressure |
| **THERMAL** | `AccentThermal` | `#5AC8FA` | Engine Coolant Temperature, Air Intake Temperature, Engine Oil Temperature, Catalyst Temperature, Ambient Air Temperature |
| **IGNITION** | `AccentIgnition` | `#FF66C4` | Timing Advance, Knock Retard, Knock Control System |
| **HOUSEKEEPING** | `AccentHousekeeping` | `#7C8B9E` (Ash) | Engine Runtime, Distance since codes cleared, Commanded EGR, EGR Error, Control Module Power Supply |

Two of these are deliberately not colours, and both decisions matter more than the ones that are:

**MOTION is achromatic.** The primary instrument in a cluster or a glass cockpit is white on
black, because maximum luminance contrast is the fastest thing the eye resolves and RPM/speed is
the thing most often glanced at. It also always occupies the hero slot, so position already
identifies it; spending a hue there would waste the one channel the diagnostic subsystems need.
This is ISA-101's rule applied literally: the normal, always-present data is not coloured.

**HOUSEKEEPING is grey.** Tier C data (barometric pressure, ambient temperature, distance since
codes cleared) is present and findable but visually subordinate. Giving it a hue would put it in
the same perceptual class as fuel trim, which is exactly the flattening this redesign exists to
undo.

**MIXTURE inherits the old teal** (`#4FD1C5` retuned to `#2ED3C6` for the darker ground) and is
also the theme's Material `primary`. Fuel trim is the signal this project was built to chase, so
the brand colour and the flagship data category are the same colour on purpose.

### Status: is this thing OK

Never used as a category accent, so "coolant is blue" can never be confused with "coolant is in
trouble". Every one of these is rendered with its glyph alongside it.

| Tone | Colour | Glyph | Means |
|---|---|---|---|
| `NEUTRAL` | Chalk | dash | Nothing to report. Achromatic by design. |
| `UNKNOWN` | `#4A5768` | dots | Still resolving |
| `LIVE` | `#31C56A` | tick | Confirmed good. Used sparingly: the heartbeat dot, a verified upload. |
| `CAUTION` | `#FFC53D` | bang | Degraded but working: stale samples, pending upload, an anomaly flag |
| `FAULT` | `#FF4D4F` | cross | Broken: failed connection, failed upload, no real data from the vehicle |

Each also has a low-alpha fill (`StatusCautionFill` etc.) used for full-width bands, so an alert
reads as tinted glass over the panel rather than a solid block of alarm colour.

### Daylight variants: the same display, in direct sun

A user setting, off by default. Explicitly **not a light theme.** The dark-first argument in
section 2 is about night driving and does not stop being true because it is noon; direct sun is a
different failure, and its fix is not inverting the display but spending more luminance on the
one thing that has to survive a two-second glance. `Ink` stays the ground in both modes.

| Standard | Daylight | Hex |
|---|---|---|
| `Chalk` (= `AccentMotion`) | `DaylightChalk` | `#FFFFFF` |
| `AccentMixture` | `DaylightMixture` | `#7DF5E8` |
| `AccentAirpath` | `DaylightAirpath` | `#C0B4FF` |
| `AccentThermal` | `DaylightThermal` | `#A8E4FF` |
| `AccentIgnition` | `DaylightIgnition` | `#FFA8DC` |
| `Ash` (= `AccentHousekeeping`) | `Mist` | `#9AA9BA` |
| `Slate` (hero with no value, and the hero caption) | `Ash` | `#7C8B9E` |

Every daylight value is its standard twin lifted toward white, not a fresh pick: rule 4's category
contract has to survive the mode change, or the pre-attentive hue channel breaks the moment a
user flips the switch. Pure white appears here and nowhere else, and only on a 64sp numeral; the
OLED smear that rules `#000000` out as a *surface* has no bearing on foreground text.

## 4. Typography

Defined in `ui/theme/Type.kt`. No font file is bundled; Roboto is the platform font and its
metrics are known.

**Tabular figures are the load-bearing decision.** Roboto sets digits proportionally by default,
so `1` is narrower than `0` and a readout ticking from `1899` to `2000` physically changes width,
dragging every digit sideways. At a glance that horizontal jitter reads as motion where there is
none, and it makes a column of numbers impossible to scan vertically. Roboto ships the OpenType
`tnum` feature; Compose exposes it via `TextStyle.fontFeatureSettings`. **Every style that can
contain a changing number sets `fontFeatureSettings = "tnum"`.** This includes the Material body
slots, because analysis figures land there too.

| Style | Size / weight | Use |
|---|---|---|
| `hero` | 64sp Light, `tnum` | The one number that owns a screen. Light weight because at this size a regular weight is a wall of ink, and thin large numerals are what clusters actually use. |
| `large` | 36sp Regular, `tnum` | Second-order hero |
| `medium` | 24sp Medium, `tnum` | The value inside a metric tile |
| `small` | 16sp SemiBold, `tnum` | Inline value in a dense row |
| `label` | 11sp Bold, 1.3sp tracking, always uppercase | Field legend. Engraved on a bezel: identifiable without being readable, so it can shrink far below body size without competing with the number it names. |
| `unit` | 13sp Medium | Unit suffix and secondary labels |
| `mono` | 12sp Monospace | Machine output: MAC addresses, session IDs, the status line, caveats |
| `screenTitle` | 20sp SemiBold | Header title |
| `wordmark` | 18sp Bold, 3.5sp tracking | The DRIVETRACE wordmark |

**Units are always a separate `Text` from the numeral.** Never `"34.2 MPG"` in one string. This
keeps the numeral's left edge and baseline fixed regardless of the unit string, and it puts the
eye on the digits rather than on the word.

## 5. Shape and spacing

`ui/theme/Shape.kt` and `ui/theme/Dimens.kt`.

Instruments are rectilinear: a gauge bezel, a switch panel, a DIN head unit are all shallow radii
on a rectangle. Material 3 runs the other way, toward 16-28dp cards and fully pill-shaped
buttons, which is most of what makes stock M3 read as a consumer phone app regardless of colour.

| Token | Radius | Use |
|---|---|---|
| `chip` | 6dp | Status chips, badges |
| `control` | 10dp | Buttons and inputs. **Not a pill:** equipment, not chat. |
| `tile` | 10dp | Metric tiles |
| `panel` | 14dp | Top-level panels |
| `dialog` | 18dp | Dialogs |

**Elevation is a bezel line, not a shadow.** On a near-black ground a Material elevation shadow
is invisible; a 1dp bright edge is exactly how a real panel cutout reads. Panels are
`background(Panel) + border(1dp, Hairline)`, never `shadowElevation`.

Spacing is a 4dp grid: `gutter` 20dp, `panelPadding` 16dp, `tileGap` 10dp, `section` 20dp.

**Touch targets:** `touchTarget` 56dp for primary actions, `compactTarget` 44dp for secondary.
Above Material's 48dp minimum because the hand is unbraced in a moving vehicle and the effective
target is smaller than the drawn one.

## 6. Component vocabulary

`ui/components/Instrument.kt`. A new screen is assembled from these. Adding a new container type
is a design-system change and belongs in this document.

| Component | Job |
|---|---|
| `InstrumentPanel` | Bordered container with an optional left accent bar carrying category or status |
| `SectionLabel` | Uppercase legend with a rule running to the right edge, binding the label to what it names |
| `HeaderBar` | Wordmark, optional back affordance, one optional action, over a hairline. **Not** a `TopAppBar`. |
| `HeroReadout` | 64sp numeral, separate unit, optional caption. One per screen state. |
| `MetricTile` | The secondary band. Roughly a third the hero's weight. |
| `DataRow` | Dense label/value line. Where Tier C data belongs. |
| `StatusRow` | A named pipeline stage with a dot, a state word, and an optional detail line |
| `StatusBand` | Full-width alert. Tinted fill + accent bar + glyph. |
| `StatusChip` | Compact badge for list rows |
| `ChoiceChip` | One option in a small set, laid out in a row. The logbook's vehicle filter |
| `StatusDot` | Pulsing when live |
| `ConsoleLine` | Monospaced, dim, `>`-prefixed machine output |
| `NoteField` | The one text input: a short drive note. M3 `OutlinedTextField` restyled to the panel language, hard length cap. Used raw in the Stop dialog and wrapped by `DriveNoteEditor` (`ui/DriveNote.kt`) everywhere a note is edited after the fact. |
| `Caption` | Methodology caveats and small print |
| `PrimaryAction` / `SecondaryAction` / `ActionBar` | 56dp full-width primary in a pinned bar. `SecondaryAction` also carries a `busy` state for work that outlives the tap |
| `EmptyState` | Says what to do next, not only what is missing |
| `GlyphMark` | The five drawn glyphs (tick, bang, cross, dash, dots, chevron) |

Glyphs are drawn with `Canvas` rather than pulled from `material-icons`. Six shapes do not
justify an extra dependency and a few hundred KB of vector assets.

**How the daylight boost reaches the hero without touching any screen.** `ReadoutPalette`
(`ui/theme/Color.kt`) is a map from a standard token to its daylight twin plus the two greys the
hero's label and caption use, provided as `LocalReadoutPalette` next to the existing
`LocalReadoutType`. `DriveTraceTheme(highContrast = …)` swaps which instance is provided;
`HeroReadout` is the only composable that reads it, running its `accent` through
`palette.hero(accent)`. No screen has an `if (highContrast)` in it, and an unregistered colour
passes through unchanged rather than failing, so a future accent that forgets a daylight twin
renders at normal luminance instead of rendering as something unrelated. The toggle's own value
lives in `ui/DisplaySettings.kt`, a process-wide `StateFlow` over SharedPreferences, mirroring
`service/LoggingStatus`; the theme takes it as a parameter so `ui.theme` keeps no dependency back
on the screens above it.

**Exactly one animation exists in the app:** `StatusDot`'s slow alpha pulse, roughly one cycle a
second, when data is arriving. Motion is the strongest pre-attentive cue there is, so it only
stays meaningful if it is the only thing moving.

**`SecondaryAction(busy = true)` is that same dot, not a second animation.** An action whose work
outlives the tap (the logbook's retry controls) disables itself and grows a pulsing `StatusDot`
ahead of its label, toned `CAUTION` to match the status table's "pending upload" row: the work is
under way and not yet confirmed. A Material `CircularProgressIndicator` would have been a second
kind of motion saying the same thing the app already has a shape for, and rule 9 rules it out.
The flag is the caller's, and where the work is a WorkManager job the caller reads it back from
WorkManager rather than setting a boolean at tap time; see the logbook below.

## 7. Screen layouts

### SetupScreen — pre-flight

Two decisions, one setting and one action, so it is three labelled config sections over a pinned
action bar, not a scrolling column of controls. The **Display** section holds the single daylight
toggle: a `ToggleRow`, same panel-is-the-target treatment as `SelectableRow` with `Role.Switch`
and a square check mark rather than the round single-choice mark, deliberately not a Material
`Switch` (a 52x32dp sliding pill is the one shape this system rules out everywhere else). It sits
on the pre-flight screen rather than behind a settings screen because the question it answers is
"is it sunny right now", asked while sitting in the car about to press Start. Bonded devices are `SelectableRow` panels where the whole
panel is the target rather than a 20dp `RadioButton` circle, selection is carried by accent bar +
border + fill simultaneously, the MAC address is monospaced (it is an identifier to compare
character by character, not prose), and adapters matching the name heuristic are marked `LIKELY`.
`role = Role.RadioButton` on the selectable preserves the single-choice semantics TalkBack needs
now that the Material widget is gone. The `weight(1f)` on the device `LazyColumn` is load-bearing
and its original comment is preserved verbatim.

### LoggingScreen — two modes, two layouts

The same composable serves two genuinely different jobs, so it now branches into two layouts
rather than one column of rows that changes length.

**LIVE** is read from a mount, in motion, at a glance. It is a gauge cluster, which is what this
whole document was written for and what it could not be until `LoggingUiState` started carrying
live PID values (idea #1 below, since built). The first version of this layout put session elapsed
in the hero and sample/GPS counts in the tile band, which was the honest ranking at the time: the
state object carried nothing from the vehicle at all, so the app's own bookkeeping genuinely was
the most interesting true thing on the screen. It is not any more, and the ranking below is what
replaced it.

- Connection state sits in the header as a pulsing dot + word, costing no content row.
- **Hero: Engine RPM** at 64sp, achromatic. MOTION is the achromatic category and the tachometer
  is what a cluster's largest instrument shows; position plus size already identify it, so the hue
  budget stays with the diagnostic subsystems. It falls back to session elapsed when RPM has not
  answered yet or read back implausible, on the same chain-not-a-dash reasoning the trip report's
  hero uses (rule 13), and elapsed exists from the first second of every session so the chain
  cannot run out. The caption says which case it is.
- **Primary pair:** vehicle speed and current short-term fuel trim, two half-width `MetricTile`s.
  Speed is the other figure a driver already expects; short-term trim is the signal this project
  exists to chase, and watching it move in real time is the whole argument for a live screen over
  an export. A missing half keeps its space rather than letting the survivor stretch across the
  screen and read as a second hero.
- **One alert slot,** reserved for the engine-detected check, unchanged in form. It moved up to
  sit directly under the primary readouts: the content below it is now several screens of gauges,
  and an alert under all of that is an alert nobody sees. Everything else on this screen is
  information; this is the only thing that is a decision, and the band's power comes entirely from
  being the only band.
- **Category grid:** every remaining Tier A/B PID as a `MetricTile`, grouped under a
  `SectionLabel` naming its category and coloured with that category's fixed accent. The label is
  the redundant carrier the hue needs (WCAG 1.4.1, section 2); the accent is what lets "which
  system is this" be answered without reading the label at all. Always three tiles across, even
  for a category holding one or two: the report's tile rows widen to fill because every tile there
  is the same rank, whereas a two-tile row here would render at the same half-width as the primary
  pair above it and flatten the three ranks this screen depends on.
- **Context:** Tier C and everything in HOUSEKEEPING, collapsed into `DataRow`s in one panel, per
  rule 6. Category hue survives the demotion, so ambient air is still thermal blue and barometric
  still airpath violet on the line they end up on. "Slow-changing context" is `PidCatalog`'s own
  description of Tier C and it is exactly what this block is: real data, worth finding, never worth
  a glance while moving.
- **Session:** elapsed, sample count, GPS fixes, last-sample age and reconnects, as `DataRow`s in
  one panel at the bottom. Demoted, not deleted. Every figure there is still true and still worth
  having (the counts are the only proof the GPS collector is running; the last-sample age is what
  separates "the car is idling" from "the link died three minutes ago"), but it is the app talking
  about itself, which is the same class of information as the report's **Capture and delivery**
  block and gets the same treatment. The last-sample tone threshold survives the move (<=5s
  neutral, <=15s caution, beyond that fault), and the reconnect count keeps its caution tone while
  losing its own accent-barred panel, which was a dedicated frame around one integer. Elapsed is
  omitted from this panel whenever it is the hero, so the screen never prints one number twice at
  two sizes.
- **A reading the scheduler flagged `IMPLAUSIBLE` never renders as a number.** `valueNumeric` is
  null on those rows by design (`PLAUSIBLE_RANGES` in `PidScheduler.kt`), so the tile prints `--`
  in `Tone.FAULT` and inherits the cross glyph with it: the status table already has a word for
  "no real data from the vehicle" and this is that word, not a new vocabulary. A gauge that has
  merely gone quiet past its tier's polling budget gets `Tone.CAUTION` instead, the table's "stale
  samples" row. A PID that has never answered draws nothing at all, so `--` in this cluster only
  ever means "the number that came back was garbage", and the unit is dropped alongside it, since
  `-- kPa` reads as a measurement that happens to be missing.
- **No new container type.** The cluster is `HeroReadout`, `MetricTile`, `DataRow`,
  `SectionLabel`, `InstrumentPanel` and `StatusBand`, arranged; the "large" tiles of the primary
  pair are `MetricTile`s in a two-column row, not a new component. The one addition is a table,
  `ui/PidDisplay.kt`, mapping each `canonical_name` to its category, tier, label and decimal
  places, so neither the screen nor the scheduler carries a `when` block full of PID names. Tier
  does double duty there: it picks the container (A/B a tile, C a row) and it sets the staleness
  budget, derived from the scheduler's own rotation arithmetic rather than from taste. A PID
  missing from the table still renders, as a grey housekeeping line, rather than vanishing from a
  screen whose job is showing everything the car said.
- The raw service status string drops to a `ConsoleLine` at the bottom.
- Stop is a full-width 56dp action in a pinned bar, tinted `StatusFault`. Its confirm dialog also
  carries the drive's optional `NoteField`, because Stop is the last moment the drive is still in
  the driver's head.

**COMPLETE** is read stationary, so density is affordable, but the ranking still holds. The
actions live in a pinned `ActionBar` outside the scroll, so Export and New Session can never
become unreachable no matter how many flags the report carries.

#### What the report is ordered by

The first version of this layout was ordered by subject matter: economy, then the drive, then the
codes, then the pipeline, then the rig. Seven equal-weight labelled sections, four of them the app
talking about itself. A photograph of it running on the real phone, after a session that captured
nothing and then failed to upload, showed what that costs. The first two screens were a `--` hero,
a `0.00 km` tile, a red panel about a socket timeout, and a green panel confirming the adapter was
fine. At no point did the screen say the one thing that was actually true, which is that the
drive recorded nothing usable. Every fact needed to conclude that was present. None of them said
it. The reader had to assemble it.

So the report is now ordered by what a driver wants to know thirty seconds after parking, which is
a different list:

1. **Is any of this worth reading.** Existential, and it outranks every figure on the screen
   because it decides whether the figures mean anything at all.
2. **The headline result.**
3. **What the car said.** A stored trouble code is the highest-consequence statement this screen
   can carry.
4. **How the drive went:** profile, anomalies, braking.
5. **What I want to remember about it:** the note.
6. **How much I should trust all of the above, and whether the app still owes me anything.**

#### The sections that implement it

- **Capture verdict band.** The screen's one alert slot, on the same terms as the live screen's:
  nothing else on the report is allowed to be a band, and there is no band at all on a drive that
  went fine. It fires only when the report has no economy figure (caution when distance was
  recorded but Mass Air Flow never answered, fault when neither came back), and its body names the
  likely cause rather than restating the symptom, pointing at the control that holds the detail
  ("the adapter dropped 3 PIDs this drive; open Capture detail below for which ones"). Making that
  connection is the whole job; the old layout had both facts on screen and joined neither.
  **A failed upload deliberately does not qualify for the band.** It retries on its own, the
  logbook has a Retry control, and no data is lost, so it is plumbing.
- **Hero: the best figure the drive actually produced,** down a fixed chain: economy if there is
  any, otherwise distance, otherwise session length. The old rule was "MPG, or a dash", which
  spends 64sp, the largest element on the screen, on the absence of data: a hero reading `--`
  occupies the slot, defeats the hierarchy it exists to create, and still leaves the reader
  hunting for a number that does exist. `--` is now unreachable except during the second or two
  before the on-device pass returns, where it is honest.
  The cost is that the hero changes identity between drives, and this document leans on the hero
  slot's *position* to identify its contents (part of why MOTION is achromatic). The trade is
  worth taking on this screen and would not be on the live one: COMPLETE is read stationary with
  attention, the readout carries its own label and provenance caption, and the accent still obeys
  the category contract, teal for economy and MOTION white for distance and duration, so the hue
  never lies about which system the number came from. "You drove 12.4 km and got no fuel data" is
  a result. "`-- MPG`" is a layout.
  **The two surviving `--` branches render in `Ash`, not `Slate`.** They used to use `Slate`, the
  disabled-text grey, which is about 2.2:1 on `Ink`; at 64sp Light that is a hero which is
  technically drawn and practically not there, in the one state where the screen most needs to be
  legible about what it does not know. `Ash` is ~5.4:1, is the grey the hero's own label already
  uses, cannot be mistaken for a reading because it is achromatic, and keeps rule 13 honest end to
  end: the hero avoids `--` where it can, and where it cannot the `--` is readable. Both greys
  already carry daylight twins, so rule 11 holds unchanged.
- **Diagnostic codes,** immediately under the hero when there are any, unchanged in form: one
  accent-barred panel per code, the code leading and its plain-English meaning under it, the set it
  came from as a `StatusChip`. **Only when there are codes.** The clean case used to get the same
  full-weight section with a green accent-barred "no stored trouble codes" panel, on every single
  drive. That is the "everything is fine" green wash ISA-101 rules out; it becomes wallpaper by the
  third drive and it costs a whole section of vertical space that pushes real content below the
  fold. The clean read is still reported, as one `DataRow` in the capture block, because a confirmed
  clean read is a real result and losing it entirely would be worse than over-showing it.
- **Drive profile** tiles, minus whichever figure the hero took, so the screen never prints the
  same number twice at two sizes. Distance (motion), **duration (motion, new)**, idle fraction
  (mixture), warm-up (thermal). Duration was on the live screen as the hero and then vanished the
  instant the drive ended, so the report could not answer "how long was that" at all; on the
  photographed session, "35 min and 0.00 km" explains the entire drive in one line and the report
  did not have it. Tiles wrap at three per row, with four splitting 2+2 rather than 3+1, because
  four across a phone leaves each tile narrower than the word `WARM-UP` at the label style's
  tracking. A short final row is padded with weighted spacers so its tiles keep the width of the
  rows above rather than stretching out and reading as a second hero.
- **Anomaly flags** and **Braking** keep their own sections; flags are caution-barred panels with a
  glyph rather than `"- $flag"` text.
- **Drive note.** Last thing that is about the drive, first thing the thumb reaches, and directly
  under the evidence it is a reaction to: a drive gets annotated after reading what happened on it,
  not before. Above the capture block on purpose, because the driver's own words rank over the app's
  account of its own plumbing, the same ranking the logbook card already used. See
  `ui/DriveNote.kt`.
- **Capture and delivery.** One subordinate block of `DataRow`-weight lines replacing four
  full sections: **Pipeline**, **Adapter health**, the clean half of **Diagnostic codes**, and
  **On-device cross-check**. Each had its own `SectionLabel` and its own panel frame, and together
  they were most of the report's scroll length while answering one question between them: how much
  should I trust what is above, and does the app still owe me anything. Rule 6 already says Tier C
  data goes in a `DataRow` rather than a tile, and all of this is Tier C by this document's own
  definition of the term.

  **That merge fixed the weight and left the substance alone, which was the actual problem.** A
  photograph of the merged version, taken after a real completed drive, still read in full:
  `13 PIDs dropped`, `Failed reads 259`, `Cooldown pauses 123`, `LONG_TERM_BANK_2 78`,
  `SHORT_TERM_BANK_2 78`, `FUEL_CONSUMPTION_RATE 43`, a three-line caption about unsupported PIDs,
  and then a distance and an MPG that were both already on the screen above it. Compact, correctly
  ranked, and still a QA log for the capture rig sitting in the default first-look state of the
  screen whose whole job is "how was my MPG" — on every drive, whether or not anything went wrong.

  **The rule that sorts it, and rule 15 below states it generally: the block carries verdicts, the
  disclosure carries counts.** A verdict is a word that changes what the reader does next
  (`complete`, `will retry`, `all answered`, `13 PIDs dropped`, `none`). A count is a number that
  only means anything once you have already decided to debug the rig. On a clean drive this block
  is now four achromatic lines and no controls; on a bad one it grows a tone, a glyph, and one
  collapsed control.

  - **Upload** keeps one line and loses `(verified complete)`, which described the delivery
    protocol to nobody. **A failed upload is now `CAUTION`, not `FAULT`,** on the same reasoning
    the verdict band already uses to refuse it a band: it retries on its own, the logbook has a
    control for it, and no data is lost. `WILL RETRY` in amber is what that state is. `FAILED` in
    red spent the status table's "broken" tone on something that heals itself, which is the
    wolf-crying ISA-101 exists to prevent. The success detail (`"412 measurements, 88 GPS, 19
    events"`) moved into the disclosure: it is the app counting its own rows, and `COMPLETE`
    already carried the verdict.
  - **PC analysis** keeps one line and the nesting rule (it only appears once backfill succeeded).
    Its pending detail line, "Waiting on the PC to analyze this drive...", is gone: the state word
    `RUNNING` and the pulsing dot beside it already say that, twice. A *failed* analysis keeps its
    server-authored message, because that is a real cause and the server is the only thing that
    knows it.
  - **Adapter health is the specific demotion.** It was an accent-barred panel under its own
    section label, which on a clean drive meant a green tick at panel weight competing with the
    panels carrying the drive's actual result. It is diagnostic meta-information about the capture
    rig; on a good drive it is the least interesting true statement on the screen. It is now one
    verdict line. **Clean is `NEUTRAL` and glyphless** rather than a green tick, because rule 14
    and ISA-101 both say the normal state is achromatic and a mark on the one row that is fine,
    among rows that carry no mark at all, is decoration. Tone still comes from *distinct* dropped
    PIDs.
  - **The counts behind that verdict sit behind a tap, collapsed by default, offered only when
    something actually dropped.** Failed reads, cooldown pauses, the per-PID breakdown and the
    "unsupported PID vs. bad adapter" caption are all still there, unchanged, one control away.
    Deleting them was not an option and is worth being explicit about: idea #9 is right that
    telling "one unsupported PID cycling through cooldown" apart from "several different PIDs
    failing, so it is the adapter or the link" is the entire reason adapter-health reporting was
    built, and that distinction lives only in those counts. It is simply never the answer to "how
    was my MPG". The control is a `SecondaryAction`, the same component and the same move the
    logbook already uses for its per-card note editor, so this adds no vocabulary and no new
    container type.
  - **The clean-DTC line is unchanged.** One achromatic line confirming a clean read of the
    highest-consequence thing this screen reports is the cheapest true statement on it.
  - **The cross-check now only appears when it is one.** Two figures computed two different ways
    disagreeing is real information; the same figure printed twice is not. The screen passes the
    on-device number down here only when the server also produced one, so on a drive where the
    server never answered — which is every drive where this block used to be at its longest — the
    row and its caption both disappear instead of restating the hero at a twentieth the size. That
    was the state of the photographed report: `23.8` at 64sp, then `23.8` again eleven rows down.
  - **The on-device distance row is gone outright, and it was never a cross-check.** Both figures
    come from the same GPS fixes in the same Room table, one computed on the phone and one on the
    PC after the phone uploaded them. Agreement between them tests the upload, not the
    measurement. Distance already owns a hero slot and a `MetricTile`; this was its third
    appearance on one screen.
  - **The `calculating...` and `n/a (no fuel data)` rows are gone.** The hero says both, at 64sp,
    before the reader ever gets this far.
- Every methodology caveat from the original is preserved, demoted to `Caption`.
- **The report has its own scroll state.** LIVE and COMPLETE used to share one
  `rememberScrollState()`, so the report opened at whatever pixel offset the live layout had been
  left at. A `ScrollState` anchors on an offset rather than on content, these two layouts share
  neither their content nor their length, and this screen deliberately has no scrollbar, so a
  report opened part-way down is indistinguishable from one that rendered wrong. That is exactly
  how it was read: a real screenshot showed an empty bordered box under the header where the hero
  belongs, which turned out to be the empty drive-note field with ~900px of report above the fold.
  See `docs/KNOWN_ISSUES.md` for the pixel measurements that settled it.

#### The failed-upload panel never prints the server's address

`SessionEntity.backfillMessage` on the failure path is `Throwable.message` straight out of OkHttp.
On the real phone that rendered as `failed to connect to <host>/<public IP> (port 8090) from
/<LAN IP> (port 43348) after 10000ms`, at full width in fault red: the user's home network's
hostname, public IP, open port and the phone's LAN address, on a screen anyone holding the phone
can read and that gets screenshotted into forum posts and bug reports.

`ui/PipelineMessages.kt` states the rule and both screens that show upload state go through it.
**It is a whitelist, not a scrubber.** Nothing tries to redact hostnames or IPs out of an error
string; that kind of filtering works right up until the exception format changes, and then it
leaks silently and nobody notices. Instead: a message the app composed itself out of known parts
may be shown (the success line is three integers the app counted, `"412 measurements, 88 GPS, 19
events"`), and anything originating in the transport layer is replaced wholesale by one fixed
sentence about consequences, `"Couldn't reach the server. This drive is saved on the phone and
will upload on its own."` There is no code path that passes a caught exception's text to a
composable.

**The diagnostic detail is unpublished, not destroyed.** The raw string is still written to
`SessionEntity.backfillMessage` (Room, on-device, and deliberately not one of the fields
`CsvExporter` writes into `metadata.json`, so it was never reaching an export bundle either) and
still logged by `StreamingClient`. A developer with adb or a database dump can still see exactly
which host timed out; a passer-by with the phone cannot.

`StreamingClient.pollAnalysis` had the identical leak through a different door: its catch branch
carried `e.message` up into `AnalysisPollResult.Failed`, which the report renders under "PC
analysis". That one is fixed at the source rather than at the screen, because unlike
`backfillMessage` nothing persists it, so logcat is the only place worth putting the raw. The
server-authored analysis errors are untouched: those are the useful ones, and they describe the
server's view rather than this phone's.

### HistoryScreen — logbook

One card per drive. MPG is right-aligned in a fixed column so the eye runs straight down the
numbers and compares drives, which is the only reason to open this screen that is not "why didn't
that one upload". A divider-separated stack of text lines cannot be scanned that way. The card's
left accent bar carries upload state, so a scroll shows which drives still owe an upload without
reading a word. Upload and analysis states become chips; a failed backfill message becomes a
`ConsoleLine` in fault red, carrying the fixed message from `ui/PipelineMessages.kt`, never
`session.backfillMessage`, for the reason given under LoggingScreen above. The header subtitle
summarises "N drives, M not uploaded".

**The card names the vehicle,** on the line under the date, as `VehicleProfile.displayName` and
never the stored enum name. `SessionEntity.vehicleProfile` has tagged every session since the
multi-vehicle work, and the logbook was the one screen where that mattered and the one screen
that never showed it: with two cars in rotation, a column of dates and MPG figures cannot be
compared at all, because the reader cannot tell which drives belong to the same car. It costs no
new line, because it took the slot **`completed` used to hold.** That word was on every card, and
rule 14 applies at line level as well as at section level: a field that reads the same after every
drive is wallpaper. `interrupted` is a real result and still prints. The line is
`vehicle / duration`, with the status appended only when it is not the ordinary one, and it
ellipsises rather than wrapping so the card's height is fixed regardless of how long a vehicle's
name is.

**And the list filters by it.** A row of `ChoiceChip`s (All, then one per vehicle) sits between
the header and the scroll. Naming the vehicle on each card answers "whose drive is this"; the
filter answers the question that immediately follows it, which is "show me only that car", and
without it the MPG column still mixes two vehicles' figures into one column that cannot be
compared down. Three rules it follows:

- **Only vehicles that have actually logged a drive get a chip,** in the enum's own order so the
  row does not reshuffle as drives come and go. A profile nobody has driven is not a filter, it is
  a dead control.
- **The whole row disappears when only one vehicle is represented,** which is rule 14 again: a
  control that can only ever be in one state is clutter on a screen whose job is scanning a column
  of figures. A single-vehicle user never sees it.
- **The header subtitle counts what is on screen,** not what is in the database. With a filter
  applied, "12 drives, 2 not uploaded" would be answering a question nobody asked.

It is pinned above the scroll rather than riding in it, on the same reasoning as the pinned
`ActionBar`: a filter you have to scroll back up to reach is a filter you stop using. The row
scrolls horizontally, so a third vehicle widens it instead of squeezing the names. A non-blank session note appears as a two-line `Mist`
caption between the figures and the chips: the driver's own annotation ranks below what the app
measured but above what the app's upload pipeline did.

**There are two retry controls, and never more than one at a time.** "Retry upload" while the
upload has not succeeded; **"Retry analysis"** once it has but the analysis has not, which is a
real observed state rather than a hypothetical one (the ingest server was up, the analysis server
was not). They occupy the same slot rather than sitting side by side, because at most one thing
can be outstanding: until the drive is on the server there is nothing to analyze, and once the
analysis is done neither control has anything to offer. The card's height therefore does not
change between the two states. The analysis retry skips the upload entirely rather than re-sending
a drive the server already holds in full; see `BackfillRetryWorker`'s `KEY_ANALYSIS_ONLY`.

**The retry control reports its own progress.** It used to enqueue the work and immediately re-read
the database, which happens before WorkManager has started the job, so nothing on the card changed
and the button was indistinguishable from a dead one until you went and checked the server. It now
shows a `SecondaryAction(busy = true)` while its work is outstanding and reverts on its own when
the work finishes, at which point the card reloads: the running-to-finished edge is the moment the
row on disk actually changed and therefore the only moment a reload is worth anything. **The flag
comes from WorkManager, not from the card.** `WorkManager.getWorkInfosForUniqueWorkFlow` on the
same unique name the enqueue used (`BackfillRetryWorker.retryWorkName`) is already the durable
record of whether that job is outstanding; a boolean set at tap time would disagree with it the
first time the app's process died mid-retry, coming back looking idle while the work was still
queued.

The note row is also where a drive gets annotated after the fact. Every card carries an **Add
note** / **Edit note** control on that row, which swaps it for a `DriveNoteEditor`. Collapsed
behind a tap rather than always open, because the list's whole job is to be scanned down a column
of MPG figures and a text field on every card would multiply every row's height for an interaction
that happens once in twenty views. The logbook is where a drive from days ago gets looked at
again, and it is where "that was the one with the new tyres" actually occurs to someone.

## 8. Rules for whoever touches this next

1. One hero per screen state. A hierarchy with two heroes has no hierarchy.
2. A number that changes uses a `tnum` style. No exceptions.
3. The unit is a separate `Text`. Never concatenated into the value string.
4. Category hue is fixed per system. A new PID joins an existing category; it does not get a new
   colour.
5. Status colour never doubles as a category colour, and never travels without its glyph.
6. Tier C data goes in a `DataRow`, not a tile, and never in the hero.
7. Elevation is a hairline border, not a shadow.
8. Primary actions are 56dp and live in a pinned `ActionBar`, never at the end of a scroll.
9. Only `StatusDot` animates.
10. No `TopAppBar`, no hamburger, no bottom nav. Three screens do not need a navigation framework.
11. A colour a hero readout can carry registers a daylight twin in `DaylightReadoutPalette`. The
    map is the whole daylight mode; a token missing from it silently renders at night luminance
    in direct sun.
12. **A caught exception's text never reaches a composable.** Not filtered, not truncated, not
    shown behind a "details" toggle. If a failure needs describing on a screen, the app writes the
    sentence; the raw goes to Room or to logcat. The rule exists because the raw string named the
    user's home server, its public IP and its open port on a screen they might share, and because
    a redaction filter is a rule that silently stops holding the day the string changes shape.
13. **A hero never says `--`.** If the intended figure is missing, the hero falls back to the best
    figure that does exist rather than spending the screen's largest element on an absence. A
    screen state with genuinely no number should be an `EmptyState` or a `StatusBand`, not an
    empty hero.
14. **A section that reports "normal" on every run does not get a section.** A green confirmation
    panel repeated after every drive is wallpaper by the third one, it costs a section of vertical
    space, and it contradicts the ISA-101 rule this document opens with. Report it as a `DataRow`
    among the other Tier C lines and let it earn weight only when it is abnormal.
15. **Verdicts render inline; counts render behind a tap, and only when there is something
    wrong.** A verdict is a word that changes what the reader does next. A count is a number that
    only means something once they have already decided to debug the app itself. Rule 14 covers a
    section that is always normal; this one covers a section that is genuinely useful when
    abnormal and is still not what the screen is for. Collapse it behind a `SecondaryAction`,
    default closed, and hide the control entirely in the normal case, so the resting state of a
    good run carries no controls at all. Demoting such content to nothing is the wrong fix: the
    same numbers that are noise on a good run are the whole diagnosis on a bad one.

---

# Feature ideas surfaced during this pass

Observations from redesigning, roughly in order of how much they would change the product. The
ones since built say so and keep what building them turned out to require, which is the part worth
reading. Monetisation potential called out where it exists.

## 1. ~~The live gauge cluster the theme is already built for~~ — built

**Built.** `LoggingUiState` now carries `latestValues: Map<String, MeasurementSample>`, keyed on
`canonicalName`, and the LIVE layout in section 7 is the cluster this entry described: RPM in the
hero, speed and short-term trim as the primary pair, a `MetricTile` grid grouped and coloured by
category, Tier C in `DataRow`s at the bottom. The prediction that the UI layer was already sized
for it held: no new component was added to `Instrument.kt` to build it.

Three things the entry got wrong or left out, which are the parts worth keeping:

- **The update happens in `DriveLoggingService`'s `onMeasurement`, not in `PidScheduler`.** The
  scheduler has no reference to the status bus and should not grow one; it hands every sample to a
  callback, and the service's callback is already the single place that commits a sample to Room.
  Putting the state update in that same lambda means a value can only reach the screen once it has
  been recorded locally, which is this project's ordering rule everywhere else too (Room first,
  everything else after).
- **The map holds `MeasurementSample`, the scheduler's own poll-result type, not a new UI shape.**
  It already carries value, unit, `qualityFlag` and `wallTimeUtc`, which is exactly what a live
  readout needs, and reusing it means the number on screen is the row that went into the database
  rather than a re-derived copy that can quietly drift from it.
- **The map keeps `IMPLAUSIBLE` samples rather than filtering them out,** so the screen can say
  "this reading is currently garbage" instead of leaving the last good value up as though it were
  current. See section 7 for how that renders. Filtering at the state layer would have looked
  tidier and would have been a lie by omission at the exact moment the adapter started
  misbehaving.

Two smaller findings from actually building it. Grouping by category needs the category's *name*
printed over the group, not just the hue, or the pre-attentive channel is carrying the whole
message alone and breaks the moment a screenshot goes greyscale. And staleness has to be
per-tier: Tier B rotates one command every 3s across a dozen of them, so a Tier B tile is
routinely 36s old with nothing at all wrong, and a single global "stale after N seconds" rule
paints half the cluster amber on a perfectly healthy drive.

*Monetisation, still open:* a configurable gauge layout (pick which PIDs occupy the hero and tile
slots, save per-vehicle presets) is the single most requested paid feature in this app category
and is what Torque Pro's paid tier substantially is. The table in `ui/PidDisplay.kt` is the seam
that would widen for it: slot assignment is currently code in `LiveBody` reading a fixed table,
and a preset is that table plus a stored per-vehicle override.

## 2. Exportable trip report as PDF

`CsvExporter` already produces the bundle and the server already computes the full analysis. A
rendered one-page report — headline MPG, drive profile, anomaly flags, the coolant and trim
traces — is a small step from data the app already holds, and it is the artifact you actually
hand to a mechanic or attach to a forum post. *Monetisation:* clean free/paid line; unbranded or
custom-branded export is a standard paid tier for shop use.

## 3. Drive-to-drive comparison

The logbook now puts MPG in a scannable column, which immediately makes the missing feature
obvious: select two drives and diff them. Same route, different result is the entire question
this project exists to answer, and right now answering it means exporting two CSVs and running a
script. *Monetisation:* a natural premium feature, and the one with the clearest link to the
product's actual purpose.

## 4. Shareable drive summary card

A single generated image — route, distance, MPG, notable flags — sized for a forum post or a
group chat. Cheap to build on the analysis data that already exists, and it is the organic growth
loop for an app whose users already congregate in model-specific forums.

## 5. "My Vehicles" tab: per-vehicle stats, not just a fleet-view idea anymore

**Explicitly requested, not speculative like the rest of this list.** `VehicleProfile` and
`SessionEntity.vehicleProfile` already make every session vehicle-tagged; nothing in the UI uses
that yet beyond the Logbook filter (see the note on that filter shipping alongside this doc
update). A new top-level tab, alongside Setup and Logbook, showing per-vehicle rollups:

- Total drives, total distance, overall MPG per vehicle, the numbers the Logbook's own
  MPG-scan-column exists to support but currently only per-drive, never aggregated.
- Combined trim trend over time per vehicle, the actual mechanism behind idea #6's baseline/drift
  alerting below, this tab is the natural home for that once it exists rather than a separate
  screen.
- Per-vehicle DTC history: has this specific car ever thrown a code, when, how often, using the
  `DtcCatalog` lookup already shipped this session.
- Adapter health rolled up per vehicle too, useful signal if the same cheap clone is used across
  both cars, or if one vehicle's OBD port behaves worse than the other's (a real, open question
  for this user given the Subaru profile is still marked untested in `DATA_SCHEMA.md`).

Not yet built. The Logbook vehicle filter shipping now is the prerequisite groundwork (same
underlying vehicle-tag data, one screen scoped to a single drive at a time vs. this one scoped to
a vehicle's whole history), not the same feature. *Monetisation, if ever needed:* the standard
fleet-tier split (free for one or two vehicles, paid beyond) most vehicle apps use, and it would
fit genuinely rather than as an artificial gate, though this is now planned as a real feature
regardless of that.

## 6. Baseline and drift alerting

Long-term fuel trim creeping over weeks is exactly the kind of slow signal a human never notices
and a database trivially catches. "LTFT on this vehicle has moved from +3% to +9% over the last
month" is the product this project's diagnostic question actually wants, and it needs no new data
collection at all. *Monetisation:* the strongest recurring-revenue story here, since it is a
service that accrues value over time rather than a one-off tool.

## 7. Live threshold alerts

The `StatusBand` and tone machinery already exist. Speaking or chiming on a user-set threshold
(coolant above X, knock retard beyond Y, trim outside a band) is the feature that lets the phone
stay face-down during a drive, which is both a safety improvement and the answer to "why am I
staring at my phone while driving".

## 8. Premium instrument skins

Explicitly worth flagging because the token layer now supports it cleanly: every colour, shape,
and type value routes through `ui/theme/`, so alternate visual identities (a warm amber
seventies-cluster theme, a high-contrast daylight theme, an OEM-alike per vehicle) are a palette
swap rather than a rewrite. Low effort, and cosmetic packs are among the least objectionable
things to charge for since nothing functional sits behind the paywall.

The daylight variant named above has since been built (section 3) and is a useful proof of the
claim, with one correction to it: it needed a `CompositionLocal` carrying a token *mapping*, not
just a second set of token values, because the screens name colours by their standard token. A
full skin would work the same way, and `ReadoutPalette` is the seam to widen.

## 9. Smaller things noticed while working

All four are built. Kept here rather than deleted, since what each one turned out to require, and
what got deliberately left out of it, is the useful part.

- ~~**Adapter health from data already collected.**~~ **Built.** `computeAdapterHealth`
  (`data/SessionDiagnostics.kt`) counts `PID_NO_DATA` / `PID_COOLDOWN` events for one session.
  Tone is driven by *distinct* PIDs, not raw failure count: one PID failing two hundred times is
  an unsupported PID cycling through cooldown (expected, see KNOWN_ISSUES.md), several different
  PIDs failing is the adapter or the link, and the caption says so. **Since demoted twice:** it
  shipped as its own accent-barred panel under its own section label on the trip report, and
  seeing that on a phone made it obvious it was outranking the drive's actual figures to report
  that the rig was fine. That became one `DataRow` in **Capture and delivery**, which fixed the
  weight and kept every count inline, so a real drive's report still read `Failed reads 259`,
  `Cooldown pauses 123`, `LONG_TERM_BANK_2 78` by default. **The verdict now stays inline and the
  counts moved behind a Capture detail control, collapsed, and only offered when something
  dropped.** The reasoning in this entry is exactly why they moved rather than went away: the
  unsupported-PID-versus-bad-adapter call is the whole point of this feature and it cannot be made
  without them. See section 7 and rule 15.
- ~~**Session notes.**~~ **Built,** and since extended past the Stop dialog. A single-line
  `NoteField` still opens in the Stop confirmation, written onto `SessionEntity.notes` (the column
  already existed, so no schema bump) before backfill runs, because Stop is the moment the drive
  is still in the driver's head. It turned out to be a bad *only* moment: the thing worth writing
  down is often what you work out on the walk back from the car, and a dialog with a Stop button
  in it is not where anyone composes a careful sentence. `DriveNoteEditor` (`ui/DriveNote.kt`)
  wraps the same `NoteField` in a load-edit-save cycle over `SessionDao.getSession` /
  `updateSession` and now appears on **both** screens that show a drive: always open in a **Drive
  note** section on the trip report, and behind an **Add note** / **Edit note** control on each
  logbook card. Saving is explicit rather than on-blur or per-keystroke, because a silent autosave to a
  field whose state the user cannot see is exactly the interaction that makes someone reopen a
  screen to check whether it took. The Save control appears only when the draft differs from
  what is stored, so the resting state of a correct note is a plain field with nothing shouting
  next to it. Clearing a note to empty stores `null` rather than `""`, so every reader's existing
  `isNotBlank` check keeps working. **It reaches the server now too,** which it did not when this
  entry was first written: neither `/sessions/{id}/start` nor `/end` could carry a note edited
  days after the drive, so the DuckDB copy's `notes` column stayed null forever. A narrow
  `PATCH /sessions/{id}/notes` fixes that; see DATA_SCHEMA.md for the endpoint and for why
  re-posting `/start` would have been the wrong way to do it. The order is the important part and
  it is the general rule for this app rather than anything specific to notes: **Room commits
  first and synchronously, then the server hears about it, fire-and-forget.** The confirmation
  next to the field is a statement about Room, which is what it is worth; a push that never lands
  costs nothing, interrupts nobody, and leaves the note exactly where it already was.
- ~~**A daylight-readable high-contrast mode.**~~ **Built,** and see section 3's daylight table
  for the tokens and section 6 for the mechanism. Still not a light theme: `Ink` stays the ground
  in both modes and only `HeroReadout` reads the boosted palette. Toggle lives on SetupScreen
  under a **Display** section, persisted as `high_contrast_daylight` in the existing
  `drivetrace_prefs` store. **Deliberately hero-only for now:** `MetricTile`, `DataRow` and the
  status chips are unchanged, so the second-order band is still `Mist`/`Ash` in direct sun. The
  mechanism extends to them for free (read `LocalReadoutPalette` in those composables too) if
  real use says the hero alone isn't enough.
- ~~**DTC display.**~~ **Built,** on the trip report rather than the setup screen. A DTC is
  per-session data read from a session that does not exist yet when Setup is on screen, so Setup
  could only show the *previous* drive's codes (misleading, since the entire point of a code is
  that it is current) or open a connection of its own to populate one panel. `readSessionDtcs`
  parses the three `ONE_TIME_READ` events back out and `data/DtcCatalog.kt` supplies the meaning;
  see DATA_SCHEMA.md for the table's coverage limits and the structural fallback for codes
  outside it. Current codes are fault-toned, pending and permanent are caution-toned, and "no
  stored trouble codes" is stated explicitly rather than left as an absent section, because a
  confirmed clean read is itself the answer someone opened the screen for. **Half-revised since:**
  the clean read is still stated, but as one `DataRow` in **Capture and delivery** rather than as a
  green accent-barred panel under its own section label. The original argument holds, since dropping the
  clean read entirely would be a real loss, but it never justified full-section weight on every
  single drive, which is what the first version gave it. Codes *present* still get the full
  treatment, now immediately under the hero.

## 10. Android Auto dashboard, instead of the phone screen

The actual premise of Android Auto is that the driver stops looking at the phone. A live MPG/
trim/boost dashboard projected to the car's own screen is strictly safer than the same thing on
`LoggingScreen`, and Google's own driving-distraction rules end up reinforcing this design system
rather than fighting it: the Car App Library's templates (there is no custom Compose surface on
Android Auto, only a fixed set of Google-rendered templates) cap how much text and how many rows
can appear while driving, which forces the exact "one hero value, a few subordinate ones"
hierarchy this document already committed to. `PaneTemplate` (a handful of large label/value
`Row`s) is the natural fit: trip MPG as the hero row, connection state and current trim as the
other two, is plausibly the whole screen.

Play Store restricts Car App Library listings to five categories (navigation, point-of-interest,
IoT, VoIP, weather), none of which fit a diagnostics dashboard. That restriction doesn't apply
here: it's enforced at Play review, not at runtime, and Android Auto ships a developer "Unknown
sources" toggle (Settings → Apps → Android Auto → tap the version info ten times) specifically
for running apps outside the approved categories, the same sideload posture this whole app
already lives in.

**Dependency already met.** This needed the same plumbing as idea #1 (live PID values flowing into
a shared state object), and idea #1 has since shipped it. `LoggingStatus` is a process-wide
`MutableStateFlow`, not Activity-scoped, so a Car App `Screen` can collect `latestValues` from the
exact same source the phone cluster reads. The plumbing was built once and both surfaces benefit;
nothing further is needed on the data side.
Screens redraw via `invalidate()`, subject to a minimum update interval Android Auto enforces
(roughly once a second, not live telemetry rate).

**Worth weighing against the stated goal:** a car screen still costs a glance; idea #7's
chime/threshold alert costs none. They're complementary, not competing, but if only one gets
built first, the alert is arguably the safer one.

*Testing:* Google's Desktop Head Unit (DHU) tool covers at-desk iteration; the 2020 Mazda 6's
factory infotainment supports Android Auto for real-vehicle testing when wanted.

## 11. Cross-vehicle YMM comparison: "is this normal for your car, or just your car"

Every session already records `vehicleProfile` (Year/Make/Model, effectively — see
`VehicleProfile.kt`), so once more than one user's data lives on a server, the same "compare
against a baseline" idea `ANALYSIS_STARTING_POINTS.md` already flags as the single biggest gap
for one user's own drive history extends naturally to a *community* baseline: how does this
car's MPG and load compare to other same-or-similar-YMM vehicles under matched conditions (speed
bin, load bin, trip type)?

That comparison is what turns "sensor data looks clean" from a dead end into a lead. The
motivating case: engine load is high and MPG is low, but trim, temps, and knock all read normal,
so nothing in *this car's own data* points anywhere. If other same-YMM vehicles show
meaningfully better MPG at matched speed/load, that's evidence the problem is mechanical drag
outside the sensors this project reads at all, rolling resistance, brake drag, alignment, which
is exactly the "normal trims but high load at matched speed" hypothesis bucket the original
blueprint lists and this project has had no way to test against anything but one user's own
history. Surfacing "cars like yours average 31 MPG in these conditions; consider checking tire
pressure, alignment, and brake drag" turns a shrug into a next step.

*Monetisation:* probably the strongest one on this list. It requires data from other users to be
worth anything at all (a cold-start problem any single install can't solve alone, unlike every
other idea here), which makes it a natural subscription: the product isn't the comparison logic,
it's the aggregate dataset, and that dataset only gets more valuable as more paying users
contribute to it. It's also the one idea here that couldn't be cloned by a user just reading this
document and doing it themselves.

**What it actually needs, none of which exists yet:** a multi-user backend (today's server and
DuckDB are explicitly single-user/personal, see `server/README.md`); anonymization before
anything resembling raw drive data leaves a user's own session (aggregate statistics per YMM/
condition bucket, never another user's raw drive); a minimum sample size per bucket before
showing a comparison at all, so a YMM with three cars in the system doesn't produce a
confident-sounding claim off a tiny, noisy sample; and a real definition of "matched conditions"
(the same speed-bin/load-bin bucketing `compare_drives.py` was already going to need for the
single-user case, per `ANALYSIS_STARTING_POINTS.md` item 2, extended across users instead of
across one user's own sessions). That last part means this idea and the single-user cross-drive
comparison gap aren't two features, they're the same bucketing infrastructure built once and
then pointed at two different populations.

## 12. Automatic drive-end detection (designed, not built)

Right now every session ends by a human tapping Stop, in-app or (as of the notification fix)
from the shade. The ask: detect the drive is actually over and end the session automatically,
in a way that survives a stick-shift car with no "Park" gear at all.

**Gear position is the wrong signal, full stop.** A generic Mode 01 gear-position PID barely
exists across real vehicles even on automatics, and a manual transmission has no Park state to
detect in the first place, neutral-plus-parking-brake isn't reliably exposed either. Any design
built around reading the gearbox is dead on arrival for exactly the vehicles this feature most
needs to work on.

**Engine RPM sustained at zero is the actual answer.** It's the one signal that means the same
thing regardless of transmission type, make, or model: the engine stopped running. No new PID,
no per-vehicle special-casing, Tier A already samples it fast.

**The real design problem is the threshold, not the signal.** A short debounce falsely ends the
session at the first red light on any car with idle stop-start (RPM genuinely drops to 0 for
the duration of the stop, by design, and modern US-market cars increasingly have this even where
past model years didn't). A multi-minute sustained-zero requirement avoids this almost entirely,
stop-start cycles resume in well under a minute; something on the order of 5 minutes continuous
is conservative enough to essentially never misfire on a stoplight while still ending a session
reasonably promptly after a real parking event. Speed reading zero for the same window is a
cheap, redundant sanity check against a stray RPM sensor glitch, not the primary signal.

**A secondary, corroborating signal worth wiring in alongside RPM:** many cheap ELM327 clones
draw power straight from the OBD port and lose power the instant the ignition circuit feeding
that port is cut, so "engine off" can also show up as the adapter dropping off Bluetooth
entirely rather than answering "0." The existing reconnect/backoff logic already handles a
transient drop; a reconnect attempt that's been failing continuously for several minutes,
clearly past the range of a normal signal hiccup, is itself evidence the drive is over on a
vehicle whose OBD port is switched with ignition (common) rather than always-hot.

**UX stance, matching how this app already treats uncertain calls elsewhere:** flag and act
conservatively, don't require the user to be watching. Nobody's looking at their phone the
moment they turn the engine off and get out, so a "tap to confirm" prompt with any real timeout
is pointless, by the time anyone would see it the moment's passed. Auto-stopping after the
threshold and labeling the resulting trip report as auto-ended (so the user isn't confused about
why a session closed without them tapping anything) is the right default, the same
flag-don't-silently-decide instinct behind how `IMPLAUSIBLE` measurements and the demoted DTC
mode-echo bytes are handled: never hide that a judgment call was made, just don't block on a
human confirming it in real time. Should be a toggle (default on, since this is what was asked
for) with a configurable minutes threshold, following the same `SharedPreferences` pattern
already used for the daylight-contrast toggle.

**Where it hooks in:** `DriveLoggingService`'s own sample-processing path (wherever it already
sees each Tier A RPM reading) tracking a running "RPM has read ~zero continuously since" 
timestamp, reset on any reading meaningfully above idle. On threshold, call the same internal
stop path `ACTION_STOP` already uses, `stopSession()`, directly, no need to round-trip through
Android's intent system for a self-triggered stop. Not yet built.
