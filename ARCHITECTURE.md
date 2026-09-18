# Domino Counter — Architecture Plan (v2 — rules resolved)

Single Activity · Navigation Component · XML + ViewBinding · ViewModel + StateFlow · Room

Changes from v1: `Team(A|B)` replaced by `side: Int` + `MatchFormat` (3-player free-for-all);
frozen finals moved to `match_sides`; completed matches are immutable; no back-press guard.

---

## 1. Governing principle

**Rounds are the source of truth. Scores are derived.**

No mutable running total is stored during play. Each side's score is computed by folding the
round list, which makes *"tap any previous entry to edit or delete, automatically
recalculating"* correct by construction rather than by patching a counter. It also makes the
win condition a pure function of the list, unit-testable with no Android dependencies.

The only denormalisation happens at completion, when final scores and the winner are frozen
so the stats screen never re-folds history.

---

## 2. The rulebook, as encoded

| # | Rule | Where it lives |
|---|---|---|
| R1 | **Foul = +50 to the side whose button was tapped.** `RoundEntity.side` is always the *receiving* side, for every source. | `MatchEngine`, trivially — a foul is just a round with `source = FOUL` |
| R2 | **Formats:** 2 players → 1v1 (2 sides) · 4 players → 2v2 (2 sides) · 3 players → free-for-all, 3 sides of 1 player, draw instead of skip | `MatchFormat` on `MatchEntity` |
| R3 | **First side to reach ≥ `targetScore` wins immediately.** No margin, no exact-hit rule. Only one entry is added at a time, so simultaneous crossing is impossible. | `MatchEngine.outcome()` |
| R4 | **Scan credits the side whose [Add Round Score] was tapped**, carried as `sideIndex` through the sheet into the scanner. | nav arguments |
| R5 | **A completed match is immutable.** Edit/delete exist only while `IN_PROGRESS`. Editing an earlier round *while in progress* may complete the match — same path, no special case. | repository guard + UI |
| R6 | **Leaving the scoreboard keeps the match `IN_PROGRESS`** and it is resumable. Abandoning is an explicit toolbar action. | nav + `MatchStatus` |

R5 removes a whole class of state: there is no "reopen the match", no un-firing the victory
dialog, no outcome transitioning back to null.

**Open assumption:** multiple `IN_PROGRESS` matches are permitted; the menu's Resume card
shows the most recent. New Game never abandons an existing match.

---

## 3. Room schema

Version 1, `exportSchema = true`, schema JSON committed from day one so migrations are
diffable later. KSP, not kapt.

### 3.1 Player

```kotlin
@Entity(
    tableName = "players",
    indices = [Index(value = ["normalizedName"], unique = true)]
)
data class PlayerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,        // exactly as typed: "Wael"
    val normalizedName: String,     // dedupe key: lowercased, trimmed, accent-folded
    val colorSeed: Int,             // stable avatar colour
    val createdAt: Long,
    val lastPlayedAt: Long?,        // lets the setup dropdown sort by recency, not alphabet
    val isArchived: Boolean = false // never hard-delete a player with match history
)
```

`normalizedName` matters more than it looks: it's what stops "Wael", "wael " and "WAEL"
becoming three players with split stats. Folding is `Locale.ROOT` lowercase + NFD normalise
+ strip combining marks, so French accents and Arabic-script input behave — not
`toLowerCase()`.

`isArchived` rather than delete: the `RESTRICT` foreign key from participants would reject
the delete anyway, and silently losing history is worse than a hidden row.

### 3.2 Match

```kotlin
@Entity(tableName = "matches")
data class MatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val finishedAt: Long?,
    val status: MatchStatus,        // IN_PROGRESS | COMPLETED | ABANDONED
    val format: MatchFormat,        // ONE_V_ONE | TWO_V_TWO | FREE_FOR_ALL
    val sideCount: Int,             // 2 or 3 — denormalised from format for cheap queries
    val targetScore: Int,           // snapshot of the setting AT CREATION
    val winningSide: Int?           // null until COMPLETED
)
```

