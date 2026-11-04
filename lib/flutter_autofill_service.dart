import 'dart:async';

import 'package:flutter/services.dart';
import 'package:logging/logging.dart';
import 'package:universal_platform/universal_platform.dart';

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
  });

  factory AutofillPreferences.fromJson(Map<dynamic, dynamic> json) =>
      AutofillPreferences(
        enableDebug: json['enableDebug'] as bool,
        enableSaving: json['enableSaving'] as bool? ?? true,
        enableIMERequests: json['enableIMERequests'] as bool? ?? true,
        useInAppSelection: json['useInAppSelection'] as bool? ?? false,
        useEphemeralDataset: json['useEphemeralDataset'] as bool? ?? true,
        enableFillDialog: json['enableFillDialog'] as bool? ?? true,
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

  AutofillPreferences copyWith({
    bool? enableDebug,
    bool? enableSaving,
    bool? enableIMERequests,
    bool? useInAppSelection,
    bool? useEphemeralDataset,
    bool? enableFillDialog,
  }) =>
      AutofillPreferences(
        enableDebug: enableDebug ?? this.enableDebug,
        enableSaving: enableSaving ?? this.enableSaving,
        enableIMERequests: enableIMERequests ?? this.enableIMERequests,
        useInAppSelection: useInAppSelection ?? this.useInAppSelection,
        useEphemeralDataset: useEphemeralDataset ?? this.useEphemeralDataset,
        enableFillDialog: enableFillDialog ?? this.enableFillDialog,
      );

  Map<String, dynamic> toJson() => <String, dynamic>{
        'enableDebug': enableDebug,
        'enableSaving': enableSaving,
        'enableIMERequests': enableIMERequests,
        'useInAppSelection': useInAppSelection,
        'useEphemeralDataset': useEphemeralDataset,
        'enableFillDialog': enableFillDialog,
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

class AutofillMetadata {
  AutofillMetadata({
    this.appId,
    this.appIdSource = AppIdSource.none,
    required this.webDomains,
    this.isWebOrigin = false,
    this.appIsBrowser = false,
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
  final SaveInfoMetadata? saveInfo;

  @override
  String toString() => toJson().toString();

  Map<String, Object?> toJson() => {
        if (appId != null) 'appId': appId!,
        'appIdSource': appIdSource.name,
        'webDomains': webDomains.map((e) => e.toJson()),
        'isWebOrigin': isWebOrigin,
        'appIsBrowser': appIsBrowser,
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
