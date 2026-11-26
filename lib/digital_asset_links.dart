import 'dart:convert';

import 'flutter_autofill_service.dart';

/// Helpers for the Digital Asset Links (DAL) `get_login_creds` relation.
///
/// This class does no networking. You fetch
/// `https://<domain>/.well-known/assetlinks.json` yourself (see the "Checking
/// get_login_creds" section of the Readme for the rules to follow) and pass the
/// body to [grantsGetLoginCreds].
///
/// ## What it tells you
///
/// A site that lists your user's app in a statement like this
///
/// ```json
/// [{
///   "relation": ["delegate_permission/common.get_login_creds"],
///   "target": {
///     "namespace": "android_app",
///     "package_name": "com.example.app",
///     "sha256_cert_fingerprints": ["14:6D:E9:..."]
///   }
/// }]
/// ```
///
/// has declared that credentials for that site may be shared with that app,
/// as built by the holder of that signing key. That is a stronger and more
/// specific statement than `AutofillWebDomain.dalVerified`, which reflects
/// Android's App Links verification (`delegate_permission/common.handle_all_urls`,
/// "this app may open this site's links"). The two differ in several ways:
///
/// * `dalVerified` needs the app to declare the domain in an intent filter and
///   needs Android 12+; this check needs neither, so a site can be associated
///   with an app that does not use App Links. It is also available on Android
///   10 and 11 (API 29 and 30), where `dalVerified` cannot be computed.
///   However, on those versions `AutofillMetadata.webDomains` is emptied for
///   apps that are not browsers, so you cannot learn which domain the app is
///   showing: you would need to use a domain from your own saved entry and ask
///   whether the site grants the requesting app access to it.
/// * `handle_all_urls` says nothing about credentials, so it is deliberately
///   not accepted here: an app (or site) that merely handles links has not
///   agreed to receive logins. Only `get_login_creds` expresses that.
/// * This check says nothing about which domain the app is actually showing.
///   Combine it with what the request tells you (for example the focused web
///   domain) and with your own saved entry for the site.
///
/// Only trust the answer for an app whose identity is reliable: use it when
/// `AutofillMetadata.appIdTrusted` is true, `appIsBrowser` is false and
/// `appSigningCertSha256` is not empty. [grantsGetLoginCredsToApp] checks that
/// for you.
///
/// Which of the app's certificates are compared with the site's list depends
/// on `AutofillMetadata.appSigningCertKind`:
///
/// * [AppSigningCertKind.single]: only the app's CURRENT certificate (the
///   last of its key rotation lineage). Older certificates in the lineage are
///   ignored, so a site that still lists a retired (possibly leaked) key does
///   not approve an APK signed only by that old key.
/// * [AppSigningCertKind.multiple]: a single statement must list EVERY one of
///   the app's signers (it may list others too). Android treats the whole
///   signer set as the app's identity, so an APK that adds an attacker's own
///   key beside a listed signer does not match. Signers listed across
///   different statements do not combine. If any of the app's fingerprints is
///   invalid, nothing matches.
/// * [AppSigningCertKind.none]: never matches.
class DigitalAssetLinks {
  DigitalAssetLinks._();

  /// The relation that grants an app access to a site's credentials.
  static const getLoginCredsRelation =
      'delegate_permission/common.get_login_creds';

  /// Like [grantsGetLoginCreds] for the app described by a request's [metadata]:
  /// uses its `appId`, `appSigningCertSha256` and `appSigningCertKind`. False
  /// without looking at [assetLinks] if the request does not identify an app
  /// reliably: the app ID is not trusted (`appIdTrusted`), the app is a browser,
  /// or no signing certificates are known.
  static bool grantsGetLoginCredsToApp({
    required Object? assetLinks,
    required AutofillMetadata metadata,
  }) {
    final appId = metadata.appId;
    if (appId == null || !metadata.appIdTrusted || metadata.appIsBrowser) {
      return false;
    }
    return grantsGetLoginCreds(
      assetLinks: assetLinks,
      appId: appId,
      appSigningCertSha256: metadata.appSigningCertSha256,
      appSigningCertKind: metadata.appSigningCertKind,
    );
  }

