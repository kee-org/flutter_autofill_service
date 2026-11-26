import 'package:flutter/material.dart';
import 'package:flutter_autofill_service/flutter_autofill_service.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Reference implementation of "trust on first use" pinning of an app's signing
/// certificates, using `AutofillMetadata.appSigningCertSha256` through the
/// library's `AppSignerPin`.
///
/// Why: a package name does not identify an app. Someone else's APK, signed
/// with a different key, can use the same package name (for example when it is
/// sideloaded onto a new phone). Pinning the signer the first time the user
/// lets you fill an app means you notice when that changes.
///
/// What is pinned depends on the shape Android reports. For an app with one
/// signer only its CURRENT certificate is pinned, and a later request matches
/// if that certificate is still in the app's key lineage (a forward rotation is
/// accepted, and the pin then moves to the new certificate); a lineage without
/// it, such as an APK signed only by a retired key, is a mismatch. For an app
/// with several signers the whole set is pinned and must be equal. Do not
/// write "any pinned fingerprint is present" yourself: it is not safe.
///
/// A real password manager would store the pin with each saved entry, inside
/// its own encrypted database. This demo has no database so it keeps one pin
/// per app ID.

/// What the pin check concluded for a fill request.
enum SignerStatus {
  /// Not an app request we can pin: the app ID is not trusted (it did not come
  /// from the activity component Android supplies) or the app is a browser
  /// (whose package name is not an app identity).
  notApplicable,

  /// The request is pinnable but no fingerprints were reported. The most
  /// likely reason is that this app has not declared QUERY_ALL_PACKAGES (see
  /// the Readme), so it cannot see the app being filled. Never treat this as a
  /// match, even if the app has a pin.
  unknown,

  /// Nothing is pinned for this app ID yet.
  firstUse,

  /// The same signing identity as the pin.
  matches,

  /// A single-signer app that rotated its key forward since it was pinned. As
  /// trustworthy as the pin; the pin is moved to the new certificate once the
  /// user has had something filled.
  rotated,

  /// A different signing identity from the pin.
  mismatch,
}

/// Decides what to tell the user, given the request and what was pinned for
/// its app ID earlier ([pinned] is null if nothing was).
SignerStatus evaluateSigner(AutofillMetadata? metadata, AppSignerPin? pinned) {
  if (metadata == null ||
      metadata.appId == null ||
      !metadata.appIdTrusted ||
      metadata.appIsBrowser) {
    return SignerStatus.notApplicable;
  }
  if (AppSignerPin.fromMetadata(metadata) == null) {
    // Nothing reported, or something we cannot read as a pin.
    return SignerStatus.unknown;
  }
  if (pinned == null) {
    return SignerStatus.firstUse;
  }
  switch (pinned.match(metadata)) {
    case AppSignerMatch.matches:
      return SignerStatus.matches;
    case AppSignerMatch.matchesRotated:
      return SignerStatus.rotated;
    case AppSignerMatch.mismatch:
      return SignerStatus.mismatch;
    case AppSignerMatch.unavailable:
      return SignerStatus.unknown;
  }
}

/// Persists the pin for each app ID.
///
/// Storage choice: `shared_preferences`, because the library's own code has no
/// need for storage and the Flutter engine gives us no writable directory
/// without a plugin such as `path_provider`. (`Directory.systemTemp` is the
/// app's cache directory, which Android may clear at any time, which would
/// silently forget the pins.) A real app should keep pins with its encrypted
/// entries instead.
///
/// Each pin is the JSON of an `AppSignerPin`. Earlier versions of this demo
/// stored a flat list of fingerprints under `signer_pin:<appId>`. That cannot be
/// turned into a pin (it does not say whether the app had one signer or
/// several, or which fingerprint was current) so it is dropped, and the app is
/// simply pinned again the next time it is filled.
class SignaturePinStore {
  const SignaturePinStore();

  static const _prefix = 'signer_pin_v2:';
  static const _legacyPrefix = 'signer_pin:';

  Future<SharedPreferences> _prefs() async {
    final prefs = await SharedPreferences.getInstance();
    // The autofill activity runs its own Flutter engine, whose copy of the
    // preferences can be stale if the main app changed them since.
    await prefs.reload();
    for (final key
        in prefs.getKeys().where((k) => k.startsWith(_legacyPrefix)).toList()) {
      await prefs.remove(key);
    }
    return prefs;
  }

