# Pocket libbox patch

Base: official SagerNet/sing-box v1.14.4,
commit `c005f35300ce76427dc285481ec5695f5b179cd2`.

Pocket modifies two upstream files on 2026-10-29:

- `experimental/libbox/command_server.go`: removes the only invocation that
  persisted decrypted runtime configuration to `configuration.json`.
- `experimental/libbox/report.go`: makes `copyConfigSnapshot` a no-op, so crash,
  OOM and power reports cannot copy a previous plaintext configuration snapshot.

The checked-in `libbox-no-config-snapshots.patch` is part of the modified source
distribution. Upstream copyright and LICENSE are unchanged. The uncalled private
`saveConfigSnapshot` helper remains in upstream `log.go`; it has no callers in this
build. This patch does not remove existing files from an installation and does not
claim to redact arbitrary strings provided by callers as log/platform metadata.
Pocket must not pass secrets to those APIs.

`tools/Build-Libbox.ps1` verifies the base commit, pinned patch SHA256 and exact
tracked diff. It applies the patch only to a clean tree, accepts the same already
applied patch, rejects other tracked edits, and never runs reset/clean. Use
`-VerifySourceOnly` to check/apply the pinned patch without a native rebuild.