  /// Returns true if [assetLinks] contains a statement granting
  /// [getLoginCredsRelation] to the Android app with package name [appId]
  /// signed by a certificate whose SHA-256 fingerprint is in
  /// [appSigningCertSha256].
  ///
  /// [appSigningCertKind] says how to read [appSigningCertSha256] (both are
  /// fields of `AutofillMetadata`) and which of its entries are compared with
  /// the statement: only the last (current) entry for
  /// [AppSigningCertKind.single], every entry (all listed by the same
  /// statement) for [AppSigningCertKind.multiple],
  /// and none (so the result is false) for [AppSigningCertKind.none]. See the
  /// class documentation for why.
  ///
  /// [assetLinks] is the body of an `assetlinks.json` file, either the raw text
  /// ([String]) or already decoded (a [List] as returned by `jsonDecode`).
  ///
  /// It never throws for bad input; anything it cannot understand means the
  /// statement does not match and, if nothing else matches, the result is
  /// false. Fingerprints are compared ignoring case and separators, so
  /// `14:6d:e9:...`, `146DE9...` and `14 6D E9 ...` are equal. A value that is
  /// not a SHA-256 fingerprint (64 hex digits) never matches. [appId] is
  /// compared exactly.
  ///
  /// Not supported, and treated as non-matching:
  ///
  /// * `include` statements (which point at another file) are not followed.
  ///   Following them needs more network requests, with their own risks, which
  ///   a caller can decide to take on by fetching the included file and calling
  ///   this again.
  /// * Statements whose target is not an `android_app` (for example `web`).
  /// * Any other relation, including `delegate_permission/common.handle_all_urls`.
  static bool grantsGetLoginCreds({
    required Object? assetLinks,
    required String appId,
    required Iterable<String> appSigningCertSha256,
    required AppSigningCertKind appSigningCertKind,
  }) {
    try {
      if (appId.isEmpty || appSigningCertKind == AppSigningCertKind.none) {
        return false;
      }
      final candidates = appSigningCertSha256.toList();
      final wanted = <String>{};
      if (appSigningCertKind == AppSigningCertKind.single) {
        // Only the current certificate: the last entry. If it is not a valid
        // fingerprint do not fall back to an older one.
        final normalized =
            candidates.isEmpty ? null : normalizeFingerprint(candidates.last);
        if (normalized != null) {
          wanted.add(normalized);
        }
      } else {
        // Every signer is required, so one that is not a valid fingerprint
        // means the app's identity is unknown: fail closed.
        for (final fingerprint in candidates) {
          final normalized = normalizeFingerprint(fingerprint);
          if (normalized == null) {
            return false;
          }
          wanted.add(normalized);
        }
      }
      if (wanted.isEmpty) {
        return false;
      }
      final statements = _decode(assetLinks);
      if (statements is! List) {
        return false;
      }
      for (final statement in statements) {
        if (_statementGrants(statement, appId, wanted)) {
          return true;
        }
      }
      return false;
    } catch (_) {
      // Nothing in here should throw, but "false" is always the safe answer.
      return false;
    }
  }

  /// Returns [fingerprint] as upper case hex without separators, or null if it
  /// is not a SHA-256 fingerprint (exactly 64 hex digits once colons, spaces,
  /// dashes and similar separators are ignored).
  static String? normalizeFingerprint(String fingerprint) {
    final stripped = fingerprint.replaceAll(RegExp(r'[\s:\-]'), '');
    if (!RegExp(r'^[0-9A-Fa-f]{64}$').hasMatch(stripped)) {
      return null;
    }
    return stripped.toUpperCase();
  }

  static Object? _decode(Object? assetLinks) {
    if (assetLinks is String) {
      try {
        // A UTF-8 byte order mark is legal at the start of JSON served by some hosts.
        final text = assetLinks.startsWith('\uFEFF')
            ? assetLinks.substring(1)
            : assetLinks;
        return jsonDecode(text);
      } on FormatException {
        return null;
      }
    }
    return assetLinks;
  }

  static bool _statementGrants(
      Object? statement, String appId, Set<String> wanted) {
    if (statement is! Map) {
      // Also covers entries that are not objects at all.
      return false;
    }
    final relation = statement['relation'];
    if (relation is! List || !relation.contains(getLoginCredsRelation)) {
      return false;
    }
    final target = statement['target'];
    if (target is! Map ||
        target['namespace'] != 'android_app' ||
        target['package_name'] != appId) {
      return false;
    }
    final fingerprints = target['sha256_cert_fingerprints'];
    if (fingerprints is! List) {
      return false;
    }
    final listed = <String>{};
    for (final fingerprint in fingerprints) {
      if (fingerprint is String) {
        final normalized = normalizeFingerprint(fingerprint);
        if (normalized != null) {
          listed.add(normalized);
        }
      }
    }
    // Every wanted certificate must be listed by this one statement: the one
    // current certificate of a single-signer app, or every signer of a
    // multi-signer app (whose identity is the whole set, as Android treats it).
    return listed.containsAll(wanted);
  }
}