  /// The pin for [appId], or null if there is none (or it cannot be read).
  Future<AppSignerPin?> pinFor(String appId) async =>
      AppSignerPin.tryParse((await _prefs()).getString('$_prefix$appId'));

  /// Every readable pin, keyed by app ID.
  Future<Map<String, AppSignerPin>> all() async {
    final prefs = await _prefs();
    final result = <String, AppSignerPin>{};
    for (final key in prefs.getKeys()) {
      if (!key.startsWith(_prefix)) {
        continue;
      }
      final pin = AppSignerPin.tryParse(prefs.getString(key));
      if (pin != null) {
        result[key.substring(_prefix.length)] = pin;
      }
    }
    return result;
  }

  /// Pins [pin] for [appId], replacing any earlier pin (this is also how a user
  /// "re-pins" after a legitimate change, and how a forward rotation is
  /// followed).
  Future<void> pin(String appId, AppSignerPin pin) async =>
      (await _prefs()).setString('$_prefix$appId', pin.toJsonString());

  Future<void> remove(String appId) async =>
      (await _prefs()).remove('$_prefix$appId');

  Future<void> clear() async {
    final prefs = await _prefs();
    for (final key in prefs.getKeys().where((k) => k.startsWith(_prefix))) {
      await prefs.remove(key);
    }
  }
}

/// The outcome of the pre-fill check: whether to go ahead and which pin, if
/// any, to record once the fill has actually been delivered.
class SignerDecision {
  const SignerDecision.cancel()
      : proceed = false,
        appId = null,
        pinAfterFill = null;

  const SignerDecision.proceed({this.appId, this.pinAfterFill})
      : proceed = true;

  final bool proceed;
  final String? appId;

  /// Record this for [appId] with [commitSignerPin] after the reply succeeded.
  final AppSignerPin? pinAfterFill;
}

/// The decision for every status that needs no question to the user. For a
/// [SignerStatus.mismatch] it returns null: the user has to be asked.
///
/// * First use and a forward rotation proceed and pin the (new) current signer.
/// * A match, an unknown signer and a request that cannot be pinned proceed
///   without changing anything.
SignerDecision? decideWithoutAsking(
    SignerStatus status, AutofillMetadata metadata) {
  final appId = metadata.appId;
  final current = AppSignerPin.fromMetadata(metadata);
  switch (status) {
    case SignerStatus.firstUse:
    case SignerStatus.rotated:
      return SignerDecision.proceed(appId: appId, pinAfterFill: current);
    case SignerStatus.matches:
    case SignerStatus.unknown:
    case SignerStatus.notApplicable:
      return const SignerDecision.proceed();
    case SignerStatus.mismatch:
      return null;
  }
}

/// Runs the pin check immediately before filling. Call this when the user has
/// chosen an entry, not when the request arrives, so a pin is only ever
/// recorded for something the user approved. Then, only if the reply to the
/// plugin succeeds, call [commitSignerPin] with the result.
///
/// * First use: proceeds, and the signer is pinned after the fill (the UI
///   already told the user).
/// * Match: proceeds.
/// * Rotated forward: proceeds, and the pin moves to the new current
///   certificate after the fill.
/// * Mismatch: asks the user. They can cancel, fill this time only, or fill and
///   re-pin to the new signer. A mismatch is not proof of an attack (a debug
///   build or an app from another store has a different key) so it is the
///   user's call, but it must be an explicit one.
/// * Unknown / not applicable: proceeds, there is nothing to compare.
Future<SignerDecision> checkSignerBeforeFill(
    BuildContext context, AutofillMetadata? metadata,
    {SignaturePinStore store = const SignaturePinStore()}) async {
  final appId = metadata?.appId;
  if (metadata == null || appId == null) {
    return const SignerDecision.proceed();
  }
  final status = evaluateSigner(metadata, await store.pinFor(appId));
  final simple = decideWithoutAsking(status, metadata);
  if (simple != null) {
    return simple;
  }
  final choice = await showDialog<_MismatchChoice>(
    context: context,
    barrierDismissible: false,
    builder: (context) => AlertDialog(
      title: const Text('App signer has changed'),
      content: Text('$appId is signed by a different key from the one you '
          'approved earlier. It may be a different build of the same app '
          '(another store, or a debug build) or a counterfeit that is '
          'pretending to be it.\n\nOnly fill if you are sure it is the app '
          'you expect.'),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context, _MismatchChoice.cancel),
          child: const Text('Do not fill'),
        ),
        TextButton(
          onPressed: () => Navigator.pop(context, _MismatchChoice.once),
          child: const Text('Fill this once'),
        ),
        TextButton(
          onPressed: () => Navigator.pop(context, _MismatchChoice.repin),
          child: const Text('Fill and re-pin'),
        ),
      ],
    ),
  );
  switch (choice) {
    case _MismatchChoice.once:
      return const SignerDecision.proceed();
    case _MismatchChoice.repin:
      return SignerDecision.proceed(
          appId: appId, pinAfterFill: AppSignerPin.fromMetadata(metadata));
    case _MismatchChoice.cancel:
    case null:
      return const SignerDecision.cancel();
  }
}

