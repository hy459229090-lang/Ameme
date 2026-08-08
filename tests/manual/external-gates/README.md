# External Gate execution templates

These files are blank, content-free schemas for evidence that cannot be produced by repository
automation. Copy them into `data/private/external-gates/<run-id>/` before use. That directory is
Git-ignored. Never fill the committed templates with participant, device-account, signing,
location, health, contact, database, media, prompt, or product-content data.

The execution authority and step order are defined in:

- `docs/research/MVP用户与真机验证执行包.md`
- `docs/release/封闭Beta外部门执行包.md`

Templates:

- `t0-participant-day-template.csv`: daily source/coverage and effort counts, no content.
- `t0-reuse-outcome-template.csv`: four-scenario reuse outcomes and guardrails, no query or IDs.
- `t0-d8-delete-recovery-template.csv`: D8 delete/recovery outcome matrix.
- `physical-device-run-template.csv`: physical-device, accessibility, lifecycle, source, storage,
  delete, recovery, LAN and signing evidence.
- `provider-cost-input-template.csv`: dated public/contract price inputs without account IDs.
- `external-gate-summary-template.json`: fail-closed gate ledger. Every gate starts `hold`.
- `signing-store-checklist-template.md`: human approval checklist; no credentials.

Hashes in evidence rows must be per-study/per-run salted irreversible hashes. Do not reuse product
object IDs, device serials, advertising IDs, participant contact details, signing account IDs, or
store account IDs as hashes. Raw screenshots, recordings, databases, logs, exports, provisioning
profiles and credentials remain outside Git.
