import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_autofill_service/flutter_autofill_service.dart';

/// The outcome of fetching a domain's assetlinks.json.
class AssetLinksResult {
  const AssetLinksResult.success(this.body, {this.fromCache = false})
      : error = null;
  const AssetLinksResult.failure(this.error, {this.fromCache = false})
      : body = null;

  /// The raw, valid JSON text, or null on failure.
  final String? body;

  /// Why nothing usable was obtained, or null on success.
  final String? error;
  final bool fromCache;

  bool get ok => body != null;
}

class _CacheEntry {
  _CacheEntry(this.result, this.expires);
  final AssetLinksResult result;
  final DateTime expires;
}

/// Fetches `https://<domain>/.well-known/assetlinks.json` following the rules
/// in Google's Digital Asset Links specification, for use with
/// [DigitalAssetLinks.grantsGetLoginCreds]. The library does no networking
/// itself so that you control it, and this is a reference for doing it safely:
///
/// * HTTPS only, and only ever to the well known path on the host given.
/// * Redirects are NOT followed. The spec requires the file to be served
///   directly, and a redirect lets one host speak for another.
/// * The response must be 200 with a JSON content type and body.
/// * A short timeout, and a cap on the body size (measured after
///   decompression), because the host is controlled by whoever wrote the app.
/// * Results are cached in memory, so every autofill request does not become a
///   web request: a TTL for successes, and a shorter one for failures so that a
///   broken or hostile host cannot make us hammer it either. A real app might
///   also persist the cache and honour the server's `Cache-Control`.
///
/// Note the domain you check comes from somewhere an attacker might influence
/// (an app's claimed web domain), so it is validated to be a plain public-style
/// host name first: no scheme, port, path, credentials or IP address.
class AssetLinksClient {
  AssetLinksClient({
    this.timeout = const Duration(seconds: 5),
    this.maxBytes = 128 * 1024,
    this.ttl = const Duration(hours: 1),
    this.failureTtl = const Duration(minutes: 5),
  });

  final Duration timeout;
  final int maxBytes;
  final Duration ttl;
  final Duration failureTtl;
  final Map<String, _CacheEntry> _cache = {};

  static final _hostName = RegExp(
      r'^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$');

  /// Returns [domain] lower cased if it is acceptable, otherwise null.
  static String? validDomain(String domain) {
    final d = domain.trim().toLowerCase();
    if (d.length > 253 || !_hostName.hasMatch(d)) {
      return null;
    }
    // A dotted all-number name is an IPv4 address: never fetch from those.
    if (RegExp(r'^[0-9.]+$').hasMatch(d)) {
      return null;
    }
    return d;
  }

  Future<AssetLinksResult> fetch(String domain) async {
    final host = validDomain(domain);
    if (host == null) {
      return const AssetLinksResult.failure('Not a valid domain name');
    }
    final cached = _cache[host];
    if (cached != null && cached.expires.isAfter(DateTime.now())) {
      return cached.result.ok
          ? AssetLinksResult.success(cached.result.body!, fromCache: true)
          : AssetLinksResult.failure(cached.result.error, fromCache: true);
    }
    final result = await _download(host);
    _cache[host] = _CacheEntry(
        result, DateTime.now().add(result.ok ? ttl : failureTtl));
    return result;
  }

  Future<AssetLinksResult> _download(String host) async {
    final uri = Uri(
        scheme: 'https', host: host, path: '/.well-known/assetlinks.json');
    final client = HttpClient()
      ..connectionTimeout = timeout
      ..userAgent = 'flutter_autofill_service_example';
    try {
      // One overall deadline, which also covers a server that dribbles bytes.
      return await _get(client, uri).timeout(timeout);
    } on TimeoutException {
      return const AssetLinksResult.failure('Timed out');
    } catch (e) {
      // Includes TLS failures: a bad certificate is never ignored.
      return AssetLinksResult.failure('Request failed: ${e.runtimeType}');
    } finally {
      client.close(force: true);
    }
  }

  Future<AssetLinksResult> _get(HttpClient client, Uri uri) async {
    final request = await client.getUrl(uri);
    request.followRedirects = false;
    request.headers.set(HttpHeaders.acceptHeader, 'application/json');
    final response = await request.close();
    if (response.statusCode != HttpStatus.ok) {
      await response.drain<void>();
      return AssetLinksResult.failure(response.isRedirect
          ? 'Redirects are not allowed (${response.statusCode})'
          : 'HTTP ${response.statusCode}, expected 200');
    }
    if (response.headers.contentType?.mimeType != 'application/json') {
      return const AssetLinksResult.failure(
          'Content-Type is not application/json');
    }
    if (response.contentLength > maxBytes) {
      return const AssetLinksResult.failure('Response too large');
    }
    final bytes = BytesBuilder(copy: false);
    await for (final chunk in response) {
      bytes.add(chunk);
      if (bytes.length > maxBytes) {
        return const AssetLinksResult.failure('Response too large');
      }
    }
    final String text;
    try {
      text = utf8.decode(bytes.takeBytes());
      jsonDecode(text);
    } on FormatException {
      return const AssetLinksResult.failure('Body is not valid JSON');
    }
    return AssetLinksResult.success(text);
  }
}

