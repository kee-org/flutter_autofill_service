import 'dart:convert';

import 'package:flutter/foundation.dart';

import 'flutter_autofill_service.dart';

/// The outcome of comparing the signing identity of the app that is being
/// filled now with an [AppSignerPin] recorded earlier.
enum AppSignerMatch {
  /// The same signing identity: for a single signer the pinned certificate is
  /// still the one signing the app, for several signers the set is identical.
  matches,

  /// A single-signer app whose signing key was rotated forward since it was
  /// pinned: the pinned certificate is in the app's lineage but is no longer the
  /// current one. Only the holder of the pinned key can have produced such a
  /// lineage, so this is as trustworthy as [matches]. Once the user has
  /// confirmed the fill, replace the pin with `AppSignerPin.fromMetadata` of
  /// the current request so the new certificate is the one pinned.
  matchesRotated,

  /// A different signing identity: another certificate, a rollback to a lineage
  /// that lacks the pinned certificate (for example an APK signed only by a
  /// retired key), a different set of signers, or a change between one signer
  /// and several. Not proof of an attack (another store or a debug build is
  /// signed by another key), so ask the user rather than silently blocking, and
  /// never auto re-pin.
  mismatch,

  /// There is nothing to compare: the request does not identify an app (see
  /// [AppSignerPin.fromMetadata]) or no fingerprints were reported (for example
  /// the app lacks `QUERY_ALL_PACKAGES`). Never treat this as a match.
  unavailable,
}

/// A recorded signing identity of an app, for "trust on first use" pinning of
/// `AutofillMetadata.appId`. Store one per app ID (or per saved entry) when
/// the user first approves filling that app, and later call [match] with the
/// metadata of each new request.
///
/// What is stored depends on the shape Android reports
/// ([AppSigningCertKind]), because the two shapes must be compared differently:
///
/// * [AppSigningCertKind.single]: only the CURRENT certificate (the last entry
///   of the lineage) is pinned. A request matches if that certificate is still
///   the current one, or is later in the lineage than it was (the app rotated
///   its key forward: [AppSignerMatch.matchesRotated]). A lineage that does not
///   contain it is a mismatch. In particular, anyone holding an old key the app
///   has since retired can sign an APK whose lineage consists of only that old
///   key; accepting "any pinned fingerprint present" would let that through
///   after a pin of the old key-then-new-key lineage.
/// * [AppSigningCertKind.multiple]: the whole set of signers is pinned and must
///   be exactly equal. A subset or superset is a different identity. Android
///   does not support key rotation for multi-signer APKs.
///
/// Fingerprints are compared ignoring case and separators (see
/// [DigitalAssetLinks.normalizeFingerprint]) and stored as upper case colon
/// separated hex. Instances are immutable and compare by value.
@immutable
class AppSignerPin {
  const AppSignerPin._(this.kind, this.fingerprints);

  /// A pin of a single-signer app, whose current certificate is
  /// [currentSha256], or null if that is not a SHA-256 fingerprint.
  static AppSignerPin? single(String currentSha256) => fromSigners(
      kind: AppSigningCertKind.single, fingerprints: [currentSha256]);

  /// A pin of an app with several signers, or null if [signerSha256] is empty
  /// or contains something that is not a SHA-256 fingerprint.
  static AppSignerPin? multiple(Iterable<String> signerSha256) =>
      fromSigners(
          kind: AppSigningCertKind.multiple, fingerprints: signerSha256);

  /// Builds a pin from a [kind] and the matching `appSigningCertSha256`
  /// [fingerprints] (for [AppSigningCertKind.single] the lineage, oldest first
  /// with the current certificate last, of which only the last is kept). Null
  /// if there is nothing to pin: kind is [AppSigningCertKind.none], the list is
  /// empty, or a fingerprint is not valid.
  static AppSignerPin? fromSigners({
    required AppSigningCertKind kind,
    required Iterable<String> fingerprints,
  }) {
    final normalized = _normalizeAll(fingerprints);
    if (normalized == null || normalized.isEmpty) {
      return null;
    }
    switch (kind) {
      case AppSigningCertKind.none:
        return null;
      case AppSigningCertKind.single:
        return AppSignerPin._(kind, List.unmodifiable([normalized.last]));
      case AppSigningCertKind.multiple:
        final set = normalized.toSet().toList()..sort();
        return AppSignerPin._(kind, List.unmodifiable(set));
    }
  }

  /// The pin to record for the app in [metadata], or null if the request is not
  /// pinnable: the app ID is not trusted (`appIdTrusted` is false), the app is a
  /// browser (a browser's package name is not an app identity), or no
  /// fingerprints are available.
  static AppSignerPin? fromMetadata(AutofillMetadata metadata) {
    if (!_pinnable(metadata)) {
      return null;
    }
    return fromSigners(
      kind: metadata.appSigningCertKind,
      fingerprints: metadata.appSigningCertSha256,
    );
  }

