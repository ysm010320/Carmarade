# Carmarade implementation ledger

User approved recommended native app with staged implementation on 2026-11-07.
User corrected English brand to CAR + MARADE; UI and title now use Carmarade.

Ruling: New independent directory in existing workspace; no unrelated BPT files edited and no BPT worktree needed.
Ruling: Initial app is a clearly labeled local simulator; production service and Pleos runtime validation remain separate milestones.
Ruling: Execute inline without repeated design approval, per user's explicit delegation.
Pre-flight: Domain owns all transitions; UI and persistence consume it. Gateway never silently falls back to claiming real connectivity.

## Verification
- 14 pure Kotlin domain tests pass, including route reacceptance, host authorization, location expiry, ending/relaunch privacy and persistence failure.
- Android resources, Kotlin classes, D8 and APK signature verification pass using the documented manual build.
- Independent review found privacy updates blocked by save failure and misleading success toasts. Both fixed; tests observed RED then GREEN. Form drafts also survive orientation redraw.
- Browser verified create, validation, route changes/limits/acceptance, start, messages, sharing, reload privacy reset, finish, review persistence.
- Final brand revision: Camarade → Carmarade in user-visible English text. Technical package and existing storage keys stay stable.
- Gradle wrapper added with official distribution SHA256. Android manifest namespace moved to Gradle-compatible setup; standalone aapt2 uses a generated copy.
- No emulator or physical Android/Pleos runtime available. Full Gradle, lint and speech runtime remain unverified.

## Next milestone
Validate the APK on a Pleos emulator with the user's SDK account; replace DemoVehicleGateway with NaviHelper/FusedLocation and real vehicle restrictions, then add authenticated group messaging and location transport.