`targetScore` is per match, never read from Settings at display time: change the target to
150 next month and last month's matches must still read as the games they were.

`format` is stored rather than derived from player count because **stats must segment by
it** — a free-for-all win-rate baseline is 1 in 3, a 2v2 baseline is 1 in 2, and averaging
them together produces a meaningless number.

### 3.3 MatchSide — frozen finals

```kotlin
@Entity(
    tableName = "match_sides",
    primaryKeys = ["matchId", "side"],
    foreignKeys = [ForeignKey(MatchEntity::class, ["id"], ["matchId"], onDelete = CASCADE)]
)
data class MatchSideEntity(
    val matchId: Long,
    val side: Int,              // 0-based
    val finalScore: Int
)
```

Two fixed score columns on the match row can't hold three sides, so the frozen totals live
here — written once, inside the completion transaction. This is also what makes
"biggest blowout", "average winning score" and "points scored per match" single-scan
queries instead of round folds.

### 3.4 MatchParticipant — the junction table that makes the stats possible

```kotlin
@Entity(
    tableName = "match_participants",
    primaryKeys = ["matchId", "playerId"],
    foreignKeys = [
        ForeignKey(MatchEntity::class,  ["id"], ["matchId"],  onDelete = CASCADE),
        ForeignKey(PlayerEntity::class, ["id"], ["playerId"], onDelete = RESTRICT)
    ],
    indices = [Index("playerId"), Index(value = ["matchId", "side"])]
)
data class MatchParticipantEntity(
    val matchId: Long,
    val playerId: Long,
    val side: Int,         // 0-based; 2 sides for 1v1 and 2v2, 3 for free-for-all
    val seatIndex: Int     // display order within the side
)
```

**The single most important schema decision.** The shortcut — `teamAPlayer1Id … teamBPlayer2Id`
on the match row — is fatal to the stated goal: the Wael/Bilal/Moh question becomes a
four-way `OR` across eight columns, it cannot express a 3-player free-for-all at all, and
every rule change is a migration.

As a junction table, teammate synergy is a self-join on `matchId` where the side matches and
the player differs. It handles 1, 2 or N players per side with no branching, **and it
handles free-for-all for free**: each FFA side holds exactly one player, so the self-join
returns no rows and synergy is correctly empty without a single special case.

### 3.5 Round — the audit log

```kotlin
@Entity(
    tableName = "rounds",
    foreignKeys = [ForeignKey(MatchEntity::class, ["id"], ["matchId"], onDelete = CASCADE)],
    indices = [Index(value = ["matchId", "sequence"])]
)
data class RoundEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val matchId: Long,
    val sequence: Int,           // display order; survives edits, gaps are fine
    val side: Int,               // who RECEIVED these points (R1: always the tapped side)
    val points: Int,
    val source: ScoreSource,     // MANUAL | SCAN | FOUL
    val createdAt: Long,
    val editedAt: Long?,         // non-null ⇒ show an "edited" marker in the log
    // --- scan audit, null for manual/foul ---
    val detectedTileCount: Int?,
    val rawPipTotal: Int?        // what the CV said BEFORE any user correction
)
```

`rawPipTotal` costs one nullable column and buys a real accuracy metric: after a few hundred
scans you can query how often the user corrected the scanner and by how much, which is how
`DetectionConfig` gets tuned against reality instead of a hunch.

### 3.6 Enums

`ScoreSource`, `MatchStatus`, `MatchFormat` stored as **strings** via `@TypeConverter`, never
ordinals — ordinals break the day someone reorders the enum, strings stay readable in a DB
inspector. `side` is a plain `Int`, deliberately: an `A|B|C` enum would invite the same
redesign again at 4-player free-for-all.

### 3.7 The queries that justify the schema

Record, segmented by format (R2):

