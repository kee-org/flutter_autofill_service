import 'dart:async';

import 'package:flutter/services.dart';
import 'package:logging/logging.dart';
import 'package:universal_platform/universal_platform.dart';

export 'app_signer_pin.dart';
export 'digital_asset_links.dart';

final _logger = Logger('flutter_autofill_service');

enum AutofillServiceStatus {
  unsupported,
  disabled,
  enabled,
}

class PwDataset {
  PwDataset({
    this.id,
    /*required*/
    required this.label,
    /*required*/
    required this.username,
    /*required*/
    required this.password,
  });
  String? id;
  String label;
  String username;
  String password;
}

class AutofillPreferences {
  AutofillPreferences({
    required this.enableDebug,
    this.enableSaving = true,
    this.enableIMERequests = true,
    this.useInAppSelection = false,
    this.useEphemeralDataset = true,
    this.enableFillDialog = true,
    this.dialogHeaderText,
  });

  factory AutofillPreferences.fromJson(Map<dynamic, dynamic> json) =>
      AutofillPreferences(
        enableDebug: json['enableDebug'] as bool,
        enableSaving: json['enableSaving'] as bool? ?? true,
        enableIMERequests: json['enableIMERequests'] as bool? ?? true,
        useInAppSelection: json['useInAppSelection'] as bool? ?? false,
        useEphemeralDataset: json['useEphemeralDataset'] as bool? ?? true,
        enableFillDialog: json['enableFillDialog'] as bool? ?? true,
        dialogHeaderText: json['dialogHeaderText'] as String?,
      );

  final bool enableDebug;
  final bool enableSaving;
  final bool enableIMERequests;

  /// When true, the unlock suggestion opens the app to show its own entry
  /// picker ([AutofillService.fillRequestedInteractive] is true) and the chosen
  /// entry is filled directly, without any further taps. The app must reply
  /// with [AutofillService.resultWithDataset].
  ///
  /// When false (default) the legacy flow is used: the app returns a list of
  /// matching entries with [AutofillService.resultWithDatasets] and the user
  /// picks from Android's autofill UI.
  final bool useInAppSelection;

  /// Whether the entry chosen in the in-app selection flow is a one-shot fill.
  ///
  /// When true (default) the entry is filled once and Android keeps offering
  /// the "unlock" suggestion, so the user can pick a different entry by
  /// tapping it again. Android does not retain the credential.
  /// When false Android keeps the chosen entry for the rest of the autofill
  /// session and offers it again without unlocking.
  ///
  /// Only takes effect on Android 12 (API 31) or later; older versions always
  /// behave as if this were false. Has no effect unless [useInAppSelection] is
  /// true.
  final bool useEphemeralDataset;

  /// Whether to offer the unlock suggestion in Android's fill dialog, which
  /// appears when a field is first focused. Requires Android 13 (API 33) or
  /// later and only takes effect when the app being filled also supports it.
  /// Enabled by default (opt-out). Experimental: behaviour varies between apps
  /// and Android versions.
  final bool enableFillDialog;

  /// Subtitle shown above the unlock button in Android's fill dialog (Android
  /// 13 / API 33 and later, and only when the app being filled also supports
  /// the dialog). Null (default) shows no subtitle. Pass localised text, for
  /// example from your app's strings. Blank text is treated as no subtitle.
  ///
  /// [AutofillService.setPreferences] replaces the whole stored object, so keep
  /// this value when changing other preferences (use [copyWith], which does).
  final String? dialogHeaderText;

