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
