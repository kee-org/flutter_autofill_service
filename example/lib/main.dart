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
  runApp(MyApp());
}

class MyApp extends StatefulWidget {
  @override
  _MyAppState createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  bool? _hasEnabledAutofillServices;

  @override
  void initState() {
    super.initState();
    _updateStatus();
  }

  // Platform messages are asynchronous, so we initialize in an async method.
  Future<void> _updateStatus() async {
    _hasEnabledAutofillServices =
        await AutofillService().hasEnabledAutofillServices;
    setState(() {});
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
              Text(
                  'hasEnabledAutofillServices: $_hasEnabledAutofillServices\n'),
              RaisedButton(
                child: const Text('requestSetAutofillService'),
                onPressed: () async {
                  _logger.fine('Starting request.');
                  final response =
                      await AutofillService().requestSetAutofillService();
                  _logger.fine('request finished $response');
                  await _updateStatus();
                },
              ),
              RaisedButton(
                child: const Text('finish'),
                onPressed: () async {
                  _logger.fine('Starting request.');
                  final response = await AutofillService().resultWithDatasets([
                    PwDataset(
                      label: 'this is the label 1',
                      username: 'dummyUsername1',
                      password: 'dpwd1',
                    ),
                    PwDataset(
                      label: 'this is the label 2',
                      username: 'dummyUsername2',
                      password: 'dpwd2',
                    ),
                  ]);
                  _logger.fine('resultWithDatasets $response');
                  await _updateStatus();
                },
              ),
              RaisedButton(
                child: const Text('finish other'),
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
            ],
          ),
        ),
      ),
    );
  }
}