  /// Restores a pin from the output of [toJson]. Returns null, never throws,
  /// for anything that is not a valid pin (wrong types, unknown or `none`
  /// kind, a single-signer pin that does not have exactly one fingerprint, an
  /// invalid fingerprint), so a corrupt or foreign stored value is treated as
  /// "not pinned".
  static AppSignerPin? tryFromJson(Object? json) {
    try {
      if (json is! Map) {
        return null;
      }
      final kind = AppSigningCertKind.values.asNameMap()[json['kind']];
      final list = json['fingerprints'];
      if (kind == null || list is! List) {
        return null;
      }
      final strings = <String>[];
      for (final item in list) {
        if (item is! String) {
          return null;
        }
        strings.add(item);
      }
      if (kind == AppSigningCertKind.single && strings.length != 1) {
        return null;
      }
      return fromSigners(kind: kind, fingerprints: strings);
    } catch (_) {
      return null;
    }
  }

  /// Like [tryFromJson] for the JSON text produced by [toJsonString]; null for
  /// null or text that is not valid JSON.
  static AppSignerPin? tryParse(String? source) {
    if (source == null) {
      return null;
    }
    try {
      return tryFromJson(jsonDecode(source));
    } on FormatException {
      return null;
    }
  }

  /// [AppSigningCertKind.single] or [AppSigningCertKind.multiple], never
  /// [AppSigningCertKind.none].
  final AppSigningCertKind kind;

  /// For a single signer exactly one entry, the current certificate when
  /// pinned. For several signers every signer, sorted. Upper case colon
  /// separated hex.
  final List<String> fingerprints;

  /// The pinned current certificate of a single-signer app, otherwise null.
  String? get currentSha256 =>
      kind == AppSigningCertKind.single ? fingerprints.single : null;

  /// Compares what is pinned with the app in [current].
  ///
  /// [AppSignerMatch.unavailable] if [current] is not pinnable (see
  /// [fromMetadata]) or reports no fingerprints, otherwise as for
  /// [matchSigners].
  AppSignerMatch match(AutofillMetadata current) {
    if (!_pinnable(current)) {
      return AppSignerMatch.unavailable;
    }
    return matchSigners(
      kind: current.appSigningCertKind,
      fingerprints: current.appSigningCertSha256,
    );
  }

  /// Compares what is pinned with a signing identity given as [kind] and
  /// `appSigningCertSha256` [fingerprints] (lineage oldest first for a single
  /// signer, a set for several). It does no check that the request identifies
  /// an app: use [match] for that.
  ///
  /// [AppSignerMatch.unavailable] if [kind] is none, the list is empty or it
  /// contains an invalid fingerprint. [AppSignerMatch.mismatch] if the kinds
  /// differ. For a single signer: [AppSignerMatch.matches] if the pinned
  /// certificate is the last of the lineage, [AppSignerMatch.matchesRotated]
  /// if it is elsewhere in it, otherwise [AppSignerMatch.mismatch]. For several
  /// signers: [AppSignerMatch.matches] if the sets are equal, otherwise
  /// [AppSignerMatch.mismatch].
  AppSignerMatch matchSigners({
    required AppSigningCertKind kind,
    required Iterable<String> fingerprints,
  }) {
    final current = _normalizeAll(fingerprints);
    if (kind == AppSigningCertKind.none || current == null || current.isEmpty) {
      return AppSignerMatch.unavailable;
    }
    if (kind != this.kind) {
      return AppSignerMatch.mismatch;
    }
    switch (kind) {
      case AppSigningCertKind.none:
        return AppSignerMatch.unavailable;
      case AppSigningCertKind.single:
        final pinned = this.fingerprints.single;
        if (current.last == pinned) {
          return AppSignerMatch.matches;
        }
        return current.contains(pinned)
            ? AppSignerMatch.matchesRotated
            : AppSignerMatch.mismatch;
      case AppSigningCertKind.multiple:
        final pinned = this.fingerprints.toSet();
        final now = current.toSet();
        return pinned.length == now.length && pinned.containsAll(now)
            ? AppSignerMatch.matches
            : AppSignerMatch.mismatch;
    }
  }

  /// A JSON-encodable form of this pin, for storage. Restore with
  /// [tryFromJson].
  Map<String, Object?> toJson() => {
        'kind': kind.name,
        'fingerprints': fingerprints,
      };

  /// [toJson] as JSON text. Restore with [tryParse].
  String toJsonString() => jsonEncode(toJson());

  @override
  bool operator ==(Object other) =>
      other is AppSignerPin &&
      other.kind == kind &&
      listEquals(other.fingerprints, fingerprints);

  @override
  int get hashCode => Object.hash(kind, Object.hashAll(fingerprints));

  @override
  String toString() => 'AppSignerPin(${kind.name}, $fingerprints)';

  static bool _pinnable(AutofillMetadata metadata) =>
      metadata.appId != null &&
      metadata.appIdTrusted &&
      !metadata.appIsBrowser;

  /// Every entry formatted as upper case colon separated hex, or null if any is
  /// not a SHA-256 fingerprint.
  static List<String>? _normalizeAll(Iterable<String> fingerprints) {
    final result = <String>[];
    for (final fingerprint in fingerprints) {
      final normalized = DigitalAssetLinks.normalizeFingerprint(fingerprint);
      if (normalized == null) {
        return null;
      }
      result.add(_colonSeparated(normalized));
    }
    return result;
  }

  static String _colonSeparated(String normalized) => [
        for (var i = 0; i < normalized.length; i += 2)
          normalized.substring(i, i + 2)
      ].join(':');
}
