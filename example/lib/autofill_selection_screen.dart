import 'package:flutter/material.dart';
import 'package:flutter_autofill_service/flutter_autofill_service.dart';
import 'package:logging/logging.dart';

final _logger = Logger('AutofillSelectionScreen');

final _demoEntries = <PwDataset>[
  PwDataset(
      id: 'demo-1',
      label: 'user and pass 1',
      username: 'dummyUsername1',
      password: 'dpwd1'),
  PwDataset(
      id: 'demo-2',
      label: 'user and pass 2',
      username: 'dummyUsername2',
      password: 'dpwd2'),
  PwDataset(
      id: 'demo-3',
      label: 'user only',
      username: 'dummyUsername3',
      password: ''),
  PwDataset(
      id: 'demo-4', label: 'pass only', username: '', password: 'dpwd4'),
];

/// Shown when the autofill service launches the app for the user to choose
/// one entry (`fillRequestedInteractive`).
class AutofillSelectionScreen extends StatefulWidget {
  const AutofillSelectionScreen({this.metadata});

  final AutofillMetadata? metadata;

  @override
  State<AutofillSelectionScreen> createState() =>
      _AutofillSelectionScreenState();
}

class _AutofillSelectionScreenState extends State<AutofillSelectionScreen> {
  String _query = '';

  Future<void> _submit(PwDataset entry) async {
    // The plugin finishes the host activity after a result, so no Navigator.pop.
    // Used by the in-app selection flow and the legacy "Use a different entry" launch.
    _logger.fine('resultWithDataset');
    await AutofillService().resultWithDataset(
      id: entry.id,
      label: entry.label,
      username: entry.username,
      password: entry.password,
    );
  }

  @override
  Widget build(BuildContext context) {
    final q = _query.toLowerCase();
    final entries = _demoEntries
        .where((e) =>
            q.isEmpty ||
            e.label.toLowerCase().contains(q) ||
            e.username.toLowerCase().contains(q))
        .toList();
    final origin = [
      ...?widget.metadata?.webDomains.map((d) => d.domain),
      if (widget.metadata?.appId != null) widget.metadata!.appId!,
    ].join(', ');
    return Scaffold(
      appBar: AppBar(
        title: const Text('Select an entry'),
        actions: [
          TextButton(
            // A blank dataset would fill the form with empty values.
            onPressed: () => AutofillService().resultWithDatasets(null),
            child: const Text('Cancel'),
          ),
        ],
      ),
      body: Column(
        children: [
          if (origin.isNotEmpty)
            Padding(
              padding: const EdgeInsets.all(8),
              child: Text('For: $origin'),
            ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: TextField(
              decoration: const InputDecoration(
                  prefixIcon: Icon(Icons.search), hintText: 'Search'),
              onChanged: (v) => setState(() => _query = v),
            ),
          ),
          Expanded(
            child: ListView(
              children: [
                for (final e in entries)
                  ListTile(
                    title: Text(e.label),
                    subtitle: Text(e.username),
                    onTap: () => _submit(e),
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
