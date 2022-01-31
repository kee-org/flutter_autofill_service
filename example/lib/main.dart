import 'dart:async';

import 'package:flutter_autofill_service/flutter_autofill_service.dart';
import 'package:flutter/material.dart';
import 'package:logging/logging.dart';
import 'package:logging_appenders/logging_appenders.dart';

final _logger = Logger('main');

void main() {
  Logger.root.level = Level.ALL;
  PrintAppender().attachToLogger(Logger.root);
  _logger.info('Initialized logger.');
  runApp(const MyApp(false));
}

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
  bool? _hasEnabledAutofillServices;
  AutofillMetadata? _autofillMetadata;
  bool? _fillRequestedAutomatic;
  bool? _fillRequestedInteractive;
  bool? _saveRequested;
  AutofillPreferences? _preferences;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance!.addObserver(this);
    _updateStatus();
  }

  // Platform messages are asynchronous, so we initialize in an async method.
  Future<void> _updateStatus() async {
    _hasEnabledAutofillServices =
        await AutofillService().hasEnabledAutofillServices;
    _autofillMetadata = await AutofillService().getAutofillMetadata();
    _saveRequested = _autofillMetadata?.saveInfo != null;
    _fillRequestedAutomatic = await AutofillService().fillRequestedAutomatic;
    _fillRequestedInteractive =
        await AutofillService().fillRequestedInteractive;
    _preferences = await AutofillService().getPreferences();
    setState(() {});
  }

  @override
  void dispose() {
    WidgetsBinding.instance!.removeObserver(this);
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
        'Building AppState. defaultRouteName:${WidgetsBinding.instance!.window.defaultRouteName}');
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(
          title: const Text('Plugin example app'),
        ),
        body: Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: <Widget>[
              Text(widget.launchedByAutofillService
                  ? 'Autofill launch'
                  : 'Standard launch'),
              Text(
                  '\nhasEnabledAutofillServices: $_hasEnabledAutofillServices\n'),
              Text('fillRequestedAutomatic: $_fillRequestedAutomatic\n'),
              Text('fillRequestedInteractive: $_fillRequestedInteractive\n'),
              Text('SuppliedAutofillMetadata: $_autofillMetadata\n'),
              Text('Offer save enabled: ${_preferences?.enableSaving}\n'),
              ElevatedButton(
                child: const Text('Toggle Save enabled setting'),
                onPressed: () async {
                  await AutofillService().setPreferences(AutofillPreferences(
                    enableDebug: _preferences!.enableDebug,
                    enableSaving: !_preferences!.enableSaving,
                  ));
                  await _updateStatus();
                },
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
                  final response = await AutofillService().resultWithDatasets([
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
    );
  }
}
