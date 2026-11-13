# Pocket libbox patch

Base: official SagerNet/sing-box v1.14.4,
commit `c005f35300ce76427dc285481ec5695f5b179cd2`.

Pocket modifies three upstream files (2026-10-29, 2026-11-13):

- `experimental/libbox/command_server.go`: removes the only invocation that
  persisted decrypted runtime configuration to `configuration.json`.
- `experimental/libbox/report.go`: makes `copyConfigSnapshot` a no-op, so crash,
  OOM and power reports cannot copy a previous plaintext configuration snapshot.
- `common/tls/reality_client.go`: the REALITY client no longer removes the
  X25519MLKEM768 key share. Current Xray REALITY servers accept only a hello with
  X25519MLKEM768 followed by X25519 (as Xray clients send) and treat others as
  probes, which upstream reports as `reality verification failed`. Fingerprints
  without that pair (Firefox 120, Safari, iOS, Edge, randomized in uTLS 1.8.7) use
  the Chrome fingerprint for REALITY, so the fingerprint named in such a profile is
  replaced for REALITY connections only. The hello also reports Xray client version
  26.7.11 instead of 1.8.1, for servers that set `minClientVer`. The TLS
  `fragment`/`record_fragment` options, which upstream applies to uTLS only, also
  split the REALITY hello, as providers' Xray configs ask (finalmask fragment).

The checked-in `libbox-pocket.patch` is part of the modified source
distribution. Upstream copyright and LICENSE are unchanged. The uncalled private
`saveConfigSnapshot` helper remains in upstream `log.go`; it has no callers in this
build. This patch does not remove existing files from an installation and does not
claim to redact arbitrary strings provided by callers as log/platform metadata.
Pocket must not pass secrets to those APIs.

`tools/Build-Libbox.ps1` verifies the base commit, pinned patch SHA256 and exact
tracked diff. It applies the patch only to a clean tree, accepts the same already
applied patch, rejects other tracked edits, and never runs reset/clean. Use
`-VerifySourceOnly` to check/apply the pinned patch without a native rebuild.
