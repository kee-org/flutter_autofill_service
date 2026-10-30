## 0.23.0

* New opt-in "select-in-app" autofill flow: the unlock suggestion authenticates a single dataset, so after the user picks an entry once in the app's own UI it is filled directly with no further taps. Enable with the new `AutofillPreferences.useInAppSelection` preference (default `false`, which keeps the existing behaviour). Reply with `resultWithDataset`.
* New `AutofillPreferences.useEphemeralDataset` preference (default `true`), only used with `useInAppSelection` on Android 12+: `true` fills once and keeps the unlock suggestion available; `false` lets Android keep the chosen entry for the session.
* If the select-in-app flow cannot build its unlock suggestion (no field ids), the request now falls back completely to the existing flow instead of leaving the new-flow flag set, which made the reply to response-level authentication unusable
* Use distinct request codes for the unlock and "Use a different entry" PendingIntents
* Fix username and password field IDs being swapped when building save info in the replacement fill response

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
