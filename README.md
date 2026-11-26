# flutter_autofill_service

Integrate Flutter with Android platform autofill services.

## Features

* Autofill results are never revealed automatically: the initial response is an "unlock" suggestion that sends the user to your app, so nothing is filled without their request and your code's authorisation.
* Two ways to let the user choose an entry (see [Selecting an entry](#selecting-an-entry-in-your-app-optional)):
  * Legacy flow (default): your app returns up to 10 matching entries to Android's autofill UI, and the user picks one there. A "Choose a different" entry option lets the user return to your app to select an entry that was not previously matched to the app or website in question.
  * In-app selection flow (opt-in): the user picks once in your own UI and that entry is filled immediately, with no further taps.
* Trust signals ([`appId`, `appIdSource`, `appIdTrusted`, `isWebOrigin`, `webDomains[].dalVerified`, `webDomains[].userSelected`, `webDomains[].focused`, `appIsBrowser`, `appSigningCertSha256`, `appSigningCertKind`](#deciding-whether-a-request-is-genuine-autofillmetadata)) to help you detect requests that try to impersonate another app or website.
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

Excluded packages are matched against the activity component supplied by Android, and also against the view node ID packages and window titles supplied by the app being filled. The exception is the built-in `android` entry, which only matches the activity component: ordinary apps use framework view IDs (`android.R.id.*`), so those do not exclude a request.

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

The unlock suggestion is shown as a prominent filled button (your `unlock_drawable_name` icon, the `unlock_label` text and a trailing chevron).

#### Header text

`AutofillPreferences.dialogHeaderText` sets an optional subtitle above the button. It is `null` by default (no subtitle) and blank text is treated the same way. Supply localised text from your app, for example:

```dart
final prefs = await AutofillService().preferences;
await AutofillService().setPreferences(
  prefs.copyWith(dialogHeaderText: () => 'Tap the button below to sign in'),
);
// To remove it again:
await AutofillService().setPreferences(prefs.copyWith(dialogHeaderText: () => null));
```

`setPreferences` replaces the whole stored object, so always start from `await AutofillService().preferences` and use `copyWith`, which keeps the header unless you pass `dialogHeaderText`. The header is only shown with the fill dialog. In the legacy flow the "N matches..." header that `resultWithDatasets` supplies is separate and unaffected.

#### Restyling the button

Declare any of these colours in your app's `res/values` (for example `res/values/colors.xml`) to override the plugin's defaults; the example app overrides the button colour:

| Colour name | Default | Used for |
|---|---|---|
| `flutter_autofill_fill_dialog_button` | `#FF3F51B5` | Button background |
| `flutter_autofill_fill_dialog_button_text` | `#FFFFFFFF` | Button label, icon tint and chevron |
| `flutter_autofill_fill_dialog_header_text` | `#FF5F6368` | Header text |

The views are rendered by the system, not by your app, so they use fixed (not theme-dependent) colours and the same look in light and dark mode. Choose opaque colours with enough contrast.

### Deciding whether a request is genuine (`AutofillMetadata`)

Any installed app can claim to be another app or to display another website, so do not fill credentials based on `webDomains` or `appId` alone. `AutofillMetadata` provides these signals:

| Property | Meaning |
|---|---|
| `appId` | Package ID of the app being filled, or `null` if it could not be identified. Taken from the activity component, then view node package IDs, then (least reliable) the window title. Only the activity component, which Android supplies, is reliable. Replaces `packageNames` from earlier versions. |
| `appIdSource` | Where `appId` came from: `component` (the activity component supplied by Android), `viewNode` (a view node package ID), `windowTitle`, or `none` when `appId` is `null`. The app being filled chooses `viewNode` and `windowTitle` values. |
| `appIdTrusted` | Convenience getter: `true` only if `appIdSource` is `component`. |
| `webDomains` | Every web domain the request claims to be for. Each `AutofillWebDomain` has its own `dalVerified`, `userSelected` and `focused` flags (below). |
| `webDomains[].dalVerified` | `true` only if the app is a known browser or Android reports it as verified to handle links for this domain via Digital Asset Links (Android 12+). This is App Links verification (`delegate_permission/common.handle_all_urls`), not the `get_login_creds` relation (see [Checking `get_login_creds`](#checking-get_login_creds-digitalassetlinks) for that); the host must be one the app declares, exactly or through a wildcard (`*.example.com` covers subdomains of `example.com` but not `example.com` itself). If the app is not visible to your app under Android's package visibility rules the check fails and the domain is unverified; for apps that are not browsers that usually means you need [`QUERY_ALL_PACKAGES`](#package-visibility-query_all_packages). Each domain is checked on its own, so one verified domain says nothing about the others in the same request. The app is identified only from the activity component supplied by Android, never from package names found inside the app's own view hierarchy, and nothing is verified if Android does not supply one. On Android 11 and below it cannot be checked, so `webDomains` is emptied for non-browser apps. |
| `webDomains[].userSelected` | `true` when the app declares the domain but Android has not verified it, and the user enabled "Open supported links" for the app. That is a link-handling choice, not proof of ownership (a malicious app can ask the user to enable it), so it never implies `dalVerified` and you should treat the domain as unverified. |
| `webDomains[].focused` | `true` for the one domain the request is about: the domain of the focused field (or, if no focused field is recognised, the only claimed domain, or that of the password field). At most one domain is `focused`; none is if the primary domain cannot be determined. `AutofillMetadata.focusedWebDomain` returns it. |
| `focusedWebDomain` | Convenience getter: the webdomain from `webDomains[]` which has `focused` == `true``. |
| `isWebOrigin` | `true` if the request claims a web origin, i.e. at least one web domain was found when the request was parsed. (On Android 11 and below `webDomains` is then emptied for non-browser apps.) |
| `appIsBrowser` | `true` if `appId` is a web browser (see below). |
| `appSigningCertSha256` | SHA-256 fingerprints of the certificate(s) that signed the app identified by `appId`, formatted like `sha256_cert_fingerprints` in `assetlinks.json` (`14:6D:E9:...`). Only meaningful when `appIdTrusted` is `true`; empty otherwise, and also empty if the app is not visible to your app (needs [`QUERY_ALL_PACKAGES`](#package-visibility-query_all_packages)). How to read the list depends on `appSigningCertKind`. See [Pinning an app's signing certificates](#pinning-an-apps-signing-certificates). |
| `appSigningCertKind` | The shape of `appSigningCertSha256` (`AppSigningCertKind`): `none` (the list is empty), `single` (one signer: the list is its key rotation lineage, oldest first, and the LAST entry is the certificate that currently signs the app) or `multiple` (several independent signers: the list is a set, in no meaningful order). Defaults to `none` if absent. |
| `appSigningCertCurrentSha256` | Convenience getter: the last fingerprint when `appSigningCertKind` is `single`, otherwise `null`. |

If `isWebOrigin` is `true`, use `focusedWebDomain` as the domain to offer credentials for. If it is `null`, or its `dalVerified` is `false`, an app is presenting a website it has not been shown to own: block the request or ask the user before filling credentials for that domain. Only match entries against domains whose own `dalVerified` is `true`, and never against another domain in `webDomains` on the strength of the focused one. Consider also treating `appId` as an identity only when `appIdTrusted` is `true`.

#### Pinning an app's signing certificates

A package name does not identify an app by itself: a different APK signed by someone else can use the same package name (for example one sideloaded onto a new device). Digital Asset Links already makes Android check the signing certificate of an app against a website's `assetlinks.json`, so that is covered for `webDomains`. For a native app request (no web domain) nothing checks who signed the app, so `appSigningCertSha256` lets you do it:

* Use trust on first use, and use `AppSignerPin` rather than comparing lists yourself. When the user first approves filling an entry from an `appId` with `appIdTrusted`, store `AppSignerPin.fromMetadata(metadata)` (it returns `null` when the request cannot be pinned: untrusted `appId`, a browser, or no fingerprints) with that entry; `toJsonString()` and `AppSignerPin.tryParse` convert it to and from text for storage. On later requests call `pin.match(metadata)`.
* Android reports an app's signing identity in one of two mutually exclusive shapes, shown by `appSigningCertKind`, and they must be compared differently:
  * `single`: one signer. `appSigningCertSha256` is its key rotation lineage (APK Signature Scheme v3), oldest first, with the current certificate last. Only the **current** certificate is pinned. A later request matches if that certificate is still the current one (`AppSignerMatch.matches`) or the app rotated forward so it is now earlier in the lineage (`AppSignerMatch.matchesRotated`; after the user has confirmed the fill, replace the stored pin with `AppSignerPin.fromMetadata` of the new request). A lineage that does not contain the pinned certificate is a `mismatch`. Do **not** treat "any pinned fingerprint is present" as a match: someone who holds a key the app has retired can sign an APK whose lineage lists only that old key (a rollback), or rotates from it to a key of their own, and either would pass such a test.
  * `multiple`: several independent signers, all of which sign the APK. `appSigningCertSha256` is a set, and the whole set is pinned. A later request matches only if the set is exactly equal (`matches`), because a subset or superset is a different identity. Android does not support key rotation for these apps.
  * A change of kind (one signer to several, or back) is a `mismatch`.
* A `mismatch` is not proof of an attack. The same app installed from a different store (for example F-Droid builds versus Google Play) or as a debug build is signed with a different key. Ask the user to confirm rather than blocking outright, and never re-pin automatically after a mismatch.
* `AppSignerMatch.unavailable` means nothing could be compared: the request does not identify an app reliably or the list is empty (see below). Do not treat that as a match for an entry that has a pin.

```dart
final metadata = await AutofillService().autofillMetadata;
final pin = loadPinFor(metadata!.appId!); // your storage; AppSignerPin.tryParse(...)
if (pin == null) {
  // first use: after the user approves, store AppSignerPin.fromMetadata(metadata)
} else {
  switch (pin.match(metadata)) {
    case AppSignerMatch.matches: // fill
    case AppSignerMatch.matchesRotated: // fill, then store AppSignerPin.fromMetadata(metadata)
    case AppSignerMatch.mismatch: // warn and ask the user
    case AppSignerMatch.unavailable: // nothing to compare; do not treat as a match
  }
}
```

The plugin also checks that the signing certificates (and their kind) are the same when the reply is sent as when the request was shown to the user, and refuses the reply (as it does for a changed `appId` or domain) if they differ.

The example app demonstrates this end to end (`example/lib/signature_pins.dart`): the first fill pins the signer (after the plugin reports that the reply was delivered), later requests show "signer matches", a forward key rotation is accepted and the pin moves to the new certificate after the fill, a different signer shows a prominent warning and requires explicit confirmation (with an option to re-pin) before anything is filled, and an empty list shows "signer unknown". Pins are stored as `AppSignerPin` JSON with `shared_preferences` there; in your app store them with the saved entry. The main screen has a button to view and clear the pins.

#### Checking `get_login_creds` (`DigitalAssetLinks`)

`dalVerified` reflects App Links verification (`delegate_permission/common.handle_all_urls`: "this app may open this site's links"). A site can also publish a stronger and more specific statement in its `https://<domain>/.well-known/assetlinks.json`: that it agrees credentials for the site may be shared with a particular app and signing key, using the `delegate_permission/common.get_login_creds` relation:

```json
[{
  "relation": ["delegate_permission/common.get_login_creds"],
  "target": {
    "namespace": "android_app",
    "package_name": "com.example.app",
    "sha256_cert_fingerprints": ["14:6D:E9:..."]
  }
}]
```

`DigitalAssetLinks.grantsGetLoginCredsToApp` (or `grantsGetLoginCreds`, which takes the app ID, fingerprints and `appSigningCertKind` separately) answers whether a statement like that exists for the app making the request. It is pure Dart and does no networking: you fetch the file and pass it in.

```dart
final metadata = await AutofillService().autofillMetadata;
if (metadata != null &&
    metadata.appIdTrusted &&
    !metadata.appIsBrowser &&
    metadata.appSigningCertSha256.isNotEmpty) {
  final body = await fetchAssetLinks(domain); // your code, rules below
  final granted = body != null &&
      DigitalAssetLinks.grantsGetLoginCredsToApp(
        assetLinks: body, // raw JSON text, or the decoded List
        metadata: metadata, // also refuses untrusted app IDs and browsers
      );
}
```

How it differs from `dalVerified`:

* It does not need the app to declare the domain in an intent filter, so it works for apps that share credentials with a site but do not support App Links.
* It does not need Android 12. On Android 10 and 11 (API 29 and 30) `dalVerified` cannot be computed. However, `webDomains` is also emptied there for apps that are not browsers, so you do not learn which domain the app is showing. You have to take the domain from your own saved entry for the requesting app (and ask whether that site grants the app access) rather than from `webDomains`.
* It only accepts `get_login_creds`. `handle_all_urls` is deliberately ignored: a site that lets an app open its links has not agreed to hand that app a login.
* It says nothing about which domain the app is actually showing. Combine it with `focusedWebDomain` or your saved entry.
* It needs `appSigningCertSha256`, so the same `QUERY_ALL_PACKAGES` requirement applies. Only use it when `appIdTrusted` is `true`, `appIsBrowser` is `false` and `appSigningCertSha256` is not empty.

What it matches: some statement whose relation list contains `delegate_permission/common.get_login_creds` and whose target has `namespace` `android_app`, a `package_name` equal to `appId`, and a `sha256_cert_fingerprints` entry equal to the app's signing certificate (compared ignoring case and separators). Which of the app's fingerprints are compared depends on `appSigningCertKind`: for `single` only the **current** (last) certificate, so a site that still lists a retired key does not approve an APK whose lineage merely contains that old key (anyone holding the old key can sign such a lineage that rotates to a key of their own); for `multiple` every one of the signers, all listed by the same statement (Android treats the whole signer set as the app's identity, so an APK that adds an attacker's own key beside a listed signer does not match; signers listed in separate statements do not combine, and an invalid signer fingerprint means no match); for `none` nothing matches. `web` targets and other relations are ignored, and `include` statements are not followed (fetch the included file yourself and call the helper again if you want to support them). Malformed or unexpected input returns `false`, it never throws.

Fetching the file safely is your responsibility. Follow Google's Digital Asset Links rules:

* HTTPS only, from exactly `https://<domain>/.well-known/assetlinks.json`, with normal certificate validation. Validate the domain first: it may be chosen by the app being filled, so reject anything that is not a plain host name (no scheme, port, path, credentials or IP address).
* Do not follow redirects. Require a `200` response with `Content-Type: application/json` and a body that parses as JSON.
* Enforce a timeout and a size limit on the body (measured after decompression).
* Cache the result with a sensible TTL (for example an hour, or what `Cache-Control` allows) and cache failures briefly, so an autofill request does not turn into a web request every time and a slow or hostile host cannot be used to slow you down.

The example app implements a client that does this (`example/lib/assetlinks_check.dart`) and shows the answer beside `dalVerified` for the focused web domain or a domain you type.

#### Which fields are filled

Credentials are only written into fields that belong to the focused domain (or, when the request has no web origin, to no domain), so a page or app cannot receive credentials meant for another origin by including extra fields, iframes or WebViews. Fields that are not visible, or are 1 pixel or less in width or height, are never filled.

#### Detecting browsers (`appIsBrowser`)

`AutofillMetadata.appIsBrowser` is `true` when `appId` is a web browser. A browser's package id is not an app identity, so do not match or learn entries by `appId` in that case. It does not change any `dalVerified` value. Detection uses a built-in browser allowlist plus a query for apps that handle generic `https` links. The latter needs the `<queries>` entry the plugin adds to your merged manifest (Android 11+, not `QUERY_ALL_PACKAGES`). To opt out, remove it with `tools:node="remove"` in your app manifest; only the allowlist is then used.

### Package visibility (`QUERY_ALL_PACKAGES`)

From Android 11 an app can only see other installed packages that it has declared an interest in. The system binds the autofill service, but that does not make the app being filled visible to your app. The plugin's merged manifest only adds a `<queries>` entry for apps that handle generic `https` links (browsers, see [Detecting browsers](#detecting-browsers-appisbrowser)).

Declaring the `QUERY_ALL_PACKAGES` permission in your app enables:

* Digital Asset Links checks for apps that are not browsers (`webDomains[].dalVerified`). The plugin asks Android which domains the requesting app is verified for, and that fails for a package it cannot see.
* Signing certificate information (`appSigningCertSha256`).

This is fail-safe: domains requested by non-browser apps stay unverified (`dalVerified` is `false`) and `appSigningCertSha256` is empty. A warning is logged when this happens (if debug logging is enabled). Known browsers are not affected, and unlike other apps they are verified without a DAL check.

The plugin deliberately does not declare the permission itself. If you want it, add this to your own app's `android/app/src/main/AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <uses-permission
        android:name="android.permission.QUERY_ALL_PACKAGES"
        tools:ignore="QueryAllPackagesPermission" />
    ...
</manifest>
```

`QUERY_ALL_PACKAGES` is a restricted permission on Google Play. You have to complete a declaration in Play Console explaining why your app needs it, and Google decides whether that use case is eligible. We cannot say whether a password manager or similar app will be approved, so check Google's current "Use of the broad package (app) visibility (QUERY_ALL_PACKAGES) permission" policy before relying on it. Apps distributed elsewhere are not subject to that policy. If you cannot use it, design your app on the basis that `dalVerified` will be `false` for non-browser apps and `appSigningCertSha256` will be empty.

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
