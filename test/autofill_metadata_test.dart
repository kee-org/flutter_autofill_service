import 'package:flutter_autofill_service/flutter_autofill_service.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('AutofillMetadata.appSigningCertSha256', () {
    const fingerprint =
        '14:6D:E9:AA:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB';

    test('defaults to an empty list', () {
      expect(
          AutofillMetadata(webDomains: {}, saveInfo: null).appSigningCertSha256,
          isEmpty);
    });

    test('fromJson tolerates a missing value', () {
      final metadata = AutofillMetadata.fromJson({'webDomains': []});
      expect(metadata.appSigningCertSha256, isEmpty);
    });

    test('fromJson tolerates a null value', () {
      final metadata = AutofillMetadata.fromJson(
          {'webDomains': [], 'appSigningCertSha256': null});
      expect(metadata.appSigningCertSha256, isEmpty);
    });

    test('fromJson reads the fingerprints in order', () {
      final metadata = AutofillMetadata.fromJson({
        'appId': 'com.example',
        'appIdSource': 'component',
        'webDomains': [],
        'appSigningCertSha256': [fingerprint, 'AA:BB'],
      });
      expect(metadata.appSigningCertSha256, [fingerprint, 'AA:BB']);
      expect(metadata.appIdTrusted, isTrue);
    });

    test('toJson includes the fingerprints and round trips', () {
      final metadata = AutofillMetadata(
        appId: 'com.example',
        appIdSource: AppIdSource.component,
        webDomains: {},
        appSigningCertSha256: const [fingerprint],
        saveInfo: null,
      );
      final json = metadata.toJson();
      expect(json['appSigningCertSha256'], [fingerprint]);
      final again = AutofillMetadata.fromJson(json);
      expect(again.appSigningCertSha256, [fingerprint]);
    });
  });

  group('AutofillMetadata.appSigningCertKind', () {
    const a =
        '14:6D:E9:AA:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB';
    const b =
        'AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89';

    test('defaults to none', () {
      expect(AutofillMetadata(webDomains: {}, saveInfo: null).appSigningCertKind,
          AppSigningCertKind.none);
    });

    test('fromJson reads each kind', () {
      for (final kind in AppSigningCertKind.values) {
        final metadata = AutofillMetadata.fromJson(
            {'webDomains': [], 'appSigningCertKind': kind.name});
        expect(metadata.appSigningCertKind, kind);
      }
    });

    test('fromJson treats missing, null, unknown and mistyped as none', () {
      for (final json in <Map<String, Object?>>[
        {'webDomains': []},
        {'webDomains': [], 'appSigningCertKind': null},
        {'webDomains': [], 'appSigningCertKind': 'triple'},
        {'webDomains': [], 'appSigningCertKind': 3},
      ]) {
        expect(AutofillMetadata.fromJson(json).appSigningCertKind,
            AppSigningCertKind.none);
      }
    });

    test('toJson includes the kind and round trips', () {
      final metadata = AutofillMetadata(
        appId: 'com.example',
        appIdSource: AppIdSource.component,
        webDomains: {},
        appSigningCertSha256: const [a, b],
        appSigningCertKind: AppSigningCertKind.single,
        saveInfo: null,
      );
      final json = metadata.toJson();
      expect(json['appSigningCertKind'], 'single');
      expect(AutofillMetadata.fromJson(json).appSigningCertKind,
          AppSigningCertKind.single);
    });

    test('appSigningCertCurrentSha256 is the last entry for a single signer',
        () {
      AutofillMetadata with_(AppSigningCertKind kind, List<String> prints) =>
          AutofillMetadata(
              webDomains: {},
              appSigningCertSha256: prints,
              appSigningCertKind: kind,
              saveInfo: null);
      expect(with_(AppSigningCertKind.single, [a, b]).appSigningCertCurrentSha256,
          b);
      expect(with_(AppSigningCertKind.single, [a]).appSigningCertCurrentSha256,
          a);
      expect(
          with_(AppSigningCertKind.multiple, [a, b]).appSigningCertCurrentSha256,
          isNull);
      expect(with_(AppSigningCertKind.none, []).appSigningCertCurrentSha256,
          isNull);
      expect(with_(AppSigningCertKind.single, []).appSigningCertCurrentSha256,
          isNull);
    });
  });
}
