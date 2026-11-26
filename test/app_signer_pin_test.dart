import 'dart:convert';

import 'package:flutter_autofill_service/flutter_autofill_service.dart';
import 'package:flutter_test/flutter_test.dart';

String _fp(int seed) => [
      for (var i = 0; i < 32; i++)
        ((seed * 37 + i * 11) % 256).toRadixString(16).padLeft(2, '0')
    ].join(':').toUpperCase();

void main() {
  final oldKey = _fp(1);
  final newKey = _fp(2);
  final newerKey = _fp(3);
  final evilKey = _fp(4);

  AutofillMetadata metadata({
    List<String> prints = const [],
    AppSigningCertKind kind = AppSigningCertKind.single,
    AppIdSource source = AppIdSource.component,
    bool browser = false,
    String? id = 'com.example.app',
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

  AppSignerPin singlePinned(String current) => AppSignerPin.single(current)!;

  group('single signer', () {
    final pin = singlePinned(newKey);

    test('pins only the current (last) certificate of the lineage', () {
      final pinned = AppSignerPin.fromMetadata(
          metadata(prints: [oldKey, newKey], kind: AppSigningCertKind.single))!;
      expect(pinned.kind, AppSigningCertKind.single);
      expect(pinned.fingerprints, [newKey]);
      expect(pinned.currentSha256, newKey);
    });

    test('the same certificate matches', () {
      expect(pin.match(metadata(prints: [newKey])), AppSignerMatch.matches);
    });

    test('the same current certificate with the same history matches', () {
      expect(pin.match(metadata(prints: [oldKey, newKey])),
          AppSignerMatch.matches);
    });

    test('a forward rotation matches as rotated', () {
      expect(pin.match(metadata(prints: [oldKey, newKey, newerKey])),
          AppSignerMatch.matchesRotated);
      expect(pin.match(metadata(prints: [newKey, newerKey])),
          AppSignerMatch.matchesRotated);
    });

    test('a rollback to a lineage without the pinned certificate mismatches',
        () {
      // someone with the leaked old key signs an APK whose lineage is just it
      expect(pin.match(metadata(prints: [oldKey])), AppSignerMatch.mismatch);
    });

    test('a lineage that rotates from the old key to an attacker mismatches',
        () {
      expect(pin.match(metadata(prints: [oldKey, evilKey])),
          AppSignerMatch.mismatch);
    });

    test('an unrelated certificate mismatches', () {
      expect(pin.match(metadata(prints: [evilKey])), AppSignerMatch.mismatch);
    });

    test('a pin made while the old key was current follows the app forward',
        () {
      // The user pinned before the app rotated: the pin is the old key.
      final oldPin = singlePinned(oldKey);
      expect(oldPin.match(metadata(prints: [oldKey])), AppSignerMatch.matches);
      expect(oldPin.match(metadata(prints: [oldKey, newKey])),
          AppSignerMatch.matchesRotated);
      // a lineage that does not include the pinned key is unrelated
      expect(oldPin.match(metadata(prints: [newKey])), AppSignerMatch.mismatch);
    });

    test('normalises case and separators on both sides', () {
      final lowerNoColons = newKey.replaceAll(':', '').toLowerCase();
      final fromLower = AppSignerPin.single(lowerNoColons)!;
      expect(fromLower.fingerprints, [newKey]);
      expect(fromLower, pin);
      expect(pin.match(metadata(prints: [oldKey, lowerNoColons])),
          AppSignerMatch.matches);
      expect(pin.match(metadata(prints: [newKey.replaceAll(':', ' ')])),
          AppSignerMatch.matches);
    });

    test('a kind change is a mismatch', () {
      expect(
          pin.match(metadata(
              prints: [newKey], kind: AppSigningCertKind.multiple)),
          AppSignerMatch.mismatch);
      expect(
          pin.match(metadata(
              prints: [newKey, oldKey], kind: AppSigningCertKind.multiple)),
          AppSignerMatch.mismatch);
    });
  });

  group('multiple signers', () {
    final pin = AppSignerPin.multiple([oldKey, newKey])!;

    AutofillMetadata many(List<String> prints) =>
        metadata(prints: prints, kind: AppSigningCertKind.multiple);

    test('pins the whole set, sorted and without duplicates', () {
      final pinned = AppSignerPin.fromMetadata(many([newKey, oldKey, newKey]))!;
      expect(pinned.kind, AppSigningCertKind.multiple);
      expect(pinned.fingerprints.toSet(), {oldKey, newKey});
      expect(pinned.fingerprints.length, 2);
      expect(pinned.fingerprints, [...pinned.fingerprints]..sort());
      expect(pinned.currentSha256, isNull);
      expect(pinned, pin);
    });

    test('the same set matches in any order', () {
      expect(pin.match(many([oldKey, newKey])), AppSignerMatch.matches);
      expect(pin.match(many([newKey, oldKey])), AppSignerMatch.matches);
    });

    test('a subset mismatches', () {
      expect(pin.match(many([oldKey])), AppSignerMatch.mismatch);
      expect(pin.match(many([newKey])), AppSignerMatch.mismatch);
    });

    test('a superset mismatches', () {
      expect(pin.match(many([oldKey, newKey, evilKey])),
          AppSignerMatch.mismatch);
    });

    test('a different set mismatches', () {
      expect(pin.match(many([oldKey, evilKey])), AppSignerMatch.mismatch);
    });

    test('never reports a rotation', () {
      expect(pin.match(many([oldKey, newKey, newerKey])),
          AppSignerMatch.mismatch);
    });

    test('a kind change to a single signer is a mismatch', () {
      expect(pin.match(metadata(prints: [oldKey, newKey])),
          AppSignerMatch.mismatch);
      expect(pin.match(metadata(prints: [newKey])), AppSignerMatch.mismatch);
    });

    test('normalises case and separators', () {
      expect(
          pin.match(many([
            newKey.replaceAll(':', '').toLowerCase(),
            oldKey.toLowerCase()
          ])),
          AppSignerMatch.matches);
    });

    test('a single multi-signer entry is still a set', () {
      final one = AppSignerPin.multiple([oldKey])!;
      expect(one.match(many([oldKey])), AppSignerMatch.matches);
      expect(one.match(metadata(prints: [oldKey])), AppSignerMatch.mismatch);
    });
  });

  group('matchSigners', () {
    test('takes a kind and a list without metadata', () {
      final pin = singlePinned(newKey);
      expect(
          pin.matchSigners(
              kind: AppSigningCertKind.single, fingerprints: [newKey]),
          AppSignerMatch.matches);
      expect(
          pin.matchSigners(
              kind: AppSigningCertKind.single, fingerprints: [oldKey, newKey]),
          AppSignerMatch.matches);
      expect(
          pin.matchSigners(
              kind: AppSigningCertKind.single,
              fingerprints: [newKey, newerKey]),
          AppSignerMatch.matchesRotated);
    });
  });

  group('unavailable', () {
    final pin = singlePinned(newKey);

    test('no fingerprints or kind none', () {
      expect(pin.match(metadata()), AppSignerMatch.unavailable);
      expect(
          pin.match(metadata(prints: [newKey], kind: AppSigningCertKind.none)),
          AppSignerMatch.unavailable);
      expect(
          pin.match(metadata(prints: [], kind: AppSigningCertKind.multiple)),
          AppSignerMatch.unavailable);
    });

    test('an app id that is not trusted', () {
      for (final source in [AppIdSource.viewNode, AppIdSource.windowTitle]) {
        expect(pin.match(metadata(prints: [newKey], source: source)),
            AppSignerMatch.unavailable);
      }
      expect(
          pin.match(metadata(
              prints: [newKey], source: AppIdSource.none, id: null)),
          AppSignerMatch.unavailable);
    });

    test('a browser', () {
      expect(pin.match(metadata(prints: [newKey], browser: true)),
          AppSignerMatch.unavailable);
    });

    test('an invalid fingerprint in the current list', () {
      expect(pin.match(metadata(prints: [newKey, 'not a fingerprint'])),
          AppSignerMatch.unavailable);
      expect(pin.match(metadata(prints: ['zz'])), AppSignerMatch.unavailable);
    });
  });

  group('fromMetadata', () {
    test('is null for requests that cannot be pinned', () {
      expect(AppSignerPin.fromMetadata(metadata(prints: [])), isNull);
      expect(
          AppSignerPin.fromMetadata(
              metadata(prints: [newKey], kind: AppSigningCertKind.none)),
          isNull);
      expect(
          AppSignerPin.fromMetadata(
              metadata(prints: [newKey], source: AppIdSource.viewNode)),
          isNull);
      expect(AppSignerPin.fromMetadata(metadata(prints: [newKey], browser: true)),
          isNull);
      expect(
          AppSignerPin.fromMetadata(
              metadata(prints: [newKey], source: AppIdSource.none, id: null)),
          isNull);
      expect(AppSignerPin.fromMetadata(metadata(prints: ['junk'])), isNull);
      expect(
          AppSignerPin.fromMetadata(
              metadata(prints: [newKey, 'junk'], kind: AppSigningCertKind.multiple)),
          isNull);
    });

    test('a pin always matches the metadata it was made from', () {
      for (final m in [
        metadata(prints: [newKey]),
        metadata(prints: [oldKey, newKey]),
        metadata(prints: [oldKey, newKey], kind: AppSigningCertKind.multiple),
      ]) {
        expect(AppSignerPin.fromMetadata(m)!.match(m), AppSignerMatch.matches);
      }
    });
  });

  group('JSON', () {
    test('round trips a single and a multiple pin', () {
      for (final pin in [
        singlePinned(newKey),
        AppSignerPin.multiple([oldKey, newKey, evilKey])!,
      ]) {
        expect(AppSignerPin.tryFromJson(pin.toJson()), pin);
        expect(AppSignerPin.tryFromJson(jsonDecode(jsonEncode(pin))), pin);
        expect(AppSignerPin.tryParse(pin.toJsonString()), pin);
      }
    });

    test('the encoded form has a kind and fingerprints', () {
      expect(singlePinned(newKey).toJson(), {
        'kind': 'single',
        'fingerprints': [newKey],
      });
    });

    test('normalises stored fingerprints when loading', () {
      final pin = AppSignerPin.tryFromJson({
        'kind': 'single',
        'fingerprints': [newKey.toLowerCase().replaceAll(':', '')],
      });
      expect(pin, singlePinned(newKey));
    });

    test('malformed values are not pins', () {
      for (final bad in <Object?>[
        null,
        42,
        'x',
        <Object?>[],
        <String, Object?>{},
        {'kind': 'single'},
        {'fingerprints': [newKey]},
        {'kind': 'none', 'fingerprints': [newKey]},
        {'kind': 'triple', 'fingerprints': [newKey]},
        {'kind': 3, 'fingerprints': [newKey]},
        {'kind': 'single', 'fingerprints': 'x'},
        {'kind': 'single', 'fingerprints': []},
        {'kind': 'single', 'fingerprints': [newKey, oldKey]},
        {'kind': 'single', 'fingerprints': [1]},
        {'kind': 'single', 'fingerprints': ['junk']},
        {'kind': 'multiple', 'fingerprints': []},
        {'kind': 'multiple', 'fingerprints': [newKey, 'junk']},
        {'kind': 'multiple', 'fingerprints': [newKey, null]},
      ]) {
        expect(AppSignerPin.tryFromJson(bad), isNull, reason: '$bad');
      }
    });

    test('malformed text is not a pin', () {
      for (final bad in <String?>[
        null,
        '',
        'not json',
        '{"kind": ',
        '[]',
        'null',
        '{"kind":"single","fingerprints":[]}',
      ]) {
        expect(AppSignerPin.tryParse(bad), isNull, reason: '$bad');
      }
    });

    test('an old flat list of fingerprints is not a pin', () {
      expect(AppSignerPin.tryFromJson([oldKey, newKey]), isNull);
    });
  });

  group('value semantics', () {
    test('equal pins are equal, different pins are not', () {
      expect(singlePinned(newKey), singlePinned(newKey));
      expect(singlePinned(newKey).hashCode, singlePinned(newKey).hashCode);
      expect(singlePinned(newKey), isNot(singlePinned(oldKey)));
      expect(AppSignerPin.multiple([oldKey])!, isNot(singlePinned(oldKey)));
    });

    test('the fingerprints cannot be modified', () {
      expect(() => singlePinned(newKey).fingerprints.add(oldKey),
          throwsUnsupportedError);
    });

    test('invalid input does not make a pin', () {
      expect(AppSignerPin.single('junk'), isNull);
      expect(AppSignerPin.multiple([]), isNull);
      expect(AppSignerPin.multiple([oldKey, 'junk']), isNull);
      expect(
          AppSignerPin.fromSigners(
              kind: AppSigningCertKind.none, fingerprints: [oldKey]),
          isNull);
    });
  });
}
