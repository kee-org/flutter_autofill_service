import 'dart:convert';

import 'package:flutter_autofill_service/flutter_autofill_service.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  const appId = 'com.example.app';
  const fingerprint =
      '14:6D:E9:AA:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB';
  const otherFingerprint =
      'AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89';
  const thirdFingerprint =
      '01:02:03:04:05:06:07:08:09:0A:0B:0C:0D:0E:0F:10:11:12:13:14:15:16:17:18:19:1A:1B:1C:1D:1E:1F:20';
  const loginCreds = 'delegate_permission/common.get_login_creds';
  const handleAllUrls = 'delegate_permission/common.handle_all_urls';

  Map<String, Object?> androidStatement({
    List<Object?> relation = const [loginCreds],
    String namespace = 'android_app',
    Object? package = appId,
    Object? fingerprints = const [fingerprint],
  }) =>
      {
        'relation': relation,
        'target': {
          'namespace': namespace,
          'package_name': package,
          'sha256_cert_fingerprints': fingerprints,
        },
      };

  bool check(Object? body,
          {String id = appId,
          List<String> prints = const [fingerprint],
          AppSigningCertKind kind = AppSigningCertKind.single}) =>
      DigitalAssetLinks.grantsGetLoginCreds(
          assetLinks: body,
          appId: id,
          appSigningCertSha256: prints,
          appSigningCertKind: kind);

  group('grantsGetLoginCreds', () {
    test('matches a raw JSON string', () {
      expect(check(jsonEncode([androidStatement()])), isTrue);
    });

    test('matches an already decoded list', () {
      expect(check([androidStatement()]), isTrue);
    });

    test('matches among unrelated statements and with extra relations', () {
      final body = [
        {
          'relation': [handleAllUrls],
          'target': {'namespace': 'web', 'site': 'https://example.com'},
        },
        androidStatement(package: 'com.other'),
        androidStatement(relation: [handleAllUrls, loginCreds]),
      ];
      expect(check(body), isTrue);
    });

    test('wrong package does not match', () {
      expect(check([androidStatement(package: 'com.example.other')]), isFalse);
      expect(check([androidStatement()], id: 'com.example.App'), isFalse);
      expect(check([androidStatement()], id: ''), isFalse);
    });

    test('wrong fingerprint does not match', () {
      expect(check([androidStatement(fingerprints: [otherFingerprint])]),
          isFalse);
    });

    test('single signer: only the current (last) certificate is compared', () {
      // lineage [other, fingerprint]: the current certificate is fingerprint
      expect(
          check([androidStatement()], prints: [otherFingerprint, fingerprint]),
          isTrue);
      // a site that still lists only the retired key does not approve the app
      expect(
          check([androidStatement(fingerprints: [otherFingerprint])],
              prints: [otherFingerprint, fingerprint]),
          isFalse);
    });

    test('single signer: a lineage that merely contains a listed old key is refused',
        () {
      // Someone holding the retired key `otherFingerprint` can sign a lineage
      // that rotates from it to a key of their own. The site still lists the
      // old key, but the app's current certificate is the attacker's.
      const attackerKey =
          'CC:CC:CC:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89';
      final stale = [androidStatement(fingerprints: [otherFingerprint])];
      expect(check(stale, prints: [otherFingerprint, attackerKey]), isFalse);
      // The genuine lineage is accepted once the site lists the current key.
      final updated = [
        androidStatement(fingerprints: [otherFingerprint, fingerprint])
      ];
      expect(check(updated, prints: [otherFingerprint, fingerprint]), isTrue);
    });

    test('single signer: an invalid current entry does not fall back', () {
      expect(check([androidStatement()], prints: [fingerprint, 'ZZ']), isFalse);
    });

    test('multiple signers: one statement must list every signer', () {
      // All signers listed, in any order, possibly alongside other keys.
      expect(
          check([
            androidStatement(fingerprints: [otherFingerprint, fingerprint])
          ], prints: [fingerprint, otherFingerprint], kind: AppSigningCertKind.multiple),
          isTrue);
      expect(
          check([
            androidStatement(
                fingerprints: [fingerprint, thirdFingerprint, otherFingerprint])
          ], prints: [otherFingerprint, fingerprint], kind: AppSigningCertKind.multiple),
          isTrue);
      // Only some signers listed: an APK that adds a key beside a listed
      // signer must not match.
      expect(
          check([androidStatement(fingerprints: [otherFingerprint])],
              prints: [fingerprint, otherFingerprint],
              kind: AppSigningCertKind.multiple),
          isFalse);
      expect(
          check([androidStatement()],
              prints: [otherFingerprint, fingerprint],
              kind: AppSigningCertKind.multiple),
          isFalse);
      // Signers spread over separate statements do not combine.
      expect(
          check([
            androidStatement(fingerprints: [fingerprint]),
            androidStatement(fingerprints: [otherFingerprint]),
          ], prints: [fingerprint, otherFingerprint], kind: AppSigningCertKind.multiple),
          isFalse);
      // A later statement listing them all still matches.
      expect(
          check([
            androidStatement(fingerprints: [fingerprint]),
            androidStatement(fingerprints: [fingerprint, otherFingerprint]),
          ], prints: [fingerprint, otherFingerprint], kind: AppSigningCertKind.multiple),
          isTrue);
    });

    test('multiple signers: an invalid signer fingerprint fails closed', () {
      expect(
          check([
            androidStatement(fingerprints: [fingerprint, otherFingerprint])
          ], prints: [fingerprint, otherFingerprint, 'ZZ'], kind: AppSigningCertKind.multiple),
          isFalse);
    });

    test('kind none never matches', () {
      expect(check([androidStatement()], kind: AppSigningCertKind.none),
          isFalse);
    });

    test('matches when any of several listed fingerprints is the app\'s', () {
      expect(
          check([androidStatement(fingerprints: [otherFingerprint, fingerprint])]),
          isTrue);
    });

    test('an app with no fingerprints never matches', () {
      expect(check([androidStatement()], prints: const []), isFalse);
    });

    test('invalid fingerprints never match, even each other', () {
      expect(
          check([
            androidStatement(fingerprints: ['', 'AB', 'ZZ'])
          ], prints: ['', 'AB', 'ZZ']),
          isFalse);
    });

    test('fingerprints are compared ignoring case and separators', () {
      final lower = fingerprint.toLowerCase();
      final bare = fingerprint.replaceAll(':', '');
      final spaced = fingerprint.replaceAll(':', ' ');
      expect(check([androidStatement(fingerprints: [lower])]), isTrue);
      expect(check([androidStatement(fingerprints: [bare])]), isTrue);
      expect(check([androidStatement()], prints: [lower]), isTrue);
      expect(check([androidStatement()], prints: [bare]), isTrue);
      expect(check([androidStatement(fingerprints: [spaced])]), isTrue);
    });

    test('handle_all_urls alone is not enough', () {
      expect(check([androidStatement(relation: [handleAllUrls])]), isFalse);
    });

    test('similar relation strings do not match', () {
      expect(
          check([
            androidStatement(
                relation: ['delegate_permission/common.get_login_creds2'])
          ]),
          isFalse);
      expect(
          check([
            androidStatement(
                relation: ['Delegate_Permission/common.get_login_creds'])
          ]),
          isFalse);
    });

    test('a web target is ignored', () {
      final web = {
        'relation': [loginCreds],
        'target': {'namespace': 'web', 'site': 'https://example.com'},
      };
      expect(check([web]), isFalse);
      // Even if it carries android style fields.
      expect(check([androidStatement(namespace: 'web')]), isFalse);
    });

    test('include statements are not followed', () {
      expect(
          check([
            {'include': 'https://example.com/other.json'}
          ]),
          isFalse);
    });

    test('malformed JSON text returns false', () {
      expect(check(''), isFalse);
      expect(check('not json'), isFalse);
      expect(check('[{"relation": '), isFalse);
      expect(check('null'), isFalse);
      expect(check('{}'), isFalse);
      expect(check('"string"'), isFalse);
    });

    test('unexpected shapes return false and never throw', () {
      expect(check(null), isFalse);
      expect(check(42), isFalse);
      expect(check(<Object?, Object?>{}), isFalse);
      expect(check(const []), isFalse);
      expect(check([null, 1, 'x', <Object?>[]]), isFalse);
      expect(check([{}]), isFalse);
      expect(
          check([
            {'relation': loginCreds, 'target': androidStatement()['target']}
          ]),
          isFalse,
          reason: 'relation must be a list');
      expect(
          check([
            {'relation': [loginCreds], 'target': 'x'}
          ]),
          isFalse);
      expect(check([androidStatement(fingerprints: 'x')]), isFalse);
      expect(check([androidStatement(fingerprints: [1, null, {}])]), isFalse);
      expect(check([androidStatement(package: 5)]), isFalse);
      expect(
          check([
            {'relation': [loginCreds, 5, null], 'target': null}
          ]),
          isFalse);
    });

    test('tolerates a leading byte order mark', () {
      expect(check('﻿${jsonEncode([androidStatement()])}'), isTrue);
    });

    test('a bad statement does not prevent a later good one', () {
      expect(check([42, androidStatement(package: 7), androidStatement()]),
          isTrue);
    });
  });

  group('grantsGetLoginCredsToApp', () {
    AutofillMetadata metadata({
      String? id = appId,
      AppIdSource source = AppIdSource.component,
      bool browser = false,
      List<String> prints = const [otherFingerprint, fingerprint],
      AppSigningCertKind kind = AppSigningCertKind.single,
    }) =>
        AutofillMetadata(
          appId: id,
          appIdSource: source,
          appIsBrowser: browser,
          appSigningCertSha256: prints,
          appSigningCertKind: kind,
          webDomains: {},
          saveInfo: null,
        );

    bool grants(AutofillMetadata m) => DigitalAssetLinks.grantsGetLoginCredsToApp(
        assetLinks: [androidStatement()], metadata: m);

    test('uses the metadata\'s id, fingerprints and kind', () {
      expect(grants(metadata()), isTrue);
      expect(grants(metadata(prints: [fingerprint, otherFingerprint])), isFalse);
      expect(
          grants(metadata(
              prints: [fingerprint, otherFingerprint],
              kind: AppSigningCertKind.multiple)),
          isFalse);
      expect(
          DigitalAssetLinks.grantsGetLoginCredsToApp(
              assetLinks: [
                androidStatement(fingerprints: [otherFingerprint, fingerprint])
              ],
              metadata: metadata(
                  prints: [fingerprint, otherFingerprint],
                  kind: AppSigningCertKind.multiple)),
          isTrue);
    });

    test('refuses requests that do not identify an app reliably', () {
      expect(grants(metadata(source: AppIdSource.viewNode)), isFalse);
      expect(grants(metadata(source: AppIdSource.windowTitle)), isFalse);
      expect(grants(metadata(id: null, source: AppIdSource.none)), isFalse);
      expect(grants(metadata(browser: true)), isFalse);
      expect(grants(metadata(prints: [], kind: AppSigningCertKind.none)),
          isFalse);
    });
  });

  group('normalizeFingerprint', () {
    test('normalises to upper case hex', () {
      expect(DigitalAssetLinks.normalizeFingerprint(fingerprint.toLowerCase()),
          fingerprint.replaceAll(':', ''));
    });

    test('rejects the wrong length or non hex content', () {
      expect(DigitalAssetLinks.normalizeFingerprint(''), isNull);
      expect(DigitalAssetLinks.normalizeFingerprint('14:6D'), isNull);
      expect(DigitalAssetLinks.normalizeFingerprint('$fingerprint:00'), isNull);
      expect(
          DigitalAssetLinks.normalizeFingerprint(
              'ZZ${fingerprint.substring(2)}'),
          isNull);
    });
  });
}
