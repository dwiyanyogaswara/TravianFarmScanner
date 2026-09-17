# TravcoOasisFarmlist

Android Kotlin app for:
1. Manual Travian login in an embedded WebView.
2. Travian logout + browser cookie cleanup.
3. Scan inactive villages from TravcoTools and save them in SQLite.
4. Scan Travian map oasis data using Travian's `/api/v1/map/position` endpoint and save it in SQLite.
5. Add coordinates from Travco DB to an existing Travian Farm List.
6. Add unoccupied oasis coordinates from Oasis DB to an existing Travian Farm List.

## Important
- The app expects an existing Farm List. It does not create a new Farm List automatically.
- Travian Farm Lists are a Gold Club feature according to Travian's official support.
- Oasis scan defaults to a rectangular radius around X/Y. It filters results to the requested bounds.
- Login is intentionally manual: enter the server, press LOGIN, then complete the Travian login in the WebView.
- The app uses the logged-in WebView session for Travian requests.
- Before using mass farming, verify troop counts and target selection in-game.

## Source basis
The implementation was designed after inspecting the supplied `TravianBot-Ultra-main.zip`, especially its Travco inactive-search parser, Travian map oasis scanner, and official Farm List/Add-target selectors.

## Build
Open this folder in Android Studio and run:
`./gradlew assembleDebug`

Recommended environment: JDK 17, Android SDK 35.
