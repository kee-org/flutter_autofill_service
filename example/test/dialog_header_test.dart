import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_autofill_service_example/main.dart';
import 'package:flutter_test/flutter_test.dart';

/// Drives the example app against a fake of the plugin's platform channel to
/// check that the fill dialog header controls read and write the preference
/// without disturbing the other preferences.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('com.keevault/flutter_autofill_service');
  late Map<String, dynamic> stored;
  late List<Map<String, dynamic>> writes;

  setUp(() {
    writes = [];
    stored = {
      'enableDebug': false,
      'enableSaving': true,
      'enableIMERequests': true,
      'useInAppSelection': true,
      'useEphemeralDataset': true,
      'enableFillDialog': true,
    };
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      switch (call.method) {
        case 'getPreferences':
          return Map<String, dynamic>.from(stored);
        case 'setPreferences':
          stored = Map<String, dynamic>.from(
              (call.arguments as Map)['preferences'] as Map);
          writes.add(Map<String, dynamic>.from(stored));
          return null;
        case 'hasEnabledAutofillServices':
          return true;
        case 'fillRequestedAutomatic':
        case 'fillRequestedInteractive':
          return false;
      }
      return null;
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  Future<void> pumpApp(WidgetTester tester) async {
    // A tall surface so every control is built without scrolling.
    tester.view.physicalSize = const Size(1000, 3000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(const MyApp(false));
    await tester.pumpAndSettle();
  }

  testWidgets('shows no header until one is applied', (tester) async {
    await pumpApp(tester);
    expect(find.text('Fill dialog header: (none)'), findsOneWidget);
  });

  testWidgets('apply stores the entered text and keeps other preferences',
      (tester) async {
    await pumpApp(tester);

    await tester.enterText(
        find.byKey(const Key('dialogHeaderField')), 'Sign in with us');
    await tester.tap(find.byKey(const Key('applyDialogHeader')));
    await tester.pumpAndSettle();

    expect(stored['dialogHeaderText'], 'Sign in with us');
    expect(stored['useInAppSelection'], isTrue);
    expect(stored['enableFillDialog'], isTrue);
    expect(find.text('Fill dialog header: "Sign in with us"'), findsOneWidget);
  });

  testWidgets('clear removes the header', (tester) async {
    stored['dialogHeaderText'] = 'Existing';
    await pumpApp(tester);
    expect(find.text('Fill dialog header: "Existing"'), findsOneWidget);

    await tester.tap(find.byKey(const Key('clearDialogHeader')));
    await tester.pumpAndSettle();

    expect(stored.containsKey('dialogHeaderText'), isFalse);
    expect(find.text('Fill dialog header: (none)'), findsOneWidget);
  });

  testWidgets('toggling another preference does not lose the header',
      (tester) async {
    stored['dialogHeaderText'] = 'Existing';
    await pumpApp(tester);

    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();

    expect(stored['enableSaving'], isFalse);
    expect(stored['dialogHeaderText'], 'Existing');
  });

  testWidgets('fill dialog toggle flips enableFillDialog only', (tester) async {
    stored['dialogHeaderText'] = 'Existing';
    await pumpApp(tester);

    await tester.tap(find.text('Fill dialog (Android 13+)'));
    await tester.pumpAndSettle();

    expect(stored['enableFillDialog'], isFalse);
    expect(stored['dialogHeaderText'], 'Existing');
    expect(writes, hasLength(1));
  });
}
