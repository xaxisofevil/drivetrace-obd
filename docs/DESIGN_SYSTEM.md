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
| `StatusDot` | Pulsing when live |
| `ConsoleLine` | Monospaced, dim, `>`-prefixed machine output |
| `Caption` | Methodology caveats and small print |
| `PrimaryAction` / `SecondaryAction` / `ActionBar` | 56dp full-width primary in a pinned bar |
| `EmptyState` | Says what to do next, not only what is missing |
| `GlyphMark` | The five drawn glyphs (tick, bang, cross, dash, dots, chevron) |

Glyphs are drawn with `Canvas` rather than pulled from `material-icons`. Six shapes do not
justify an extra dependency and a few hundred KB of vector assets.

**Exactly one animation exists in the app:** `StatusDot`'s slow alpha pulse, roughly one cycle a
second, when data is arriving. Motion is the strongest pre-attentive cue there is, so it only
stays meaningful if it is the only thing moving.

## 7. Screen layouts

### SetupScreen — pre-flight

Two decisions and one action, so it is two labelled config sections over a pinned action bar,
not a scrolling column of controls. Bonded devices are `SelectableRow` panels where the whole
panel is the target rather than a 20dp `RadioButton` circle, selection is carried by accent bar +
border + fill simultaneously, the MAC address is monospaced (it is an identifier to compare
character by character, not prose), and adapters matching the name heuristic are marked `LIKELY`.
`role = Role.RadioButton` on the selectable preserves the single-choice semantics TalkBack needs
now that the Material widget is gone. The `weight(1f)` on the device `LazyColumn` is load-bearing
and its original comment is preserved verbatim.

### LoggingScreen — two modes, two layouts

The same composable serves two genuinely different jobs, so it now branches into two layouts
rather than one column of rows that changes length.

**LIVE** is read from a mount, in motion, at a glance:

- Connection state sits in the header as a pulsing dot + word, costing no content row.
- **Hero:** session elapsed at 64sp. This is the number actually worth a glance today.
- **Capture band:** three tiles at roughly a third the hero's weight — samples, GPS fixes, last
  sample age. The last-sample tile carries a tone threshold (<=5s neutral, <=15s caution, beyond
  that fault). This is styling only; nothing about polling or logging changed. It turns a number
  already on screen into something whose severity is readable without arithmetic.
- **One alert slot,** reserved for the engine-detected check. Everything else on this screen is
  information; this is the only thing that is a decision, and the band's power comes entirely
  from being the only band.
- Reconnect count appears as a caution panel only when non-zero.
- The raw service status string drops to a `ConsoleLine` at the bottom.
- Stop is a full-width 56dp action in a pinned bar, tinted `StatusFault`.

**COMPLETE** is read stationary, so density is affordable, but the ranking still holds:

- **Hero:** trip MPG in mixture teal, with a caption naming its provenance. The server figure is
  preferred (it gates on stoichiometric operation); the on-device figure is the fallback.
- **Drive profile:** distance (motion), idle fraction (mixture), warm-up (thermal) as tiles, each
  in its category's colour, each shown only when non-null exactly as before.
- **Pipeline** panel groups upload and analysis into one question with two stages, instead of two
  unrelated status lines. The nesting rule is unchanged: analysis only appears once backfill
  succeeded.
- **Braking** and **Anomaly flags** get their own sections; flags become caution-barred panels
  with a glyph rather than `"- $flag"` text.
- **On-device cross-check** stays visible even when the server figure is the hero, because the
  two disagreeing is itself information and it is the only figure that exists when the server was
  never reachable.
- Every methodology caveat from the original is preserved verbatim, demoted to `Caption`.

The actions moved out of the scroll area into a pinned `ActionBar`, so Export and New Session can
never become unreachable no matter how many flags the report carries.

### HistoryScreen — logbook

One card per drive. MPG is right-aligned in a fixed column so the eye runs straight down the
numbers and compares drives, which is the only reason to open this screen that is not "why didn't
that one upload". A divider-separated stack of text lines cannot be scanned that way. The card's
left accent bar carries upload state, so a scroll shows which drives still owe an upload without
reading a word. Upload and analysis states become chips; a failed backfill message becomes a
`ConsoleLine` in fault red. The header subtitle summarises "N drives, M not uploaded".

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

---

# Feature ideas surfaced during this pass

Observations from redesigning, not implemented, roughly in order of how much they would change
the product. Monetisation potential called out where it exists.

## 1. The live gauge cluster the theme is already built for

**This is the big one.** `LoggingUiState` (`service/LoggingStatus.kt`) carries session
bookkeeping only: connection state, counts, timestamps. It does not carry a single live PID
value. The Logging screen therefore cannot show RPM, speed, fuel trim, boost, or coolant while
driving, no matter how it is styled, and this pass could not add it without modifying the service
layer that was explicitly out of scope.

The fix is small and well-defined: add a `latestValues: Map<String, MeasurementSample>` (keyed on
`canonicalName`) to `LoggingUiState`, updated by `PidScheduler` on each successful poll. The UI
layer is already sized for it — `HeroReadout`, `MetricTile`, and the six category accents exist
precisely to receive it. The intended live layout is RPM in the hero slot, speed and current
short-term trim as large tiles, then a `MetricTile` grid grouped and coloured by category, with
Tier C collapsed into `DataRow`s at the bottom.

*Monetisation:* a configurable gauge layout (pick which PIDs occupy the hero and tile slots, save
per-vehicle presets) is the single most requested paid feature in this app category and is what
Torque Pro's paid tier substantially is.

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

## 5. Multi-vehicle fleet view

`VehicleProfile` and `SessionEntity.vehicleProfile` already make every session vehicle-tagged;
nothing in the UI uses that. A per-vehicle logbook with per-vehicle baselines is nearly free
given the existing data model. *Monetisation:* the standard fleet-tier split (free for one or two
vehicles, paid beyond) that most vehicle apps use, and it fits genuinely rather than being an
artificial gate.

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

## 9. Smaller things noticed while working

- **Adapter health from data already collected.** `PID_COOLDOWN` and `PID_NO_DATA` events are
  logged every session. A "your adapter dropped 14 PIDs this drive" summary would tell a user
  their cheap clone is the problem, which is currently invisible to them.
- **Session notes.** `SessionEntity.notes` exists and nothing writes to it. "Cold start, highway,
  93 octane" typed at Stop time is exactly the metadata that makes drive-to-drive comparison
  meaningful.
- **A daylight-readable high-contrast mode.** Distinct from a light theme: same dark ground, but
  boosted luminance on hero readouts for direct sun. This is what the dark-first decision should
  be paired with, rather than a conventional light mode.
- **DTC display.** DTCs are already read once at session start and stored as events, but the UI
  never surfaces them. A check-engine light with a code and its plain-English meaning on the
  setup screen is high perceived value for effort that is nearly zero.
