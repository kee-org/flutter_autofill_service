import 'dart:async';
import 'package:universal_platform/universal_platform.dart';
import 'package:flutter/services.dart';
import 'package:logging/logging.dart';

final _logger = Logger('flutter_autofill_service');

enum AutofillServiceStatus {
  unsupported,
  disabled,
  enabled,
}

class PwDataset {
  String label;
  String username;
  String password;
  PwDataset({
    /*required*/
    required this.label,
    /*required*/
    required this.username,
    /*required*/
    required this.password,
  });
}

class AutofillPreferences {
  AutofillPreferences({required this.enableDebug});

  factory AutofillPreferences.fromJson(Map<dynamic, dynamic> json) =>
      AutofillPreferences(enableDebug: json['enableDebug'] as bool);

  final bool enableDebug;

  Map<String, dynamic> toJson() => <String, dynamic>{
        'enableDebug': enableDebug,
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

  Future<bool> get hasEnabledAutofillServices async {
    return (await _channel.invokeMethod<bool>('hasEnabledAutofillServices')) ==
        true;
  }

  Future<bool> get fillRequestedAutomatic async {
    return (await _channel.invokeMethod<bool>('fillRequestedAutomatic')) ==
        true;
  }

  Future<bool> get fillRequestedInteractive async {
    return (await _channel.invokeMethod<bool>('fillRequestedInteractive')) ==
        true;
  }

//TODO: Change to getter for consistency with other data access points above?
  Future<AutofillMetadata?> getAutofillMetadata() async {
    final result = await _channel
        .invokeMethod<Map<dynamic, dynamic>>('getAutofillMetadata');
    _logger.fine(
        'Got result for getAutofillMetadata $result (${result.runtimeType})');
    if (result == null) {
      return null;
    }
    return AutofillMetadata.fromJson(result);
  }

  Future<AutofillServiceStatus> status() async {
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

  Future<AutofillPreferences> getPreferences() async {
    final json =
        await (_channel.invokeMapMethod<String, dynamic>('getPreferences')
            as FutureOr<Map<String, dynamic>>);
    _logger.fine('Got preferences $json');
    return AutofillPreferences.fromJson(json);
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
  SaveInfoMetadata({this.username, this.password});

  factory SaveInfoMetadata.fromJson(Map<dynamic, dynamic> json) =>
      SaveInfoMetadata(
        username: json['username'] as String?,
        password: json['password'] as String?,
      );

  final String? username;
  final String? password;

  @override
  String toString() => toJson().toString();

  Map<String, Object> toJson() => {
        if (username != null) 'username': username!,
        if (password != null) 'password': password!,
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