/// Shared so the cache survives between widgets.
final assetLinksClient = AssetLinksClient();

/// Demonstrates the `get_login_creds` check next to `dalVerified`.
///
/// Normally the domain comes from the web domain the request is about
/// (`focusedWebDomain`), or, where `webDomains` is empty (Android 10 and 11
/// for apps that are not browsers), from the domain on the user's saved entry
/// for the requesting app. The demo has no entries so the user types a domain.
class LoginCredsCheckCard extends StatefulWidget {
  const LoginCredsCheckCard({Key? key, required this.metadata})
      : super(key: key);

  final AutofillMetadata? metadata;

  @override
  State<LoginCredsCheckCard> createState() => _LoginCredsCheckCardState();
}

class _LoginCredsCheckCardState extends State<LoginCredsCheckCard> {
  late final TextEditingController _domain = TextEditingController(
      text: widget.metadata?.focusedWebDomain?.domain ?? '');
  bool _busy = false;
  String? _checkedDomain;
  AssetLinksResult? _fetched;
  bool? _grants;

  @override
  void dispose() {
    _domain.dispose();
    super.dispose();
  }

  /// Why the check is not meaningful for this request, or null if it is.
  String? get _notApplicableReason {
    final m = widget.metadata;
    if (m == null || m.appId == null) {
      return 'No app was identified.';
    }
    if (!m.appIdTrusted) {
      return 'The app ID is not trusted (${m.appIdSource.name}).';
    }
    if (m.appIsBrowser) {
      return 'A browser is not an app identity.';
    }
    if (m.appSigningCertSha256.isEmpty) {
      return 'No signing certificates (QUERY_ALL_PACKAGES missing?).';
    }
    return null;
  }

  Future<void> _check() async {
    final m = widget.metadata!;
    setState(() => _busy = true);
    final domain = _domain.text.trim().toLowerCase();
    final fetched = await assetLinksClient.fetch(domain);
    final grants = fetched.ok &&
        DigitalAssetLinks.grantsGetLoginCredsToApp(
            assetLinks: fetched.body, metadata: m);
    if (mounted) {
      setState(() {
        _busy = false;
        _checkedDomain = domain;
        _fetched = fetched;
        _grants = grants;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final reason = _notApplicableReason;
    final dalVerified = _checkedDomain == null
        ? null
        : widget.metadata?.webDomains
            .where((d) => d.domain.toLowerCase() == _checkedDomain)
            .map((d) => d.dalVerified)
            .toList();
    return Card(
      key: const Key('loginCredsCard'),
      margin: const EdgeInsets.all(8),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Site grants this app login credentials?',
                style: Theme.of(context).textTheme.titleSmall),
            const SizedBox(height: 4),
            if (reason != null)
              Text('Not available: $reason')
            else ...[
              TextField(
                key: const Key('loginCredsDomain'),
                controller: _domain,
                decoration: const InputDecoration(
                    labelText: 'Domain (from your saved entry)',
                    helperText: 'Fetches https://<domain>/.well-known/'
                        'assetlinks.json'),
                onChanged: (_) => setState(() {
                  _checkedDomain = null;
                  _fetched = null;
                  _grants = null;
                }),
              ),
              Align(
                alignment: Alignment.centerLeft,
                child: TextButton(
                  onPressed: _busy || _domain.text.trim().isEmpty
                      ? null
                      : _check,
                  child: Text(_busy ? 'Checking...' : 'Check get_login_creds'),
                ),
              ),
              if (_fetched != null) ...[
                Text(_fetched!.ok
                    ? 'get_login_creds: ${_grants! ? 'GRANTED' : 'not granted'}'
                        '${_fetched!.fromCache ? ' (cached)' : ''}'
                    : 'get_login_creds: could not check: ${_fetched!.error}'),
                Text(dalVerified == null || dalVerified.isEmpty
                    ? 'dalVerified: n/a (domain is not in webDomains)'
                    : 'dalVerified (handle_all_urls): ${dalVerified.first}'),
                const Text(
                    'They are independent: the site may grant credentials '
                    'without App Links, and App Links do not imply credentials.',
                    style: TextStyle(fontSize: 11)),
              ],
            ],
          ],
        ),
      ),
    );
  }
}
