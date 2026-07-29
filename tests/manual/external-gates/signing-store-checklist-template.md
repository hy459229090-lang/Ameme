# Signing and store checklist

Copy this file into the private external-gate run directory. Record only role, completion time,
ticket/evidence hash and verdict. Never paste credentials, recovery codes, certificates,
provisioning profiles, account emails, team IDs, store account IDs or personal contact details.

- [ ] Release owner assigned and independent from sole implementer.
- [ ] Apple Developer membership and App Store Connect/TestFlight authority confirmed.
- [ ] iOS distribution certificate/profile and App Group entitlements verified on a physical device.
- [ ] Google Play Console internal-testing authority confirmed.
- [ ] Android upload/app-signing separation and protected key custody reviewed.
- [ ] Signed iOS and Android artifacts bind the reviewed commit/build identifier.
- [ ] iOS privacy manifest/nutrition labels match runtime network and data behavior.
- [ ] Google Play Data safety/permissions declarations match runtime behavior.
- [ ] Third-party SDK/provider allow-list, terms, retention and deletion reviewed.
- [ ] Target-market privacy/security/legal owner approved the closed test scope.
- [ ] Support contact, incident owner, stop authority and rollback owner are staffed.
- [ ] TestFlight/Play internal allow-list contains only consented participants.
- [ ] Rollback/stop rehearsal completed against the signed candidate.
- [ ] No credential, participant content, database, media, log or provisioning artifact entered Git.

Final verdict: `hold` until every item has real evidence and the required owners sign.
