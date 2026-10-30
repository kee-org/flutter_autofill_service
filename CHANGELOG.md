## 0.23.0

* Fix example app configuration to prohibit malicious installed apps from forging requests for other apps/sites - https://github.com/kee-org/flutter_autofill_service/security/advisories/GHSA-7g4g-ggjf-xjvr
* Prevent incidental logging of typed secrets - https://github.com/kee-org/flutter_autofill_service/security/advisories/GHSA-3gjj-334c-pr82
* Add optional ID field to PwDataset - https://github.com/kee-org/flutter_autofill_service/security/advisories/GHSA-v8w7-2wj8-3m27
* New trust signals to help you decide whether a request is genuine or should be blocked and/or referred to the end user for a decision - https://github.com/kee-org/flutter_autofill_service/security/advisories/GHSA-ghq9-vv2r-5pwq
   * **Breaking:** `AutofillMetadata.packageNames` (a set) is replaced by `AutofillMetadata.appId` (a single nullable string, null when no app could be identified). The ID is taken from the most reliable source available: the activity component package, then the package ID of the view nodes, then (least reliable) the window title.
   * Many additional trust signals are now available too - see the updated Readme for the full list.
   * To keep users of your apps secure against spoofing attacks it is important to understand these signals.
* Logging now defaults to off until the stored `enableDebug` preference is applied, which the plugin now does when it attaches to a Flutter engine.
* Excluded packages (system apps, your own app and any listed in the new optional `com.keevault.flutter_autofill_service.EXCLUDED_PACKAGES` meta-data) are now checked before anything else, so they no longer trigger save prompts. Your own package is excluded automatically; the hard-coded `com.keevault.keevault` entry is gone.
* Credentials are now only filled into visible fields that belong to the focused web domain (or, for requests without a web origin, to no web domain). Fields that are hidden or 1 pixel or less in width or height are skipped.
* New opt-in in-app selection flow: with `AutofillPreferences.useInAppSelection` enabled, the unlock suggestion opens your app so the user can pick an entry once, and it is then filled directly with no further taps. Reply with `resultWithDataset`. Default is `false`, which keeps the existing behaviour.
* New `AutofillPreferences.useEphemeralDataset` preference (default `true`; Android 12+ with `useInAppSelection` only): `true` fills once and keeps offering the unlock suggestion, `false` lets Android remember the chosen entry for the session.
* Use distinct request codes for the unlock and "Use a different entry" PendingIntents
* Fix username and password field IDs being swapped when building save info in the replacement fill response
* (legacy fill pathway): Changed return API for interactively selected items since they no longer autofill a dataset and sometimes fully ignore it unless wrapped in a fillresponse.

## 0.22.0

* Improved documentation and resilience of logging configuration
* Update to Gradle 9

## 0.21.0

* Improved support for autofilling into recent releases of the Google Chrome browser and apps that embed it
* Update to Kotlin 2.2, Gradle 8.13 and Android API 36
* Use additional data sources for determining the app package ID for filling - this may enable AutoFill in some apps that have previously not worked
* Improved handling of multi-screen sign-in form detection
* Improved detection of some username and password fields
* Improved handling of manual (forced) requests

## 0.20.0

* Support Kotlin v2

## Intermediate versions

* Avoid including text in pinned IME responses
* Use explicit intents where required
* Change number of results returned when IME demands
* Enable different icon for pinned dataset
* Disable longpress authentication launch intent
* Enable no tinting of IME icons
* Log 0 passwords rather than respond with fake dataset
* DynamicLevelLoggingProvider for tinylog included so library consumers can adjust log level at runtime
* Match additional username and password fields
* Partial disabled implementation of Dialog Fill feature
* Clear last intent when library consumer tells us save has been handled
* Gracefully handle faulty AutofillManager on device
* Match additional fields and treat signup fields differently
* Forward compatilibilty mode flag to save metadata
* Protect against non-text autofill values

## 0.14.0

* Fix crashes on non-Android platforms by updating to new Dart plugin declaration format
* Remove mentions of iOS

## 0.0.1

* TODO: Describe initial release.
