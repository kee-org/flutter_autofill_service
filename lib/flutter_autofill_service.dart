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
  AutofillPreferences({required this.enableDebug, this.enableSaving = true});

  factory AutofillPreferences.fromJson(Map<dynamic, dynamic> json) =>
      AutofillPreferences(
        enableDebug: json['enableDebug'] as bool,
        enableSaving: json['enableSaving'] as bool? ?? true,
      );

  final bool enableDebug;
  final bool enableSaving;

  Map<String, dynamic> toJson() => <String, dynamic>{
        'enableDebug': enableDebug,
        'enableSaving': enableSaving,
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
        'Got result for getAutofillMetadata $result (${result.runtimeType})');
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