```sql
SELECT m.format,
       COUNT(*)                                                 AS played,
       SUM(CASE WHEN m.winningSide = mp.side THEN 1 ELSE 0 END) AS won
FROM match_participants mp
JOIN matches m ON m.id = mp.matchId
WHERE mp.playerId = :playerId AND m.status = 'COMPLETED'
GROUP BY m.format
```

Teammate synergy — *"how often does Wael win partnered with Bilal vs with Moh"*:

```sql
SELECT mate.id                                                  AS playerId,
       mate.displayName                                         AS displayName,
       COUNT(*)                                                 AS playedTogether,
       SUM(CASE WHEN m.winningSide = me.side THEN 1 ELSE 0 END) AS wonTogether
FROM match_participants me
JOIN match_participants partner
      ON  partner.matchId   = me.matchId
      AND partner.side      = me.side
      AND partner.playerId != me.playerId
JOIN players mate ON mate.id = partner.playerId
JOIN matches m    ON m.id    = me.matchId
WHERE me.playerId = :playerId AND m.status = 'COMPLETED'
GROUP BY mate.id
ORDER BY wonTogether * 1.0 / COUNT(*) DESC
```

Head-to-head is the **same query with `partner.side != me.side`**. That one-operator
difference is my test for whether a schema is right.

### 3.8 Relation POJOs

```kotlin
data class MatchDetail(
    @Embedded val match: MatchEntity,
    @Relation(parentColumn = "id", entityColumn = "matchId")
    val participants: List<MatchParticipantEntity>,
    @Relation(parentColumn = "id", entityColumn = "matchId")
    val rounds: List<RoundEntity>
)
```

`ScoreboardFragment` observes exactly one `Flow<MatchDetail>`. Any write re-emits and the
totals recompute. There is no second code path named "recalculate".

---

## 4. Navigation graph

Two graphs: a root graph, and a **nested `match_graph`** holding everything belonging to one
live match.

### Why nested

Scoreboard, add-score sheet, scanner and victory dialog all mutate the same match. A
graph-scoped `MatchViewModel` (`by navGraphViewModels(R.id.match_graph)`) gives all four one
instance, created on entry and destroyed when the graph is popped. Better than
`savedStateHandle` result-passing — the scanner doesn't return a value, it commits a round —
and better than an Activity-scoped VM, because the state dies automatically and no stale
match leaks into the next game.

### `nav_graph.xml`