  AutofillPreferences copyWith({
    bool? enableDebug,
    bool? enableSaving,
    bool? enableIMERequests,
    bool? useInAppSelection,
    bool? useEphemeralDataset,
    bool? enableFillDialog,
    // A function, not a value, so that copyWith(dialogHeaderText: () => null) can clear it.
    String? Function()? dialogHeaderText,
  }) =>
      AutofillPreferences(
        enableDebug: enableDebug ?? this.enableDebug,
        enableSaving: enableSaving ?? this.enableSaving,
        enableIMERequests: enableIMERequests ?? this.enableIMERequests,
        useInAppSelection: useInAppSelection ?? this.useInAppSelection,
        useEphemeralDataset: useEphemeralDataset ?? this.useEphemeralDataset,
        enableFillDialog: enableFillDialog ?? this.enableFillDialog,
        dialogHeaderText:
            dialogHeaderText != null ? dialogHeaderText() : this.dialogHeaderText,
      );

  Map<String, dynamic> toJson() => <String, dynamic>{
        'enableDebug': enableDebug,
        'enableSaving': enableSaving,
        'enableIMERequests': enableIMERequests,
        'useInAppSelection': useInAppSelection,
        'useEphemeralDataset': useEphemeralDataset,
        'enableFillDialog': enableFillDialog,
        // Omitted when null so Kotlin's Map<String, Any> never receives a null value.
        if (dialogHeaderText != null) 'dialogHeaderText': dialogHeaderText,
      };
}

class AutofillService {
  factory AutofillService() => _instance;

  AutofillService._();

  static const MethodChannel _channel =
      MethodChannel('com.keevault/flutter_autofill_service');

  static final _instance = AutofillService._();

  Future<bool> get hasAutofillServicesSupport async {
    if (!UniversalPlatform.isAndroid) {
      return false;
    }
    final result =
        await _channel.invokeMethod<bool>('hasAutofillServicesSupport');
    return result ?? false;
  }

  Future<bool> get fillRequestedAutomatic async {
    return (await _channel.invokeMethod<bool>('fillRequestedAutomatic')) ==
        true;
  }

  Future<bool> get fillRequestedInteractive async {
    return (await _channel.invokeMethod<bool>('fillRequestedInteractive')) ==
        true;
  }

  Future<AutofillMetadata?> get autofillMetadata async {
    final result = await _channel
        .invokeMethod<Map<dynamic, dynamic>>('getAutofillMetadata');
    _logger.fine(
        'Got AutofillMetadata packageNames: ${result?['packageNames']}, webDomains: ${result?['webDomains']}, compatMode: ${result?['saveInfo']?['isCompatMode']}');
    if (result == null) {
      return null;
    }
    return AutofillMetadata.fromJson(result);
  }

  Future<AutofillServiceStatus> get status async {
    if (!UniversalPlatform.isAndroid) {
      return AutofillServiceStatus.unsupported;
    }
    final enabled =
        await _channel.invokeMethod<bool>('hasEnabledAutofillServices');
    if (enabled == null) {
      return AutofillServiceStatus.unsupported;
    } else if (enabled) {
      return AutofillServiceStatus.enabled;
    } else {
      return AutofillServiceStatus.disabled;
    }
  }

  Future<AutofillPreferences> get preferences async {
    final json =
        await (_channel.invokeMapMethod<String, dynamic>('getPreferences'));
    _logger.fine('Got preferences $json');
    if (json == null) {
      return AutofillPreferences(enableDebug: false);
    }
    return AutofillPreferences.fromJson(json);
  }

  Future<bool> requestSetAutofillService() async {
    return (await _channel.invokeMethod<bool>('requestSetAutofillService')) ??
        false;
  }

  /// Replies with a single dataset. Returns false, and fills nothing, if the
  /// reply could not be sent or if the screen no longer matches the
  /// [AutofillMetadata] you were given: the reply is built from the screen as
  /// it was when the user tapped the suggestion, and it is refused if the app
  /// or the focused web domain differs from the one in that metadata, so
  /// credentials chosen for one target never reach another.
  Future<bool> resultWithDataset(
      {String? id, String? label, String? username, String? password}) async {
    return (await _channel.invokeMethod<bool>(
            'resultWithDataset', <String, dynamic>{
          'id': id,
          'label': label,
          'username': username,
          'password': password
        })) ??
        false;
  }

