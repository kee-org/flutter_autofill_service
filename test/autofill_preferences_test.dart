import 'package:flutter/services.dart';
import 'package:flutter_autofill_service/flutter_autofill_service.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('AutofillPreferences.dialogHeaderText', () {
    test('defaults to null', () {
      expect(AutofillPreferences(enableDebug: false).dialogHeaderText, isNull);
    });

    test('is omitted from toJson when null', () {
      final json = AutofillPreferences(enableDebug: false).toJson();
      expect(json.containsKey('dialogHeaderText'), isFalse);
    });

    test('is included in toJson when set', () {
      final json = AutofillPreferences(
        enableDebug: false,
        dialogHeaderText: 'Tap below',
      ).toJson();
      expect(json['dialogHeaderText'], 'Tap below');
    });

    test('fromJson reads the value', () {
      final prefs = AutofillPreferences.fromJson(
          {'enableDebug': false, 'dialogHeaderText': 'Hello'});
      expect(prefs.dialogHeaderText, 'Hello');
    });

    test('fromJson treats a missing key (existing installs) as null', () {
      final prefs = AutofillPreferences.fromJson({'enableDebug': true});
      expect(prefs.dialogHeaderText, isNull);
    });

    test('survives a toJson/fromJson round trip', () {
      final original = AutofillPreferences(
        enableDebug: true,
        enableFillDialog: false,
        dialogHeaderText: 'Ünïcode ✓',
      );
      final copy = AutofillPreferences.fromJson(original.toJson());
      expect(copy.dialogHeaderText, 'Ünïcode ✓');
      expect(copy.enableFillDialog, isFalse);
      expect(copy.enableDebug, isTrue);
    });

    test('copyWith without the argument keeps the stored value', () {
      final prefs =
          AutofillPreferences(enableDebug: false, dialogHeaderText: 'Keep me');
      expect(prefs.copyWith(enableSaving: false).dialogHeaderText, 'Keep me');
    });

    test('copyWith replaces the value', () {
      final prefs =
          AutofillPreferences(enableDebug: false, dialogHeaderText: 'Old');
      expect(prefs.copyWith(dialogHeaderText: () => 'New').dialogHeaderText,
          'New');
    });

    test('copyWith can set the value when it was null', () {
      final prefs = AutofillPreferences(enableDebug: false);
      expect(prefs.copyWith(dialogHeaderText: () => 'New').dialogHeaderText,
          'New');
    });

    test('copyWith(dialogHeaderText: () => null) clears the value', () {
      final prefs =
          AutofillPreferences(enableDebug: false, dialogHeaderText: 'Old');
      final cleared = prefs.copyWith(dialogHeaderText: () => null);
      expect(cleared.dialogHeaderText, isNull);
      expect(cleared.toJson().containsKey('dialogHeaderText'), isFalse);
    });
  });

  group('platform channel', () {
    const channel = MethodChannel('com.keevault/flutter_autofill_service');
    Map<dynamic, dynamic>? stored;

    setUp(() {
      stored = null;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
        switch (call.method) {
          case 'setPreferences':
            stored = Map<dynamic, dynamic>.from(
                (call.arguments as Map)['preferences'] as Map);
            return null;
          case 'getPreferences':
            return stored;
        }
        return null;
      });
    });

    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null);
    });

    test('setPreferences sends the header to the platform', () async {
      await AutofillService().setPreferences(
          AutofillPreferences(enableDebug: false, dialogHeaderText: 'Sign in'));
      expect(stored!['dialogHeaderText'], 'Sign in');
    });

    test('setPreferences sends no header key when null', () async {
      await AutofillService()
          .setPreferences(AutofillPreferences(enableDebug: false));
      expect(stored!.containsKey('dialogHeaderText'), isFalse);
    });

    test('header survives the read-modify-write cycle used by other setters',
        () async {
      await AutofillService().setPreferences(
          AutofillPreferences(enableDebug: false, dialogHeaderText: 'Sign in'));

      final prefs = await AutofillService().preferences;
      await AutofillService()
          .setPreferences(prefs.copyWith(enableSaving: false));

      final after = await AutofillService().preferences;
      expect(after.enableSaving, isFalse);
      expect(after.dialogHeaderText, 'Sign in');
    });

    test('platform reply without a header key reads back as null', () async {
      stored = {'enableDebug': false, 'enableSaving': true};
      expect((await AutofillService().preferences).dialogHeaderText, isNull);
    });
  });
}