```xml
<navigation
    android:id="@+id/nav_graph"
    app:startDestination="@id/mainMenuFragment">

    <!-- ============ MAIN MENU ============ -->
    <fragment
        android:id="@+id/mainMenuFragment"
        android:name="...ui.menu.MainMenuFragment"
        tools:layout="@layout/fragment_main_menu">

        <action android:id="@+id/toGameSetup"   app:destination="@id/gameSetupFragment" />
        <action android:id="@+id/toMatch"       app:destination="@id/match_graph" />  <!-- Resume (R6) -->
        <action android:id="@+id/toPlayerStats" app:destination="@id/playerStatsFragment" />
        <action android:id="@+id/toSettings"    app:destination="@id/settingsFragment" />
    </fragment>

    <!-- ============ SETUP ============ -->
    <fragment
        android:id="@+id/gameSetupFragment"
        android:name="...ui.setup.GameSetupFragment"
        tools:layout="@layout/fragment_game_setup">

        <!-- The Match + MatchParticipant rows are INSERTed here, so the scoreboard
             receives a real id. Consequence: the match survives process death (R6). -->
        <action
            android:id="@+id/toMatch"
            app:destination="@id/match_graph"
            app:popUpTo="@id/gameSetupFragment"
            app:popUpToInclusive="true" />
    </fragment>

    <!-- ============ THE MATCH FLOW ============ -->
    <navigation
        android:id="@+id/match_graph"
        app:startDestination="@id/scoreboardFragment">

        <argument android:name="matchId" app:argType="long" />

        <fragment
            android:id="@+id/scoreboardFragment"
            android:name="...ui.match.ScoreboardFragment"
            tools:layout="@layout/fragment_scoreboard">

            <argument android:name="matchId" app:argType="long" />

            <action android:id="@+id/toAddRound"  app:destination="@id/addRoundBottomSheet" />
            <action android:id="@+id/toEditRound" app:destination="@id/editRoundBottomSheet" />
            <action android:id="@+id/toScanner"   app:destination="@id/cameraScannerFragment" />
            <action android:id="@+id/toVictory"   app:destination="@id/victoryDialogFragment" />
        </fragment>

        <dialog
            android:id="@+id/addRoundBottomSheet"
            android:name="...ui.match.AddRoundBottomSheet">
            <argument android:name="sideIndex" app:argType="integer" />
            <action
                android:id="@+id/toScanner"
                app:destination="@id/cameraScannerFragment"
                app:popUpTo="@id/addRoundBottomSheet"
                app:popUpToInclusive="true" />
        </dialog>

        <!-- Audit-log row tap. Only reachable while IN_PROGRESS (R5). -->
        <dialog
            android:id="@+id/editRoundBottomSheet"
            android:name="...ui.match.EditRoundBottomSheet">
            <argument android:name="roundId" app:argType="long" />
        </dialog>

        <fragment
            android:id="@+id/cameraScannerFragment"
            android:name="...ui.scanner.CameraScannerFragment"
            tools:layout="@layout/fragment_camera_scanner">
            <argument android:name="sideIndex" app:argType="integer" />
        </fragment>

        <dialog
            android:id="@+id/victoryDialogFragment"
            android:name="...ui.match.VictoryDialogFragment">
            <!-- No args: reads the finished state from the graph-scoped MatchViewModel -->
            <action
                android:id="@+id/toMainMenu"
                app:destination="@id/mainMenuFragment"
                app:popUpTo="@id/match_graph"
                app:popUpToInclusive="true" />
        </dialog>
    </navigation>

    <!-- ============ LEAVES ============ -->
    <fragment android:id="@+id/playerStatsFragment" android:name="...ui.stats.PlayerStatsFragment" />
    <fragment android:id="@+id/settingsFragment"    android:name="...ui.settings.SettingsFragment" />
</navigation>
```

### Back-stack rules

| From → To | Behaviour |
|---|---|
| Setup → Match | pops Setup, so Back from the scoreboard reaches the Main Menu, never a half-filled form |
| Victory → Main Menu | pops the whole `match_graph`, destroying `MatchViewModel` |
| Back on Scoreboard | **plain pop** (R6). Match stays `IN_PROGRESS` and the menu offers Resume |
| Abandon (toolbar overflow) | confirm → `status = ABANDONED` → pop to Main Menu |
| Back on Scanner | plain pop, nothing committed |
| Victory dialog | `isCancelable = false`; the match is already persisted, the button only navigates |

Safe Args enabled throughout.

### Scoreboard layout must be N-sided

With 2 or 3 sides, fixed left/right panels don't work. A single `view_side_panel.xml`
(name(s), big score, [+50 Foul], [Add Round Score]) is inflated `sideCount` times into a
container, each bound to its `sideIndex`. One layout serves every format, and 4-player FFA
later needs no layout work.

---

## 5. ViewModel & state design

Every screen: one immutable `…UiState` as `StateFlow`, plus a `Channel` for one-shot events
(navigation, snackbars), collected under `repeatOnLifecycle(STARTED)`. All flows
`stateIn(viewModelScope, WhileSubscribed(5_000), …)` so rotation doesn't re-query Room.

| ViewModel | Scope | Holds |
|---|---|---|
| `MainMenuViewModel` | Fragment | most recent `IN_PROGRESS` match → Resume card |
| `GameSetupViewModel` | Fragment | seats, player suggestions, format derivation, validation |
| `MatchViewModel` | **`match_graph`** | the live match; shared with sheets + scanner |
| `ScannerViewModel` | Fragment | live CV detections, torch state |
| `PlayerStatsViewModel` | Fragment | records, synergy, head-to-head |
| `SettingsViewModel` | Fragment | DataStore-backed prefs |