/// Records the pin that [decision] asks for. Call it only after the plugin
/// reported that the reply was delivered (`resultWithDataset` returned true),
/// so a pin is never recorded for a fill that was refused.
///
/// The plugin finishes the host activity as part of replying, so this runs while
/// the activity is going away and the write can occasionally be lost. That is
/// safe: the next request is then evaluated as this one was (a first use, a
/// rotation, or a mismatch the user is asked about again). Errors are swallowed
/// for the same reason. In a real app, store the pin in the same transaction as
/// the approval of the entry instead.
Future<void> commitSignerPin(SignerDecision decision,
    {SignaturePinStore store = const SignaturePinStore()}) async {
  final appId = decision.appId;
  final pin = decision.pinAfterFill;
  if (appId == null || pin == null) {
    return;
  }
  try {
    await store.pin(appId, pin);
  } catch (_) {
    // See above.
  }
}

enum _MismatchChoice { cancel, once, repin }

/// Shows the signer status for a request, and lets the user pin or re-pin
/// without filling anything.
class SignerStatusCard extends StatefulWidget {
  const SignerStatusCard({Key? key, required this.metadata})
      : super(key: key);

  final AutofillMetadata? metadata;

  @override
  State<SignerStatusCard> createState() => _SignerStatusCardState();
}

class _SignerStatusCardState extends State<SignerStatusCard> {
  static const _store = SignaturePinStore();
  SignerStatus? _status;
  AppSignerPin? _pinned;

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  Future<void> _refresh() async {
    final metadata = widget.metadata;
    final pinned =
        metadata?.appId == null ? null : await _store.pinFor(metadata!.appId!);
    if (!mounted) {
      return;
    }
    setState(() {
      _pinned = pinned;
      _status = evaluateSigner(metadata, pinned);
    });
  }