  /// Replies with a list of datasets, or declines the request when [datasets]
  /// is null. Like [resultWithDataset], a non-null list is refused (returning
  /// false) if the app or the focused web domain no longer matches the
  /// [AutofillMetadata] you were given.
  Future<bool> resultWithDatasets(List<PwDataset>? datasets) async {
    return (await _channel.invokeMethod<bool>('resultWithDatasets', {
          'datasets': datasets
              ?.map((d) => <String, dynamic>{
                    'id': d.id,
                    'label': d.label,
                    'username': d.username,
                    'password': d.password
                  })
              .toList(growable: false)
        })) ??
        false;
  }

  Future<void> disableAutofillServices() async {
    return await _channel.invokeMethod('disableAutofillServices');
  }

  Future<void> setPreferences(AutofillPreferences preferences) async {
    _logger.fine('set prefs to ${preferences.toJson()}');
    await _channel.invokeMethod<void>(
        'setPreferences', {'preferences': preferences.toJson()});
  }

  Future<void> onSaveComplete() async {
    return (await _channel.invokeMethod<void>('onSaveComplete'));
  }
}

/// Where [AutofillMetadata.appId] came from.
enum AppIdSource {
  /// The activity component reported by Android. The only source the app being
  /// filled cannot choose.
  component,

  /// The package ID of a view node. Chosen by the app being filled.
  viewNode,

  /// The window title. Chosen by the app being filled.
  windowTitle,

  /// No app could be identified, so [AutofillMetadata.appId] is null.
  none,
}

/// The shape of [AutofillMetadata.appSigningCertSha256]. Android reports an
/// app's signing identity in one of two mutually exclusive shapes that must be
/// compared differently, so the list of fingerprints cannot be interpreted
/// without it. [AppSignerPin] does the right comparison for each.
enum AppSigningCertKind {
  /// No fingerprints are available (see [AutofillMetadata.appSigningCertSha256]
  /// for why).
  none,

  /// One signer. The fingerprints are its key rotation lineage (APK Signature
  /// Scheme v3), oldest first, and the LAST entry is the certificate that
  /// currently signs the app ([AutofillMetadata.appSigningCertCurrentSha256]).
  /// An app that never rotated its key has exactly one entry.
  single,

  /// Several independent signers, all of which signed the APK. The fingerprints
  /// are a set in no meaningful order. Android does not support key rotation
  /// for these.
  multiple,
}

class AutofillMetadata {
  AutofillMetadata({
    this.appId,
    this.appIdSource = AppIdSource.none,
    required this.webDomains,
    this.isWebOrigin = false,
    this.appIsBrowser = false,
    this.appSigningCertSha256 = const [],
    this.appSigningCertKind = AppSigningCertKind.none,
    required this.saveInfo,
  });
  factory AutofillMetadata.fromJson(Map<dynamic, dynamic> json) {
    final saveInfoJson = json['saveInfo'] as Map<dynamic, dynamic>?;
    return AutofillMetadata(
      appId: json['appId'] as String?,
      appIdSource: AppIdSource.values.asNameMap()[json['appIdSource']] ??
          AppIdSource.none,
      webDomains: ((json['webDomains'] as Iterable?)
              ?.map((dynamic e) =>
                  AutofillWebDomain.fromJson(e as Map<dynamic, dynamic>))
              .toSet()) ??
          {},
      isWebOrigin: json['isWebOrigin'] as bool? ?? false,
      appIsBrowser: json['appIsBrowser'] as bool? ?? false,
      appSigningCertSha256: (json['appSigningCertSha256'] as Iterable?)
              ?.whereType<String>()
              .toList(growable: false) ??
          const [],
      appSigningCertKind:
          AppSigningCertKind.values.asNameMap()[json['appSigningCertKind']] ??
              AppSigningCertKind.none,
      saveInfo:
          saveInfoJson != null ? SaveInfoMetadata.fromJson(saveInfoJson) : null,
    );
  }

