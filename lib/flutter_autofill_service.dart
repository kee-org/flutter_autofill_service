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
  });

  factory AutofillPreferences.fromJson(Map<dynamic, dynamic> json) =>
      AutofillPreferences(
        enableDebug: json['enableDebug'] as bool,
        enableSaving: json['enableSaving'] as bool? ?? true,
        enableIMERequests: json['enableIMERequests'] as bool? ?? true,
        useInAppSelection: json['useInAppSelection'] as bool? ?? false,
        useEphemeralDataset: json['useEphemeralDataset'] as bool? ?? true,
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

  AutofillPreferences copyWith({
    bool? enableDebug,
    bool? enableSaving,
    bool? enableIMERequests,
    bool? useInAppSelection,
    bool? useEphemeralDataset,
  }) =>
      AutofillPreferences(
        enableDebug: enableDebug ?? this.enableDebug,
        enableSaving: enableSaving ?? this.enableSaving,
        enableIMERequests: enableIMERequests ?? this.enableIMERequests,
        useInAppSelection: useInAppSelection ?? this.useInAppSelection,
        useEphemeralDataset: useEphemeralDataset ?? this.useEphemeralDataset,
      );

  Map<String, dynamic> toJson() => <String, dynamic>{
        'enableDebug': enableDebug,
        'enableSaving': enableSaving,
        'enableIMERequests': enableIMERequests,
        'useInAppSelection': useInAppSelection,
        'useEphemeralDataset': useEphemeralDataset,
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

class AutofillMetadata {
  AutofillMetadata({
    this.appId,
    this.appIdTrusted = false,
    required this.webDomains,
    this.isWebOrigin = false,
    this.dalVerified = false,
    required this.saveInfo,
  });
  factory AutofillMetadata.fromJson(Map<dynamic, dynamic> json) {
    final saveInfoJson = json['saveInfo'] as Map<dynamic, dynamic>?;
    return AutofillMetadata(
      appId: json['appId'] as String?,
      appIdTrusted: json['appIdTrusted'] as bool? ?? false,
      webDomains: ((json['webDomains'] as Iterable?)
              ?.map((dynamic e) =>
                  AutofillWebDomain.fromJson(e as Map<dynamic, dynamic>))
              .toSet()) ??
          {},
      isWebOrigin: json['isWebOrigin'] as bool? ?? false,
      dalVerified: json['dalVerified'] as bool? ?? false,
      saveInfo:
          saveInfoJson != null ? SaveInfoMetadata.fromJson(saveInfoJson) : null,
    );
  }

  final String? appId;
  final bool appIdTrusted;
  final Set<AutofillWebDomain> webDomains;
  final bool isWebOrigin;
  final bool dalVerified;
  final SaveInfoMetadata? saveInfo;

  @override
  String toString() => toJson().toString();

  Map<String, Object?> toJson() => {
        if (appId != null) 'appId': appId!,
        'appIdTrusted': appIdTrusted,
        'webDomains': webDomains.map((e) => e.toJson()),
        'isWebOrigin': isWebOrigin,
        'dalVerified': dalVerified,
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
  AutofillWebDomain({this.scheme, required this.domain});

  factory AutofillWebDomain.fromJson(Map<dynamic, dynamic> json) =>
      AutofillWebDomain(
        scheme: json['scheme'] as String?,
        domain: json['domain'] as String,
      );

  final String? scheme;
  final String domain;

  @override
  String toString() => toJson().toString();

  Map<String, Object> toJson() => {
        if (scheme != null) 'scheme': scheme!,
        'domain': domain,
      };
}
