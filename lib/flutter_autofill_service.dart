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
    /*required*/
    required this.label,
    /*required*/
    required this.username,
    /*required*/
    required this.password,
  });
  String label;
  String username;
  String password;
}

class AutofillPreferences {
  AutofillPreferences(
      {required this.enableDebug,
      this.enableSaving = true,
      this.enableIMERequests = true});

  factory AutofillPreferences.fromJson(Map<dynamic, dynamic> json) =>
      AutofillPreferences(
        enableDebug: json['enableDebug'] as bool,
        enableSaving: json['enableSaving'] as bool? ?? true,
        enableIMERequests: json['enableIMERequests'] as bool? ?? true,
      );

  final bool enableDebug;
  final bool enableSaving;
  final bool enableIMERequests;

  Map<String, dynamic> toJson() => <String, dynamic>{
        'enableDebug': enableDebug,
        'enableSaving': enableSaving,
        'enableIMERequests': enableIMERequests,
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

  Future<bool> get cmCreatePasskeyRequested async {
    return (await _channel.invokeMethod<bool>('cmCreatePasskeyRequested')) ==
        true;
  }

  Future<bool> get cmGetCredentialRequested async {
    return await _channel.invokeMethod<bool>('cmGetCredentialRequested') ??
        false;
  }

  Future<bool> get cmCreatePasswordRequested async {
    return await _channel.invokeMethod<bool>('cmCreatePasswordRequested') ??
        false;
  }

  Future<String?> get autofillMode async {
    return await _channel.invokeMethod<String>('getAutofillMode');
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

  Future<void> requestSetCmService() async {
    await _channel.invokeMethod<void>('requestSetCmService');
  }

  Future<bool> resultWithDataset(
      {String? label, String? username, String? password}) async {
    return (await _channel.invokeMethod<bool>(
            'resultWithDataset', <String, dynamic>{
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

  Future<bool> onCmGetCredentialComplete(
      String username, String password) async {
    return await _channel.invokeMethod<bool>('onCmGetCredentialComplete', {
          'username': username,
          'password': password,
        }) ??
        false;
  }

  Future<bool> onCmGetCredentialCancelled() async {
    return await _channel.invokeMethod<bool>('onCmGetCredentialCancelled') ??
        false;
  }

  Future<bool> onCmCreatePasswordComplete() async {
    return await _channel.invokeMethod<bool>('onCmCreatePasswordComplete') ??
        false;
  }
}

class AutofillMetadata {
  AutofillMetadata({
    required this.packageNames,
    required this.webDomains,
    required this.saveInfo,
  });
  factory AutofillMetadata.fromJson(Map<dynamic, dynamic> json) {
    final saveInfoJson = json['saveInfo'] as Map<dynamic, dynamic>?;
    return AutofillMetadata(
      packageNames: (json['packageNames'] as Iterable)
          .map((dynamic e) => e as String)
          .toSet(),
      webDomains: ((json['webDomains'] as Iterable?)
              ?.map((dynamic e) =>
                  AutofillWebDomain.fromJson(e as Map<dynamic, dynamic>))
              .toSet()) ??
          {},
      saveInfo:
          saveInfoJson != null ? SaveInfoMetadata.fromJson(saveInfoJson) : null,
    );
  }

  final Set<String> packageNames;
  final Set<AutofillWebDomain> webDomains;
  final SaveInfoMetadata? saveInfo;

  @override
  String toString() => toJson().toString();

  Map<String, Object?> toJson() => {
        'packageNames': packageNames,
        'webDomains': webDomains.map((e) => e.toJson()),
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

class CmGetCredentialRequest {
  final String? callingPackage;
  final String? callingAppLabel;
  final bool requestsPasswords;
  final bool requestsPasskeys;

  CmGetCredentialRequest({
    this.callingPackage,
    this.callingAppLabel,
    this.requestsPasswords = false,
    this.requestsPasskeys = false,
  });

  factory CmGetCredentialRequest.fromMap(Map<dynamic, dynamic> map) {
    return CmGetCredentialRequest(
      callingPackage: map['callingPackage'] as String?,
      callingAppLabel: map['callingAppLabel'] as String?,
      requestsPasswords: map['requestsPasswords'] as bool? ?? false,
      requestsPasskeys: map['requestsPasskeys'] as bool? ?? false,
    );
  }
}
