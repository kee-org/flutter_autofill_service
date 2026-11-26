import 'package:flutter_autofill_service/flutter_autofill_service.dart';
import 'package:flutter_autofill_service_example/assetlinks_check.dart';
import 'package:flutter_autofill_service_example/signature_pins.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

const _a =
    '14:6D:E9:AA:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB';
const _b =
    'AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89';
const _c =
    'CC:CC:CC:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89';
const _appId = 'com.example.app';

AutofillMetadata _metadata(
        {List<String> certs = const [_a],
        AppSigningCertKind kind = AppSigningCertKind.single,
        AppIdSource source = AppIdSource.component,
        bool browser = false}) =>
    AutofillMetadata(
      appId: _appId,
      appIdSource: source,
      appIsBrowser: browser,
      appSigningCertSha256: certs,
      appSigningCertKind: certs.isEmpty ? AppSigningCertKind.none : kind,
      webDomains: {},
      saveInfo: null,
    );

AppSignerPin _pin(String current) => AppSignerPin.single(current)!;

void main() {
  group('evaluateSigner', () {
    test('first use when nothing is pinned', () {
      expect(evaluateSigner(_metadata(), null), SignerStatus.firstUse);
    });

    test('matches when the pinned certificate is current', () {
      expect(evaluateSigner(_metadata(), _pin(_a)), SignerStatus.matches);
      expect(evaluateSigner(_metadata(certs: [_b, _a]), _pin(_a)),
          SignerStatus.matches);
    });

    test('rotated when the pinned certificate is in an extended lineage', () {
      expect(evaluateSigner(_metadata(certs: [_a, _b]), _pin(_a)),
          SignerStatus.rotated);
    });

    test('a rollback to only an old key is a mismatch', () {
      // pinned the new key `_b`; an APK signed only by the old `_a`
      expect(evaluateSigner(_metadata(certs: [_a]), _pin(_b)),
          SignerStatus.mismatch);
      expect(evaluateSigner(_metadata(certs: [_a, _c]), _pin(_b)),
          SignerStatus.mismatch);
    });

    test('a multi-signer app must have the same set', () {
      final pinned = AppSignerPin.multiple([_a, _b])!;
      const multiple = AppSigningCertKind.multiple;
      expect(
          evaluateSigner(
              _metadata(certs: [_b, _a], kind: multiple), pinned),
          SignerStatus.matches);
      expect(evaluateSigner(_metadata(certs: [_a], kind: multiple), pinned),
          SignerStatus.mismatch);
      expect(
          evaluateSigner(_metadata(certs: [_a, _b, _c], kind: multiple), pinned),
          SignerStatus.mismatch);
    });

    test('unknown, even if pinned, when no fingerprints are reported', () {
      expect(evaluateSigner(_metadata(certs: []), _pin(_a)),
          SignerStatus.unknown);
      expect(evaluateSigner(_metadata(certs: []), null), SignerStatus.unknown);
    });

    test('not applicable for untrusted ids, browsers and no metadata', () {
      expect(evaluateSigner(_metadata(source: AppIdSource.viewNode), null),
          SignerStatus.notApplicable);
      expect(evaluateSigner(_metadata(browser: true), null),
          SignerStatus.notApplicable);
      expect(evaluateSigner(null, null), SignerStatus.notApplicable);
    });
  });

  group('decideWithoutAsking', () {
    test('first use pins the current signer after the fill', () {
      final d = decideWithoutAsking(SignerStatus.firstUse, _metadata())!;
      expect(d.proceed, isTrue);
      expect(d.appId, _appId);
      expect(d.pinAfterFill, _pin(_a));
    });

    test('a forward rotation moves the pin to the new current certificate',
        () {
      final d = decideWithoutAsking(
          SignerStatus.rotated, _metadata(certs: [_a, _b]))!;
      expect(d.proceed, isTrue);
      expect(d.pinAfterFill, _pin(_b));
    });

    test('a match, unknown and not applicable proceed and pin nothing', () {
      for (final status in [
        SignerStatus.matches,
        SignerStatus.unknown,
        SignerStatus.notApplicable
      ]) {
        final d = decideWithoutAsking(status, _metadata())!;
        expect(d.proceed, isTrue);
        expect(d.pinAfterFill, isNull, reason: '$status');
      }
    });

    test('a mismatch has to be put to the user', () {
      expect(decideWithoutAsking(SignerStatus.mismatch, _metadata()), isNull);
    });
  });

  group('SignaturePinStore', () {
    setUp(() => SharedPreferences.setMockInitialValues({}));

    test('pins, lists, re-pins, removes and clears', () async {
      const store = SignaturePinStore();
      expect(await store.pinFor(_appId), isNull);

      await store.pin(_appId, _pin(_a));
      expect(await store.pinFor(_appId), _pin(_a));

      await store.pin(_appId, _pin(_b));
      expect(await store.all(), {_appId: _pin(_b)});

      await store.remove(_appId);
      expect(await store.all(), isEmpty);

      await store.pin(_appId, AppSignerPin.multiple([_a, _b])!);
      expect(await store.pinFor(_appId), AppSignerPin.multiple([_b, _a]));
      await store.clear();
      expect(await store.all(), isEmpty);
    });

    test('drops the old flat format and ignores corrupt values', () async {
      SharedPreferences.setMockInitialValues({
        'signer_pin:com.old.app': [_a, _b],
        'signer_pin_v2:com.corrupt.app': 'not json',
        'signer_pin_v2:com.shape.app': '{"kind":"single","fingerprints":[]}',
      });
      const store = SignaturePinStore();
      expect(await store.pinFor('com.old.app'), isNull);
      expect(await store.pinFor('com.corrupt.app'), isNull);
      expect(await store.all(), isEmpty);
      final prefs = await SharedPreferences.getInstance();
      expect(prefs.getKeys().where((k) => k.startsWith('signer_pin:')),
          isEmpty);
      // a corrupt value is treated as "nothing pinned" so the app is pinned again
      expect(
          evaluateSigner(_metadata(), await store.pinFor('com.corrupt.app')),
          SignerStatus.firstUse);
    });
  });

  group('commitSignerPin', () {
    setUp(() => SharedPreferences.setMockInitialValues({}));

    test('records the pin only when the decision asks for one', () async {
      const store = SignaturePinStore();
      await commitSignerPin(const SignerDecision.proceed());
      await commitSignerPin(const SignerDecision.cancel());
      expect(await store.all(), isEmpty);

      await commitSignerPin(
          SignerDecision.proceed(appId: _appId, pinAfterFill: _pin(_a)));
      expect(await store.pinFor(_appId), _pin(_a));
    });

    test('nothing is pinned before it is committed', () async {
      // The decision is made before the reply; the pin is stored only by
      // commitSignerPin, which the caller runs after a successful reply.
      final d = decideWithoutAsking(SignerStatus.firstUse, _metadata())!;
      expect(await const SignaturePinStore().all(), isEmpty);
      await commitSignerPin(d);
      expect(await const SignaturePinStore().all(), {_appId: _pin(_a)});
    });
  });

  group('AssetLinksClient.validDomain', () {
    test('accepts plain host names, lower cased', () {
      expect(AssetLinksClient.validDomain(' Login.Example.com '),
          'login.example.com');
    });

    test('rejects anything that is not a plain host name', () {
      for (final bad in [
        '',
        'localhost',
        '127.0.0.1',
        '10.0.0.1',
        'https://example.com',
        'example.com/path',
        'example.com:8443',
        'user@example.com',
        'exa mple.com',
        '-example.com',
        '[::1]',
      ]) {
        expect(AssetLinksClient.validDomain(bad), isNull, reason: bad);
      }
    });

    test('fetch refuses an invalid domain without any request', () async {
      final result = await AssetLinksClient().fetch('http://example.com');
      expect(result.ok, isFalse);
    });
  });
}
