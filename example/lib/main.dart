import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_autofill_service/flutter_autofill_service.dart';
import 'package:logging/logging.dart';
import 'package:logging_appenders/logging_appenders.dart';

import 'autofill_selection_screen.dart';
import 'test_login_form.dart';

final _logger = Logger('main');

void main() {
  Logger.root.level = Level.ALL;
  PrintAppender().attachToLogger(Logger.root);
  _logger.info('Initialized logger.');
  runApp(const MyApp(false));
}

@pragma('vm:entry-point')
void autofillEntryPoint() {
  Logger.root.level = Level.ALL;
  PrintAppender().attachToLogger(Logger.root);
  _logger.info('Initialized logger.');
  runApp(const MyApp(true));
}

class MyApp extends StatefulWidget {
  const MyApp(this.launchedByAutofillService);
  final bool launchedByAutofillService;

  @override
  _MyAppState createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> with WidgetsBindingObserver {
  AutofillServiceStatus? _status;
  AutofillMetadata? _autofillMetadata;
  bool? _fillRequestedAutomatic;
  bool? _fillRequestedInteractive;
  bool? _saveRequested;
  AutofillPreferences? _preferences;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _updateStatus();
  }

  // Platform messages are asynchronous, so we initialize in an async method.
  Future<void> _updateStatus() async {
    _status = await AutofillService().status;
    _autofillMetadata = await AutofillService().autofillMetadata;
    _saveRequested = _autofillMetadata?.saveInfo != null;
    _fillRequestedAutomatic = await AutofillService().fillRequestedAutomatic;
    _fillRequestedInteractive =
        await AutofillService().fillRequestedInteractive;
    _preferences = await AutofillService().preferences;
    setState(() {});
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  Future<void> didChangeAppLifecycleState(AppLifecycleState state) async {
    super.didChangeAppLifecycleState(state);
    if (state == AppLifecycleState.resumed) {
      await _updateStatus();
    }
  }

  String get _effectiveMode {
    final prefs = _preferences;
    if (prefs == null || !prefs.useInAppSelection) return 'Legacy';
    return prefs.useEphemeralDataset
        ? 'New / one-shot (ephemeral)'
        : 'New / sticky (framework keeps chosen entry)';
  }

  @override
  Widget build(BuildContext context) {
    _logger.info(
        'Building AppState. defaultRouteName:${WidgetsBinding.instance.window.defaultRouteName}');
    if (widget.launchedByAutofillService && _fillRequestedInteractive == true) {
      return MaterialApp(
        home: AutofillSelectionScreen(metadata: _autofillMetadata),
      );
    }
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(
          title: const Text('Plugin example app'),
        ),
        body: SingleChildScrollView(
          child: Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: <Widget>[
                Text(widget.launchedByAutofillService
                    ? 'Autofill launch'
                    : 'Standard launch'),
                Text('\nStatus: $_status\n'),
                Text('fillRequestedAutomatic: $_fillRequestedAutomatic\n'),
                Text('fillRequestedInteractive: $_fillRequestedInteractive\n'),
                Text('SuppliedAutofillMetadata: $_autofillMetadata\n'),
                Text(
                    'Prefs: debug: ${_preferences?.enableDebug}, save: ${_preferences?.enableSaving}, IME: ${_preferences?.enableIMERequests}, in-app selection: ${_preferences?.useInAppSelection}, ephemeral: ${_preferences?.useEphemeralDataset}'),
                Text('Effective mode: $_effectiveMode\n\n'),
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceAround,
                  crossAxisAlignment: CrossAxisAlignment.center,
                  children: [
                    const Flexible(child: Text('Toggle: ')),
                    ElevatedButton(
                      child: const Text('Debug/Logging'),
                      onPressed: () async {
                        await AutofillService()
                            .setPreferences(_preferences!.copyWith(
                            enableDebug: !_preferences!.enableDebug));
                        await _updateStatus();
                      },
                    ),
                    ElevatedButton(
                      child: const Text('Save'),
                      onPressed: () async {
                        await AutofillService()
                            .setPreferences(_preferences!.copyWith(
                            enableSaving: !_preferences!.enableSaving));
                        await _updateStatus();
                      },
                    ),
                    ElevatedButton(
                      child: const Text('IME'),
                      onPressed: () async {
                        await AutofillService()
                            .setPreferences(_preferences!.copyWith(
                            enableIMERequests:
                                !_preferences!.enableIMERequests));
                        await _updateStatus();
                      },
                    ),
                  ],
                ),
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceAround,
                  crossAxisAlignment: CrossAxisAlignment.center,
                  children: [
                    const Flexible(child: Text('Toggle: ')),
                    ElevatedButton(
                      child: const Text('In-app selection'),
                      onPressed: () async {
                        await AutofillService().setPreferences(
                            _preferences!.copyWith(
                                useInAppSelection:
                                    !_preferences!.useInAppSelection));
                        await _updateStatus();
                      },
                    ),
                    ElevatedButton(
                      child: const Text('One-shot (ephemeral)'),
                      onPressed: (_preferences?.useInAppSelection ?? false)
                          ? () async {
                              await AutofillService().setPreferences(
                                  _preferences!.copyWith(
                                      useEphemeralDataset:
                                          !_preferences!.useEphemeralDataset));
                              await _updateStatus();
                            }
                          : null,
                    ),
                  ],
                ),
                // Builder: the Navigator is below the MaterialApp, not this widget's context.
                Builder(
                  builder: (context) => ElevatedButton(
                    child: const Text('Open test login form'),
                    onPressed: () => Navigator.of(context).push(
                        MaterialPageRoute(
                            builder: (_) => const TestLoginForm())),
                  ),
                ),
                ElevatedButton(
                  child: const Text('requestSetAutofillService'),
                  onPressed: () async {
                    _logger.fine('Starting request.');
                    final response =
                        await AutofillService().requestSetAutofillService();
                    _logger.fine('request finished $response');
                    await _updateStatus();
                  },
                ),
                ElevatedButton(
                  child: const Text('Simulate automatic autofill result'),
                  onPressed: () async {
                    _logger.fine('Starting request.');
                    final response =
                        await AutofillService().resultWithDatasets([
                      PwDataset(
                        label: 'user and pass 1',
                        username: 'dummyUsername1',
                        password: 'dpwd1',
                      ),
                      PwDataset(
                        label: 'user and pass 2',
                        username: 'dummyUsername2',
                        password: 'dpwd2',
                      ),
                      PwDataset(
                        label: 'user only',
                        username: 'dummyUsername2',
                        password: '',
                      ),
                      PwDataset(
                        label: 'pass only',
                        username: '',
                        password: 'dpwd2',
                      ),
                    ]);
                    _logger.fine('resultWithDatasets $response');
                    await _updateStatus();
                  },
                ),
                ElevatedButton(
                  child: const Text('Simulate interactive autofill result'),
                  onPressed: () async {
                    _logger.fine('Starting request.');
                    final response = await AutofillService().resultWithDataset(
                      label: 'this is the label 3',
                      username: 'dummyUsername3',
                      password: 'dpwd3',
                    );
                    _logger.fine('resultWithDatasets $response');
                    await _updateStatus();
                  },
                ),
                Visibility(
                  visible: _saveRequested ?? false,
                  child: ElevatedButton(
                    child: const Text('Simulate save operation'),
                    onPressed: () async {
                      _logger.fine('TODO: save the supplied data now.');
                      await AutofillService().onSaveComplete();
                      _logger.fine('save completed');
                      await _updateStatus();
                    },
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
