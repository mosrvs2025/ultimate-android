# Launcher looks (2.30.0; living looks and wallpaper colours 2.31.0; home cards 2.32.0)

Code: `launcher/LauncherLook.kt` (parts, tokens, presets, share code), `launcher/LauncherClock.kt` (the clock layouts),
`launcher/LauncherScreen.kt` (favourites layouts, app-row variants, rail styles), `launcher/LauncherSettings.kt` (the settings and
their storage), `launcher/LauncherSettingsSheet.kt` (the Themes page).

## A look is parts, not a colour

Before, a "theme" was two accent colours and a background; every theme was the same arrangement in different paint. Now a look is
a set of parts, each its own setting, and a preset sets them all at once:

| Part | Choices |
| --- | --- |
| Colours | `Accent` (the pair of colours): Aurora, Sunset, Ocean, Meadow, Rose, Ember, Phosphor, Amber, Mono, Ink |
| Background | `Backdrop`: Aurora, Black, Graphite, Midnight, Dusk, Terminal, Paper, the phone's wallpaper. Each carries its text colours too. |
| Typeface | Default, Clean (sans), Serif, Mono |
| Clock | Big day (the original), Digital, Terminal, Editorial, None |
| Favourites | List (the original), Numbered, Grid, Pills, Centered |
| Letter rail | Liquid (the original), Plain letters, Hidden until touched |
| Icon shape | As they are, Circle, Squircle |

Presets (`Looks.all`): **Aurora** (the original; the default settings *are* Aurora, so nobody's launcher changes), **Terminal**,
**Paper**, **Zen**, **Tiles**, **Dusk**. Any single part changed afterwards makes a look of your own (`Looks.matching` returns null
when the parts match no preset; how many favourites show is deliberately not part of what makes a look).

## How it is drawn

Everything the launcher draws reads `LookTokens` (`LocalLook`) instead of hard-coded white: the main and quiet text colours, whether
the background is light, the search panel's colour, the typeface, the rail style, the icon shape. Two consequences worth knowing:

- **A light look** (Paper) has dark text, and the status bar and navigation bar icons are switched to dark with it
  (`WindowCompat`). The search panel is dark whatever the look, so there a light look's text becomes paper-coloured and its dark
  accent becomes amber (`LookTokens.onSheet`).
- **The edge rail** (the rail that slides over other apps) sits on a dark scrim, so it always keeps light text; it takes only the
  look's typeface, icon shape and rail style (a hidden rail would be invisible over another app, so it is drawn as plain letters).

New settings are stored as plain strings under the existing `x.<name>` keys, so an older install's settings and a value a later
version adds both fall back to the default instead of failing (`SettingsCodec.extras` / `withExtras`).

## Sharing a look

A look as one line of text: `ULOOK1:Phosphor|Terminal|Mono|Terminal|Numbered|Plain|AsIs|6|false` (accent, background, typeface,
clock, favourites, rail, icon shape, favourites shown, icons in the lists). Copy it on the Themes page, paste it on another phone.
Only the look travels: never apps, hidden apps or gestures. Reading is forgiving (an unknown name keeps what was there; a
non-look text is refused).

## How it was checked

Unit tests: every preset is different and recognised once applied; the default settings are the Aurora look; each look's
settings store and read back; text contrast on every background (main text 7:1, quiet 3:1) and on the search panel for every look;
share codes round-trip and junk is refused. And, new in this release, **the renderings were looked at**: `screenshots.yml` (a
by-hand workflow) renders the Compose screenshot tests on GitHub's runners and attaches the images. Looking at them found four
real problems that no unit test would have: Zen's clock was left-aligned over centred favourites, Terminal's date wrapped awkwardly,
the look-card previews drew hard squares, and (the important one) in Paper the search results were dark ink on a dark panel.

## Not yet verified

On a phone: how a look feels under the thumb (the rail styles above all), the dark status-bar icons in Paper, and every look with
real icons (the renderings use sample apps with placeholder discs).

## Ideas this leaves room for

Looks that change by themselves (by time of day, Focus, activity, a rule); home cards that show Ultimate's own data (activity
rings, heart rate, a mesh camera); "match my wallpaper" colours; any colour, not just the presets; a look editor with saved slots.


## Living looks (2.31.0)

