# flutter_autofill_service

Integrate Flutter with Android platform autofill services.

## Features

* Initial autofill match results are blocked behind an authentication step (no automatic reveal of user data without their request and your code's authorisation).
* Then up to 10 match results can be returned to the relevant app (in the example app this is done manually but you'd probably want to automate it).
* A "Choose a different" entry result option allows the user to return to your app to select a different result that was not previously matched to the app or website in question.
* Saving newly supplied data is supported.
* IME integration option (Android 12+)
* Detailed logging via tinylog or other slf4j compliant logger 
* Example app demonstrates all the major features.

## Usage

See the example app to understand the API and in particular the `AndroidManifest.xml` file in that project. This file is where you can configure the string and drawable overrides to customise the integration to your project.

Apart from the obvious fake responses and buttons, there is one significant difference between the example app and a real world app:

If your meta-data references a drawable that you do not already reference from elsewhere in your app, AGP will by default exclude it from the release apk/aab build as part of its resource shrinkage process. This is because the resource is only loaded by string name at runtime. To fix this, create (or modify) `android/app/src/main/res/raw/keep.xml` with a `resources` element like this:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources xmlns:tools="http://schemas.android.com/tools"
    tools:keep="@drawable/ic_example_drawable_file_name_1_*,@drawable/ic_example_drawable_file_name_2_*"
 />

```

If you'd like to see this demonstrated in a real world example app, take a look at the Kee Vault app - https://github.com/kee-org/keevault2/

### Selecting an entry in your app (optional)

By default (legacy flow) your app answers an unlock with `resultWithDatasets(list)` and the user picks an entry from Android's autofill UI. Two preferences on `AutofillPreferences` enable an alternative flow where the user picks once, in your own UI:

| Preference | Default | Effect |
|---|---|---|
| `useInAppSelection` | `false` | `true` shows a single "unlock" suggestion that opens your app with `fillRequestedInteractive == true`, so you can show an entry picker. The chosen entry is filled immediately, with no further taps. |
| `useEphemeralDataset` | `true` | Only used when `useInAppSelection` is `true`, and only on Android 12 (API 31)+. `true` = one-shot fill: Android keeps offering the "unlock" suggestion so the user can pick a different entry. `false` = Android keeps the chosen entry for the rest of the autofill session and offers it again without unlocking. |

Your app chooses which method to call after the user picks an entry:

* `useInAppSelection == false`: legacy calls (`resultWithDatasets(list)`, or `resultWithDataset(...)` after "Use a different entry").
* `useInAppSelection == true`: `resultWithDataset(...)` with the chosen entry. (`resultWithDatasets` is accepted for compatibility and uses its first entry.)
* To decline, call `resultWithDatasets(null)`.

The example app's `AutofillSelectionScreen` shows all of these.

### Detecting browsers (`appIsBrowser`)

`AutofillMetadata.appIsBrowser` is `true` when `appId` is a web browser. A browser's package id is not an app identity, so do not match or learn entries by `appId` in that case. It does not change `dalVerified`. Detection uses a built-in browser allowlist plus a query for apps that handle generic `https` links. The latter needs the `<queries>` entry the plugin adds to your merged manifest (Android 11+, not `QUERY_ALL_PACKAGES`). To opt out, remove it with `tools:node="remove"` in your app manifest; only the allowlist is then used.

### Logging

This plugin logs via tinylog/slf4j and includes a custom tinylog `LoggingProvider` (`DynamicLevelLoggingProvider`) which lets the debug log level be toggled at runtime from your Flutter code (via the `enableDebug` preference) without restarting the app. To wire this up, copy the example app's resource files into your own app:

* `android/app/src/main/resources/tinylog.properties`
* `android/app/src/main/resources/META-INF/services/org.tinylog.provider.LoggingProvider`

These are plain JVM classpath resources, not Android resources, so Gradle does not merge them in from this plugin automatically - each consuming app must provide its own copies (and can customise `tinylog.properties`, e.g. writer/output configuration, to suit). If you skip this step, tinylog falls back to its own default provider: dynamic level switching won't work, but the plugin will not crash because of it.

If your app runs Android instrumentation tests (e.g. via Patrol) and those tests exercise code that logs through SLF4J outside of your own app code (such as a test-only embedded server), you generally don't need this plugin's dynamic logging in that process. The simplest option is to disable tinylog for the `androidTest` variant entirely by adding an `android/app/src/androidTest/resources/tinylog.properties` containing just `level = off` - androidTest resources take precedence over `main` for the test APK, so this overrides the properties above without affecting your real app builds.

## Planned

* Respond to any inaccuracies in matching code algorithms for existing or new Android versions. We don't know if the current behaviour is perfect but suspect there is room for some improved heuristics.
* Evaluate the experimental IME integration support for Android 12+ (determine if the Android bug which prevents the use of IME with an authentication step can be worked around or ignored).
* Help library consumers determine if a save request from the user has already been handled or not (currently consumers need to track this themselves and some edge cases involving intentional repeat actions from users may cause a little confusion).

Only Android is supported but maybe desktop or web support can be included one day, open an issue or PR if you have any ideas on what form that support might take. iOS (at least as of v17) does not support autofilling via a Dart/Flutter plugin (it must be a native Extension instead) so there is no support planned for that platform.

## Contributing

Please open issues or discussions or PRs as you see fit. We're not expecting a deluge of activity so want to keep everything as informal as possible at least to start with. However, please do take note the CODE_OF_CONDUCT.md - an informal approach does not mean we will tolerate prejudice or abuse.

This project was inspired by and incorporates some code from the autofill_service package by hpoul.