### MatchViewModel

```kotlin
data class MatchUiState(
    val sides: List<SidePanel>,    // 2 or 3; names, score, isLeading
    val rounds: List<RoundRow>,    // newest first, for the audit RecyclerView
    val targetScore: Int,
    val isEditable: Boolean,       // false once COMPLETED (R5)
    val outcome: Outcome?
)

fun addManual(side: Int, points: Int)
fun addFoul(side: Int)                        // R1: credits this side
fun commitScan(side: Int, result: ScanResult)
fun editRound(roundId: Long, newPoints: Int)  // rejected unless IN_PROGRESS
fun deleteRound(roundId: Long)                // rejected unless IN_PROGRESS
```

```kotlin
val uiState = matchRepo.observe(matchId)
    .map { detail -> MatchEngine.reduce(detail) }   // pure: no Android, no coroutines
    .stateIn(...)
```

`MatchEngine.reduce` folds rounds → per-side totals → outcome. Being pure, the whole
rulebook (R1–R5) is testable in JVM unit tests with zero mocks.

**Victory is derived, not imperative.** When `outcome` goes from null to non-null the
ViewModel runs one `@Transaction` — write `match_sides`, set `winningSide`, `finishedAt`,
`status = COMPLETED` — then emits a one-shot `ShowVictory`. Immutability (R5) is enforced at
the repository (`UPDATE … WHERE status = 'IN_PROGRESS'`), not merely by hiding buttons.

### ScannerViewModel + the CV refactor

The current analyzer emits a debug `Bitmap`. `OverlayView` needs geometry, not pixels:

```kotlin
data class DetectedTile(val corners: FloatArray, val pipCount: Int)   // analysis-image coords
data class ScanFrame(
    val tiles: List<DetectedTile>,
    val total: Int,
    val sourceSize: Size,
    val rotationDegrees: Int,
    val frameTimeMs: Long
)
```

`DominoAnalyzer` exposes `Flow<ScanFrame>` (a `MutableStateFlow` written on the analysis
thread, `conflate()`d out). `OverlayView` gets tiles plus a `Matrix` mapping analysis space →
view space, accounting for rotation, the analysis/preview aspect difference and
`PreviewView`'s `FILL_CENTER` crop. That matrix is the one genuinely fiddly part of screen 4
and gets its own step.

Torch: `cameraControl.enableTorch()`, availability from `cameraInfo.hasFlashUnit()`, actual
state observed from `cameraInfo.torchState` — never assumed from the button.

---

## 6. Settings

**DataStore (Preferences)**, not Room: key-value config with no queries, and keeping it out
of the DB avoids a migration per toggle.

Default target score · torch-on-by-default · haptics · and a **Scanner tuning** section
writing to `DetectionConfig` (`minSurroundContrast`, `brightAboveOtsu`, `cannyHighScale`).
Those are the knobs that proved to matter per-table; letting the user nudge them beats
shipping one guess for every kitchen.

---

## 7. Build order

1. **Data layer** — entities, converters, DAOs, repositories, `MatchEngine` + unit tests for
   R1–R5. Nothing visual; everything downstream depends on it being right.
2. **Nav skeleton + MainActivity + MainMenuFragment**, including the Resume card.
3. **GameSetupFragment** — player count → format, seat assignment, Room-backed autocomplete,
   match creation.
4. **ScoreboardFragment** — N side panels, foul button, audit log, add/edit/delete sheets.
   *The app is fully usable, manual-entry only, at the end of this step.*
5. **CameraScannerFragment + OverlayView** — CV port, geometry emission, coordinate matrix, torch.
6. **VictoryDialogFragment** + completion transaction.
7. **PlayerStatsFragment** — records by format, synergy, head-to-head.
8. **SettingsFragment**.

Step 4 is deliberately a shippable milestone: if the scanner slips, you still have a working
scorekeeper.