  @override
  Widget build(BuildContext context) {
    final status = _status;
    if (status == null) {
      return const SizedBox.shrink();
    }
    final metadata = widget.metadata!;
    final d = _describe(status, metadata);
    return Card(
      key: const Key('signerStatusCard'),
      color: d.colour,
      margin: const EdgeInsets.all(8),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(children: [
              Icon(d.icon),
              const SizedBox(width: 8),
              Expanded(
                  child: Text(d.headline,
                      style: Theme.of(context).textTheme.titleSmall)),
            ]),
            const SizedBox(height: 4),
            Text(d.detail),
            if (metadata.appSigningCertSha256.isNotEmpty) ...[
              const SizedBox(height: 4),
              Text(metadata.appSigningCertKind == AppSigningCertKind.single
                  ? 'Current signer (last), with earlier keys of its lineage:'
                  : 'Signers (all of them identify the app):'),
              for (final f in metadata.appSigningCertSha256)
                Text(
                    f == metadata.appSigningCertCurrentSha256
                        ? '$f (current)'
                        : f,
                    style: const TextStyle(fontSize: 10)),
            ],
            if (status == SignerStatus.mismatch ||
                status == SignerStatus.firstUse ||
                status == SignerStatus.rotated)
              TextButton(
                onPressed: () async {
                  final pin = AppSignerPin.fromMetadata(metadata);
                  if (pin != null) {
                    await _store.pin(metadata.appId!, pin);
                  }
                  await _refresh();
                },
                child: Text(status == SignerStatus.firstUse
                    ? 'Pin this signer now'
                    : 'Re-pin to current signer'),
              ),
          ],
        ),
      ),
    );
  }

  _Description _describe(
      SignerStatus status, AutofillMetadata metadata) {
    switch (status) {
      case SignerStatus.notApplicable:
        return _Description(
          Colors.grey.shade200,
          Icons.info_outline,
          'Signer check not applicable',
          metadata.appIsBrowser
              ? 'A browser is not an app identity, so there is nothing to pin.'
              : 'The app ID is not trusted (${metadata.appIdSource.name}), '
                  'so it cannot be pinned.');
      case SignerStatus.unknown:
        return _Description(
          Colors.amber.shade100,
          Icons.help_outline,
          'Signer unknown',
          'No signing certificates were reported. Has this app declared '
              'QUERY_ALL_PACKAGES? Without it the requesting app is invisible '
              'so the signer cannot be checked, and a pin cannot be matched.');
      case SignerStatus.firstUse:
        return _Description(
          Colors.blue.shade50,
          Icons.fiber_new,
          'First time seeing this app: pin its signer?',
          'Choosing an entry to fill will pin the signer below for '
              '${metadata.appId}. Later requests are compared against it.');
      case SignerStatus.matches:
        return _Description(
          Colors.green.shade100,
          Icons.verified_user,
          'Signer matches the pin',
          metadata.appSigningCertKind == AppSigningCertKind.multiple
              ? 'The set of signers of ${metadata.appId} is the one pinned.'
              : 'The pinned certificate is still the current signer of '
                  '${metadata.appId}.');
      case SignerStatus.rotated:
        return _Description(
          Colors.lightGreen.shade100,
          Icons.autorenew,
          'Signer rotated its key since it was pinned',
          '${metadata.appId} now uses a newer certificate, and its key lineage '
              'still includes the pinned one, so it was updated by the same '
              'publisher. The pin moves to the new certificate once an entry '
              'has been filled.');
      case SignerStatus.mismatch:
        return _Description(
          Colors.red.shade100,
          Icons.warning_amber_rounded,
          'WARNING: signer differs from the pin',
          '${metadata.appId} is signed by a different key from the one pinned '
              '(${_pinned?.kind.name ?? 'nothing'} pin). This may be a '
              'counterfeit app. You will be asked to confirm before anything '
              'is filled.');
    }
  }
}

class _Description {
  const _Description(this.colour, this.icon, this.headline, this.detail);
  final Color colour;
  final IconData icon;
  final String headline;
  final String detail;
}

/// Lists the pinned signers and lets them be removed. For demonstration only.
class SignaturePinsScreen extends StatefulWidget {
  const SignaturePinsScreen({Key? key}) : super(key: key);

  @override
  State<SignaturePinsScreen> createState() => _SignaturePinsScreenState();
}

class _SignaturePinsScreenState extends State<SignaturePinsScreen> {
  static const _store = SignaturePinStore();
  Map<String, AppSignerPin> _pins = {};

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  Future<void> _refresh() async {
    final pins = await _store.all();
    if (mounted) {
      setState(() => _pins = pins);
    }
  }

  @override
  Widget build(BuildContext context) {
    final apps = _pins.keys.toList()..sort();
    return Scaffold(
      appBar: AppBar(
        title: const Text('Pinned app signers'),
        actions: [
          TextButton(
            onPressed: apps.isEmpty
                ? null
                : () async {
                    await _store.clear();
                    await _refresh();
                  },
            child: const Text('Clear all'),
          ),
        ],
      ),
      body: apps.isEmpty
          ? const Center(child: Text('Nothing pinned yet'))
          : ListView(
              children: [
                for (final appId in apps)
                  ListTile(
                    title: Text(appId),
                    subtitle: Text(
                        '${_pins[appId]!.kind == AppSigningCertKind.single ? 'current signer' : 'all signers'}:\n'
                        '${_pins[appId]!.fingerprints.join('\n')}',
                        style: const TextStyle(fontSize: 10)),
                    isThreeLine: true,
                    trailing: IconButton(
                      icon: const Icon(Icons.delete_outline),
                      tooltip: 'Remove pin',
                      onPressed: () async {
                        await _store.remove(appId);
                        await _refresh();
                      },
                    ),
                  ),
              ],
            ),
    );
  }
}
