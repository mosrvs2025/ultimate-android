# Daily activity (You → Activity, and the Home card)

Your day of movement, from Health Connect, drawn like Samsung Health's Daily activity page and then taken further.
Code: `health/ActivityMath.kt` (all the arithmetic, pure and tested), `health/ActivityData.kt` (Health Connect reads and the small
on-phone cache), `health/ActivityScreen.kt` (the page, the rings, the Home card).

## Where the numbers come from

Samsung Health writes what the watch and phone record into Health Connect; Ultimate only reads it, on the phone, and only the
kinds you allow (Activity, Calories, Workouts). Nothing is sent anywhere.

| Shown | Read as | Notes |
| --- | --- | --- |
| Steps | Health Connect's own day total of `StepsRecord` | Health Connect merges overlapping sources (phone and watch), so steps are not counted twice. |
| Distance | `DistanceRecord` total | Shown in miles in the US, UK, Liberia and Myanmar, otherwise km. |
| Active calories | `ActiveCaloriesBurnedRecord` total | |
| Total burned | `TotalCaloriesBurnedRecord` total | New permission (`READ_TOTAL_CALORIES_BURNED`), asked for separately, so existing installs keep working without it. |
| Floors | `FloorsClimbedRecord` total | |
| Active time | **worked out here** | Health Connect has no "active time" record. A minute counts when it has 40 or more steps, or lies inside a workout session; each minute is counted once. It will not match Samsung's own number exactly (their rule is not published); the page says so. The threshold is one constant, `ACTIVE_STEPS_PER_MIN`. |

Steps by the minute come from Health Connect's grouped totals (`aggregateGroupByDuration`, one-minute slices, read six hours at a
time), also merged across sources.

## What it adds beyond the totals

- **Rings with laps.** Steps, active time and active calories against your own goals (Samsung's usual 10,000 / 90 / 500 to begin
  with, changeable under Goals). Past a goal the ring laps in a lighter shade, up to three laps.
- **Your usual day.** The middle value of each hour over your last eight weeks (the same weekday when there are at least three of
  them, otherwise recent days; at least five days are needed). Drawn as a dashed line behind today's hourly bars.
- **Ahead or behind, and where it ends.** Steps so far against where you'd usually be by this time; where you'd finish if the rest of
  the day goes as usual; the time you'd probably reach the goal. Said only from 8 AM and only when there is enough history.
- **Your day in walks.** Stretches of walking found in the minute data (a pause of up to three minutes doesn't end one; at least eight
  minutes and 600 steps), named by time of day and pace ("Evening walk", "Midday run"), or by the workout that covers them
  ("Hiking"). Distance per walk uses today's own stride (distance divided by steps) and is left out if that isn't believable.
- **The week.** Seven days against the step goal, the run of days in a row (counted back from yesterday if today isn't done), best day.
- **How fresh it is.** The time of the newest steps and where they came from. Samsung Health hands data to Health Connect in
  batches, so the page says when the numbers are more than two hours old and what to do about it.

## Kept on the phone

`ActivityStore` (SharedPreferences `activity`): your three goals, and for each *finished* day its totals, steps by the hour and
active minutes (up to 70 days). Today and yesterday are never kept, because the watch may still be catching up on them. The first
open reads the eight weeks once; after that only today and yesterday.

## Not yet verified

Everything here is checked with made-up days in unit tests and drawn from made-up numbers for the renderings. **Not yet tried
against a real Health Connect**: that Samsung Health actually shares total calories and workouts, how long the first open takes,
how close active time lands to Samsung's, and whether the grouped totals behave the same on every phone.