  final String? appId;

  /// Where [appId] came from.
  final AppIdSource appIdSource;

  /// True only if [appId] came from the activity component supplied by Android
  /// ([AppIdSource.component]). Any other source can be chosen by the app being
  /// filled, so do not treat [appId] as that app's identity.
  bool get appIdTrusted => appIdSource == AppIdSource.component;

  /// Every web domain claimed by the app being filled, each with its own
  /// verification status. Any app can claim any domain, so only match entries
  /// against a domain whose [AutofillWebDomain.dalVerified] is true.
  final Set<AutofillWebDomain> webDomains;

  /// The domain this request is about (the domain of the focused field), or null
  /// if there is none. Use this one when there are several [webDomains].
  AutofillWebDomain? get focusedWebDomain {
    for (final domain in webDomains) {
      if (domain.focused) {
        return domain;
      }
    }
    return null;
  }

  /// True if the request claims at least one web origin. This can be true while
  /// [webDomains] is empty, on Android 11 and below for apps that are not browsers.
  final bool isWebOrigin;

  /// True if [appId] is a web browser (built-in allowlist, or an app that handles
  /// generic https links). A browser's package id is not an app identity, so do not
  /// match or learn entries by [appId] when this is true, unless you want to maintain
  /// an override list of known non-browser apps that handle https links (e.g. RSS
  /// readers, "Read it later apps", etc.). Does not affect [AutofillWebDomain.dalVerified].
  final bool appIsBrowser;

  /// SHA-256 fingerprints of the certificate(s) that signed the app identified
  /// by [appId], formatted like `sha256_cert_fingerprints` in `assetlinks.json`
  /// (upper case hex pairs separated by colons, e.g. `14:6D:E9:...`).
  ///
  /// Only meaningful when [appIdTrusted] is true: for any other source the
  /// package name is chosen by the app being filled so nothing is looked up and
  /// this is empty. It is also empty when it cannot be determined: the app
  /// that hosts this plugin has not declared the `QUERY_ALL_PACKAGES`
  /// permission so the requesting app is not visible to it (see the Readme), the
  /// request has no activity component, or the lookup failed. [appIsBrowser]
  /// requests are not an app identity so a browser's value is not useful.
  ///
  /// [appId] alone does not identify an app: a different APK, signed by someone
  /// else, can use the same package name (for example when sideloaded onto a new
  /// device). To detect that, pin the fingerprints per saved entry using trust
  /// on first use: store this list when the user first approves filling an entry
  /// from [appId], and on later requests compare it with what is reported now.
  ///
  /// A mismatch is not proof of an attack. The same app installed from another
  /// store (for example F-Droid builds versus Google Play) or as a debug build
  /// is signed by a different key, so prompt the user to confirm rather than
  /// blocking outright.
  ///
  /// How to read this list depends on [appSigningCertKind]:
  ///
  /// * [AppSigningCertKind.single]: the key rotation lineage (APK Signature
  ///   Scheme v3), oldest first. The LAST entry is the certificate that signs
  ///   the app now ([appSigningCertCurrentSha256]). Pin only that one. A later
  ///   request is genuine if its lineage still contains it (the app rotated
  ///   forward, which only the holder of that key can do). A lineage that
  ///   lacks it, such as one holding only an older certificate, is not: someone
  ///   who obtained an old, retired key can sign an APK that lists only that
  ///   key, so "any pinned fingerprint is present" is NOT a safe test.
  /// * [AppSigningCertKind.multiple]: a set of independent signers, in no
  ///   meaningful order. Require the whole set to be equal to the pinned set.
  ///   A subset is a different identity, and a signer cannot be added or
  ///   rotated without producing a different set.
  ///
  /// Use [AppSignerPin] rather than comparing lists yourself.
  final List<String> appSigningCertSha256;

