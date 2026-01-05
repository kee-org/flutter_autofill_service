import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_autofill_service/flutter_autofill_service.dart';
import 'package:logging/logging.dart';
import 'package:logging_appenders/logging_appenders.dart';

final _logger = Logger('main');

void main() {
  Logger.root.level = Level.ALL;
  PrintAppender().attachToLogger(Logger.root);
  _logger.info('Initialized logger (main).');
  runApp(const MyApp(false));
}

@pragma('vm:entry-point')
void autofillEntryPoint() {
  Logger.root.level = Level.ALL;
  PrintAppender().attachToLogger(Logger.root);
  _logger.info('Initialized logger (autofill).');
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
  bool? _cmCreatePasswordRequested;
  AutofillPreferences? _preferences;
  String? _autofillMode;

  @override
  void initState() {
    super.initState();
    _logger.info('Example app initialized');
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
    _autofillMode = await AutofillService().autofillMode;

    // Check for Credential Manager create password request
    _cmCreatePasswordRequested =
        await AutofillService().cmCreatePasswordRequested;

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

  @override
  Widget build(BuildContext context) {
    _logger.info(
        'Building AppState. defaultRouteName:${WidgetsBinding.instance.window.defaultRouteName}');
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
                Text(_getLaunchTypeText()),
                Text('\nStatus: $_status\n'),
                Text('fillRequestedAutomatic: $_fillRequestedAutomatic\n'),
                Text('fillRequestedInteractive: $_fillRequestedInteractive\n'),
                Text('SuppliedAutofillMetadata: $_autofillMetadata\n'),
                Text(
                    'Prefs: debug: ${_preferences?.enableDebug}, save: ${_preferences?.enableSaving}, IME: ${_preferences?.enableIMERequests}\n\n'),
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceAround,
                  crossAxisAlignment: CrossAxisAlignment.center,
                  children: [
                    const Flexible(child: Text('Toggle: ')),
                    ElevatedButton(
                      child: const Text('Debug/Logging'),
                      onPressed: () async {
                        await AutofillService()
                            .setPreferences(AutofillPreferences(
                          enableDebug: !_preferences!.enableDebug,
                          enableSaving: _preferences!.enableSaving,
                          enableIMERequests: _preferences!.enableIMERequests,
                        ));
                        await _updateStatus();
                      },
                    ),
                    ElevatedButton(
                      child: const Text('Save'),
                      onPressed: () async {
                        await AutofillService()
                            .setPreferences(AutofillPreferences(
                          enableDebug: _preferences!.enableDebug,
                          enableSaving: !_preferences!.enableSaving,
                          enableIMERequests: _preferences!.enableIMERequests,
                        ));
                        await _updateStatus();
                      },
                    ),
                    ElevatedButton(
                      child: const Text('IME'),
                      onPressed: () async {
                        await AutofillService()
                            .setPreferences(AutofillPreferences(
                          enableDebug: _preferences!.enableDebug,
                          enableSaving: _preferences!.enableSaving,
                          enableIMERequests: !_preferences!.enableIMERequests,
                        ));
                        await _updateStatus();
                      },
                    ),
                  ],
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
                  child: const Text('requestSetCmService'),
                  onPressed: () async {
                    _logger.fine('Starting request.');
                    await AutofillService().requestSetCmService();
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
                  visible: _saveRequested ??
                      false || (_cmCreatePasswordRequested ?? false),
                  child: ElevatedButton(
                    child: const Text('Simulate save operation'),
                    onPressed: () async {
                      _logger.fine(
                          'TODO: Save data: ${_autofillMetadata?.saveInfo}');
                      await AutofillService().onSaveComplete();
                      _logger.fine('save completed');
                      //await _updateStatus();
                    },
                  ),
                ),
                // Credential Manager Create Password Status Display
                if (_cmCreatePasswordRequested == true)
                  Card(
                    margin: const EdgeInsets.all(16),
                    child: Padding(
                      padding: const EdgeInsets.all(16.0),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            'Credential Manager Save Request',
                            style: Theme.of(context).textTheme.titleMedium,
                          ),
                          const SizedBox(height: 8),
                          Text(
                            'Calling App: ${_autofillMetadata?.saveInfo?.appName ?? "Unknown"}',
                          ),
                          Text(
                            'Package: ${_autofillMetadata?.packageNames.firstOrNull ?? "Unknown"}',
                          ),
                          const SizedBox(height: 8),
                          Text(
                            'Username: ${_autofillMetadata?.saveInfo?.username ?? "N/A"}',
                          ),
                          Text(
                            'Password: ${_autofillMetadata?.saveInfo?.password != null ? "***" : "N/A"}',
                          ),
                          const SizedBox(height: 8),
                          const Text(
                            'Press the save button above to complete the save operation',
                            style: TextStyle(fontStyle: FontStyle.italic),
                          ),
                        ],
                      ),
                    ),
                  ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  String _getLaunchTypeText() {
    if (!widget.launchedByAutofillService) {
      return 'Standard launch';
    }

    // Determine the launch type based on autofill_mode
    switch (_autofillMode) {
      case '/credential_manager_get_password':
      case '/credential_manager_create_password': //TODO: this should be via Standard launch so that pending saves can be seen in the main app.
        return 'Credential Manager launch';
      case '/autofill':
      case '/autofill_select':
      default:
        return 'Autofill launch';
    }
  }
}
