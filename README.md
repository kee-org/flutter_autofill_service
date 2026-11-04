# flutter_autofill_service

Integrate Flutter with Android platform autofill services.

## Features

* Autofill results are never revealed automatically: the initial response is an "unlock" suggestion that sends the user to your app, so nothing is filled without their request and your code's authorisation.
* Two ways to let the user choose an entry (see [Selecting an entry](#selecting-an-entry-in-your-app-optional)):
  * Legacy flow (default): your app returns up to 10 matching entries to Android's autofill UI, and the user picks one there. A "Choose a different" entry option lets the user return to your app to select an entry that was not previously matched to the app or website in question.
  * In-app selection flow (opt-in): the user picks once in your own UI and that entry is filled immediately, with no further taps.
* Trust signals ([`appId`, `appIdSource`, `appIdTrusted`, `isWebOrigin`, `webDomains[].dalVerified`, `webDomains[].userSelected`, `webDomains[].focused`, `appIsBrowser`](#deciding-whether-a-request-is-genuine-autofillmetadata)) to help you detect requests that try to impersonate another app or website.
* Saving newly supplied data is supported.
* IME (keyboard) integration option (Android 12+)
* Fill dialog support (Android 13+, opt-out, experimental)
* Detailed logging via tinylog or other slf4j compliant logger 
* Example app demonstrates all the major features.

## Usage

See the example app to understand the API and in particular the `AndroidManifest.xml` file in that project. This file is where you can configure the string and drawable overrides to customise the integration to your project.

The activity named by the `com.keevault.flutter_autofill_service.ACTIVITY_NAME` meta-data is launched by this plugin's own service, so it does not need to be exported. Declare it with `android:exported="false"` (as the example app does) so that other installed apps cannot start it with forged requests for another app or website.

The autofill service never offers to fill or save for a few system apps, your own app's package (so your master password screen is not offered for autofill or saving) or any packages you list, comma separated, in this optional application meta-data:

```xml
<meta-data android:name="com.keevault.flutter_autofill_service.EXCLUDED_PACKAGES"
    android:value="com.example.applock,com.example.vpn" />
```

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

The "up to 10 results" limit and the "Use a different entry" item only apply to the legacy flow. In the in-app selection flow your own UI decides which entries to show and how many.

`PwDataset` and `resultWithDataset` accept an optional `id`, which is used as the Android dataset ID. Use it to give each entry a stable, non-secret identifier. If you omit it, a positional ID is used instead.

The example app's `AutofillSelectionScreen` shows all of these.

### Fill dialog (Android 13+)

On Android 13 (API 33) and later, apps that support it can ask for autofill suggestions in a dialog that appears when a field is first focused. This is enabled by default and can be turned off with `AutofillPreferences.enableFillDialog`. It is experimental because behaviour varies between apps and Android versions, and some apps show the dialog as soon as they load rather than when a field is tapped. It applies to both the legacy and in-app selection flows.

### Deciding whether a request is genuine (`AutofillMetadata`)

Any installed app can claim to be another app or to display another website, so do not fill credentials based on `webDomains` or `appId` alone. `AutofillMetadata` provides these signals:

| Property | Meaning |
|---|---|
| `appId` | Package ID of the app being filled, or `null` if it could not be identified. Taken from the activity component, then view node package IDs, then (least reliable) the window title. Only the activity component, which Android supplies, is reliable. Replaces `packageNames` from earlier versions. |
| `appIdSource` | Where `appId` came from: `component` (the activity component supplied by Android), `viewNode` (a view node package ID), `windowTitle`, or `none` when `appId` is `null`. The app being filled chooses `viewNode` and `windowTitle` values. |
| `appIdTrusted` | Convenience getter: `true` only if `appIdSource` is `component`. |
| `webDomains` | Every web domain the request claims to be for. Each `AutofillWebDomain` has its own `dalVerified`, `userSelected` and `focused` flags (below). |
| `webDomains[].dalVerified` | `true` only if the app is a known browser or Android reports it as verified to handle links for this domain via Digital Asset Links (Android 12+). This is App Links verification (`delegate_permission/common.handle_all_urls`), not the `get_login_creds` relation; the host must be one the app declares, exactly or through a wildcard (`*.example.com` covers subdomains of `example.com` but not `example.com` itself). If the app is not visible to your app under Android's package visibility rules the check fails and the domain is unverified. Each domain is checked on its own, so one verified domain says nothing about the others in the same request. The app is identified only from the activity component supplied by Android, never from package names found inside the app's own view hierarchy, and nothing is verified if Android does not supply one. On Android 11 and below it cannot be checked, so `webDomains` is emptied for non-browser apps. |
| `webDomains[].userSelected` | `true` when the app declares the domain but Android has not verified it, and the user enabled "Open supported links" for the app. That is a link-handling choice, not proof of ownership (a malicious app can ask the user to enable it), so it never implies `dalVerified` and you should treat the domain as unverified. |
| `webDomains[].focused` | `true` for the one domain the request is about: the domain of the focused field (or, if no focused field is recognised, the only claimed domain, or that of the password field). At most one domain is `focused`; none is if the primary domain cannot be determined. `AutofillMetadata.focusedWebDomain` returns it. |
| `focusedWebDomain` | Convenience getter: the webdomain from `webDomains[]` which has `focused` == `true``. |
| `isWebOrigin` | `true` if the request claims a web origin, i.e. at least one web domain was found when the request was parsed. (On Android 11 and below `webDomains` is then emptied for non-browser apps.) |
| `appIsBrowser` | `true` if `appId` is a web browser (see below). |

If `isWebOrigin` is `true`, use `focusedWebDomain` as the domain to offer credentials for. If it is `null`, or its `dalVerified` is `false`, an app is presenting a website it has not been shown to own: block the request or ask the user before filling credentials for that domain. Only match entries against domains whose own `dalVerified` is `true`, and never against another domain in `webDomains` on the strength of the focused one. Consider also treating `appId` as an identity only when `appIdTrusted` is `true`.

#### Which fields are filled

Credentials are only written into fields that belong to the focused domain (or, when the request has no web origin, to no domain), so a page or app cannot receive credentials meant for another origin by including extra fields, iframes or WebViews. Fields that are not visible, or are 1 pixel or less in width or height, are never filled.

#### Detecting browsers (`appIsBrowser`)

`AutofillMetadata.appIsBrowser` is `true` when `appId` is a web browser. A browser's package id is not an app identity, so do not match or learn entries by `appId` in that case. It does not change any `dalVerified` value. Detection uses a built-in browser allowlist plus a query for apps that handle generic `https` links. The latter needs the `<queries>` entry the plugin adds to your merged manifest (Android 11+, not `QUERY_ALL_PACKAGES`). To opt out, remove it with `tools:node="remove"` in your app manifest; only the allowlist is then used.

### Logging

This plugin logs via tinylog/slf4j and includes a custom tinylog `LoggingProvider` (`DynamicLevelLoggingProvider`) which lets the debug log level be toggled at runtime from your Flutter code (via the `enableDebug` preference) without restarting the app. To wire this up, copy the example app's resource files into your own app:

* `android/app/src/main/resources/tinylog.properties`
* `android/app/src/main/resources/META-INF/services/org.tinylog.provider.LoggingProvider`

These are plain JVM classpath resources, not Android resources, so Gradle does not merge them in from this plugin automatically - each consuming app must provide its own copies (and can customise `tinylog.properties`, e.g. writer/output configuration, to suit). Logging is off until the stored `enableDebug` preference has been applied, which happens when the plugin attaches to a Flutter engine and each time the autofill service starts a request. If you skip this step, tinylog falls back to its own default provider: dynamic level switching won't work, but the plugin will not crash because of it.

If your app runs Android instrumentation tests (e.g. via Patrol) and those tests exercise code that logs through SLF4J outside of your own app code (such as a test-only embedded server), you generally don't need this plugin's dynamic logging in that process. The simplest option is to disable tinylog for the `androidTest` variant entirely by adding an `android/app/src/androidTest/resources/tinylog.properties` containing just `level = off` - androidTest resources take precedence over `main` for the test APK, so this overrides the properties above without affecting your real app builds.

## Planned

* Respond to any inaccuracies in matching code algorithms for existing or new Android versions. We don't know if the current behaviour is perfect but suspect there is room for some improved heuristics.
* Help library consumers determine if a save request from the user has already been handled or not (currently consumers need to track this themselves and some edge cases involving intentional repeat actions from users may cause a little confusion).

Only Android is supported but maybe desktop or web support can be included one day, open an issue or PR if you have any ideas on what form that support might take. iOS (at least as of v17) does not support autofilling via a Dart/Flutter plugin (it must be a native Extension instead) so there is no support planned for that platform.

## Contributing

Please open issues or discussions or PRs as you see fit. We're not expecting a deluge of activity so want to keep everything as informal as possible at least to start with. However, please do take note the CODE_OF_CONDUCT.md - an informal approach does not mean we will tolerate prejudice or abuse.

This project was inspired by and incorporates some code from the autofill_service package by hpoul.