  /// The shape of [appSigningCertSha256]: [AppSigningCertKind.none] exactly
  /// when that list is empty. Defaults to none if the native side did not send
  /// it.
  final AppSigningCertKind appSigningCertKind;

  /// The certificate that currently signs the app: the last fingerprint of
  /// [appSigningCertSha256] when [appSigningCertKind] is
  /// [AppSigningCertKind.single], otherwise null (there is no single current
  /// certificate for several signers, or none is known).
  String? get appSigningCertCurrentSha256 =>
      appSigningCertKind == AppSigningCertKind.single &&
              appSigningCertSha256.isNotEmpty
          ? appSigningCertSha256.last
          : null;

  final SaveInfoMetadata? saveInfo;

  @override
  String toString() => toJson().toString();

  Map<String, Object?> toJson() => {
        if (appId != null) 'appId': appId!,
        'appIdSource': appIdSource.name,
        'webDomains': webDomains.map((e) => e.toJson()),
        'isWebOrigin': isWebOrigin,
        'appIsBrowser': appIsBrowser,
        'appSigningCertSha256': appSigningCertSha256,
        'appSigningCertKind': appSigningCertKind.name,
        'saveInfo': saveInfo
      };
}

class SaveInfoMetadata {
  SaveInfoMetadata({this.username, this.password, this.isCompatMode});

  factory SaveInfoMetadata.fromJson(Map<dynamic, dynamic> json) =>
      SaveInfoMetadata(
        username: json['username'] as String?,
        password: json['password'] as String?,
        isCompatMode: json['isCompatMode'] as bool?,
      );

  final String? username;
  final String? password;
  final bool? isCompatMode;

  @override
  String toString() => toJson().toString();

  Map<String, Object> toJson() => {
        if (username != null) 'username': username!,
        if (password != null) 'password': password!,
        if (isCompatMode != null) 'isCompatMode': isCompatMode!,
      };
}

class AutofillWebDomain {
  AutofillWebDomain({
    this.scheme,
    required this.domain,
    this.dalVerified = false,
    this.userSelected = false,
    this.focused = false,
  });

  factory AutofillWebDomain.fromJson(Map<dynamic, dynamic> json) =>
      AutofillWebDomain(
        scheme: json['scheme'] as String?,
        domain: json['domain'] as String,
        dalVerified: json['dalVerified'] as bool? ?? false,
        userSelected: json['userSelected'] as bool? ?? false,
        focused: json['focused'] as bool? ?? false,
      );

  final String? scheme;
  final String domain;

  /// True only if the app presenting the request is a known browser, or Android
  /// reports it as verified to handle links for exactly this domain (Digital
  /// Asset Links, Android 12+). A verified domain says nothing about the other
  /// domains in the same request. Never true merely because the user chose to
  /// let the app open the domain's links: see [userSelected].
  ///
  /// For apps that are not browsers this also needs the app that hosts this
  /// plugin to be able to see the requesting app, which on Android 11+ means
  /// declaring the `QUERY_ALL_PACKAGES` permission. Without it the check fails
  /// and the domain is unverified. See the Readme section on package visibility.
  final bool dalVerified;

  /// True if the app declares this domain but Android has not verified it, and
  /// the user enabled "Open supported links" for the app. That is the user's
  /// choice about link handling, not proof that the app owns the domain (a
  /// malicious app can ask the user to enable it), so treat it as unverified.
  /// It is never true together with [dalVerified].
  final bool userSelected;

  /// True for the one domain the request is about: the domain of the focused
  /// field. At most one domain is focused, and none is if it cannot be determined.
  final bool focused;

  @override
  String toString() => toJson().toString();

  Map<String, Object> toJson() => {
        if (scheme != null) 'scheme': scheme!,
        'domain': domain,
        'dalVerified': dalVerified,
        'userSelected': userSelected,
        'focused': focused,
      };
}