Code: `launcher/LivingLook.kt` (rules, all plain arithmetic), `health/ActivityGlance.kt` (today's step count, for the goal rule),
`LauncherRepo.living` / `tick()` / `refreshSignals()`.

A **rule** is "when" plus "which look": a time window (`Between(from, to)` in minutes after midnight, wrapping midnight when `from > to`,
so 22:00 to 06:00 works; `from == to` never applies), **while a focus session runs** (running and not paused), or **once today's step
goal is reached** (until midnight). The look is a preset id (`zen`) or a whole `ULOOK1:` code, so a look of your own can be used.

- The look you pick yourself stays your **everyday look** (`repo.settings`). `repo.living` is that with the first applying rule's look on top;
  the home screen and the edge rail draw `living`, the Themes page edits `settings`. A rule changes only the look's parts: hidden apps,
  gestures, favourites and the rules themselves are never touched.
- Rules are checked top to bottom, the first that applies wins; a rule whose look can't be read is skipped. Up to six rules; the Themes
  page has one-tap starting points (Zen at night, Paper by day, Terminal while focusing, Dusk when the goal is reached), move up/down and
  remove, and a small builder with 30-minute steppers.
- The signals are read every 30 seconds while the home screen is up (and each time you come home): the clock, `UltimateApp.focus.current`,
  and `ActivityGlance.goalReached`. The step count is read from Health Connect at most every five minutes, only when a goal rule exists,
  and only if the steps permission was already given (the launcher never asks); with no permission the goal simply never "is reached".
- Stored as one `x.rules` string (`t1320-360@zen` per line; `focus@terminal`; `goal@dusk`). Damaged or future-version lines are dropped,
  the rest kept.

## Colours from the wallpaper (2.31.0)

Code: `launcher/WallpaperPalette.kt`, `LauncherRepo.watchWallpaper()`.

`Accent.Wallpaper` ("From wallpaper") takes the colours the phone itself found in the wallpaper (`WallpaperManager.getWallpaperColors`:
primary, secondary, tertiary) and picks two: the most colourful leads, the second is a clearly different hue (or the first turned 38 degrees
when the picture has only one colour). Wallpaper colours are often dark or washed out, so each is moved in lightness, keeping its hue,
until it is at least **4.5:1** against the lightest dark background (and against Paper's page on a light look). The result lives in
`WallpaperInk` as Compose state, so the glow, rail and highlights change the moment the wallpaper (or a look's background) changes. With no
wallpaper colours it is the original teal and violet. It is shareable in a look code (the other phone uses its own wallpaper).
The **Mirror** look pairs it with the phone's wallpaper as the background.


## Home cards (2.32.0)

Code: `launcher/HomeCards.kt` (the cards and their wording), `health/HeartGlance.kt` (the heart read and its words),
`LauncherRepo.cards` / `refreshCards()`, `LauncherSettings.cards`.

Three cards under the clock, each **off until switched on** (Settings, Home screen, Cards; stored as `x.cards`, so an old install has none):

| Card | Shows | Tap opens |
| --- | --- | --- |
| Activity | the three rings, today's steps, active time, and "+1,200 vs usual" against your usual day (`ActivityMath`) | Timeline, Activity tab (`EXTRA_SOURCE` = `activity`) |
| Heart | the newest heart rate if it is under 6 h old, else the resting rate, with how old it is | Health |
| Cameras | "Kitchen camera" or "2 cameras", who is sharing, and how many are being watched from this phone | Mesh |

- Drawn in the look being worn (text colours, typeface, accent), as small rounded blocks with ellipses instead of clipping. A card with
  nothing to say (no camera sharing) is not drawn.
- The launcher **never asks for permission**. Activity reads through `ActivityRepo`; Heart through `HeartGlance` (newest sample in 3 days,
  newest resting rate in 4 days, kept for two minutes). Without Health Connect or the permission the card says "Tap to allow ..." and a tap
  opens the matching page. Cameras reads `CamNodes.list()` (the owner's own mesh devices that chose to share), never frames, and is empty
  in editions without the mesh.
- The cards refresh about every minute while the home screen is up, only for the cards that are on.

Checked: `HomeCardsTest` (wording, short pace line, camera lines, storage), and renders of all three cards in Aurora, Terminal and Paper,
which led to shorter copy. Not checked on a phone with real data.

### Back in the launcher settings (2.32.0 fix)

`HomeActivity` used to override the old `onBackPressed()` with an empty body. Phones that deliver Back the old way (Android 13 and 14 for an
app that hasn't opted in to predictive back) call that method, so Back never reached the Compose `BackHandler`s of the settings pages, the
edit panel, search or the letter list; only the on-screen arrow worked. It now adds one always-enabled no-op `OnBackPressedCallback` before the
screen is composed (so the screen's own handlers, added later, are asked first and bare home still never closes).

Which page is open is now owned by `LauncherHome`, with a single `BackHandler` using `SettingsBack.next` (page to list, list to home).
`SettingsBackTest` covers those rules and reads `HomeActivity.kt` to check the old override is gone. A CI run of a Compose test that pressed
Back through a dispatcher showed the handler does run and clears the page, but that test made `HostsScreenTest` (Robolectric, run after it)
stall on "not idle" even when it passed (confirmed by a run without it: 1149 tests, 0 failures), so it was removed. Not tried on a phone.

## Saved looks and QR (2.33.0)

Code: `launcher/LookSlots.kt` (`LookSlot`, `LookSlots`, `LookQr`), the Your looks card and QR dialog in `LauncherSettingsSheet.kt`.

- **Saved looks**: up to `LookSlots.MAX` (8), each a name (at most 24 characters, tabs/newlines cleaned so the stored lines can't break) and the
  whole look as a `ULOOK1:` code. A saved look is a copy of the look's parts, never a pointer to a preset, so changing the presets in a later version
  can't change it. Saving under a name already used (any case) replaces it; a full list refuses a new name but can still replace one. Stored as one
  string, `x.slots`, one `name<TAB>code` per line; reading drops any line that isn't a name and a readable look.
- **Wear / QR / delete** per saved look; the card also shows a small preview of the look being worn and suggests a name (the preset's name, or
  "My look", numbered until free).
- **Rules can wear a saved look**: the rule builder lists them next to the presets (the rule stores the look's code, so deleting or renaming the saved
  look later doesn't break the rule); `LivingLook.lookName(code, slots)` shows the saved name.
- **QR**: `LookQr.cells(code)` draws the code with zxing (already a dependency for Send), shown on white at 260 dp with a quiet border;
  `LookQr.decode(pixels, w, h)` reads a QR out of a picture's pixels and accepts it only if it is a look code. Reading is from a picture the owner
  picks (`GetContent`, decoded on the phone like the SSH key scan); there is no live camera scan yet. Only the look is in the code.

Checked: `LookSlotsTest` (storage, damage, replace/full/delete, names, rule wearing a saved look, a look surviving being drawn and read back as a QR,
a non-look QR and a blank picture refused, QR size small enough to scan from a screen) and a render of the QR. The Themes page with saved looks was
also rendered on a CI runner. Not tried on a phone: scanning a real screenshot or photo.

### Back, second attempt (2.33.1)

The first fix (2.32.0) did not cure it on the owner's phone and the cause could not be reproduced. 2.33.1 adds a second route and a probe:
`HomeActivity` registers an `OnBackInvokedCallback` at `PRIORITY_OVERLAY` (API 33+) that counts the press (`LauncherBack.presses`) and calls
`onBackPressedDispatcher.onBackPressed()` itself, so the screen's handlers run even if the platform's registration of the activity's own callbacks
was the thing failing; below API 33, and for a hardware Back key, `onBackPressed()` is overridden to count and call `super`. The foot of every
launcher settings page shows "Back pressed N times since the launcher started": N rising with no page change means the launcher got Back and mishandled
it; N not rising means Android never delivered Back to the launcher. Remove the counter once the cause is known.

## Home styles (2.34.0)

Code: `launcher/HomeStyleMath.kt` (the `HomeStyle` enum and `StyleMath`, plain arithmetic), `launcher/HomeStyles.kt` (the drawing), the wiring in
`LauncherScreen.kt` (`styled`), the Home style row and `MiniStyle` previews in `LauncherSettingsSheet.kt`.

The look part `homeStyle` is separate from `favLayout`: the original (**Lists**) is a list of favourites and the liquid letter rail; the others
hold every app themselves, so the rail is not drawn and the room it took is used.

| Style | What it is | Notes |
| --- | --- | --- |
| Pages | grid of every app, `HorizontalPager`, page dots, a dock of favourites | cells fit the area (`StyleMath.gridFit`: 3 to 6 columns, at least 2 rows) |
| Hive | hexagons packed like a honeycomb, scrolling | rows of `hiveCols`; odd rows pushed half a cell; each row's box is 3/4 of a hexagon so the next tucks in |
| Dial | an arc from 180 to 270 degrees round the bottom-right corner, drag to spin | centre half an icon in from the corner so the ends are whole; `dialSelected` is the middle app, a tick when it changes |
| Metro (`HomeStyle.Tiles`) | favourites as coloured tiles: one large, then small, small, wide, ...; All apps at the end | `StyleMath.tilePlan`; text colour chosen for contrast on the tile |
| Prompt | a command line: favourites numbered, typing filters (`LauncherMath.search`), Enter opens the top match, a bare number opens that line | output sits at the bottom with the input, nearest the thumb; `imePadding` |

Press and hold on any cell gives the menu (favourite, app info); carrying an app to another device is only in the Lists style. Five new
looks (Pages, Hive, Dial, Metro, Prompt) use the styles; the older presets set `homeStyle` to Lists, so choosing one puts the list back.
The style is the tenth part of a look code (`...|true|Hive`); a nine-part code still reads and means Lists, an unknown style keeps what was there.

Checked: `HomeStyleMathTest` (pages hold every app once, grid fit limits, hive rows, dial angles/clamp/selection/turning across the 0/360 seam,
tile rhythm, prompt list/pick/Enter, style stored and shared), and renders of all five styles and of every look card, which found the dial's end
icons cut by the screen edge. Not tried on a phone.

### Web search from the launcher's search

`LauncherRepo.webSearch(query, engine)`: for Google, the phone's own `ACTION_WEB_SEARCH` first, then the results page in the browser
(`SearchEngine.url`, the query URL-escaped); other engines (DuckDuckGo, Bing, Brave) open their results page; with no browser a toast says so.
Enter in the search box opens the best app match, or searches the web when there is none and web search is on (the button then reads Search).
The engine is the setting `searchEngine` (Settings, Search). Before, the row's failure was silent and Enter on a miss did nothing.
