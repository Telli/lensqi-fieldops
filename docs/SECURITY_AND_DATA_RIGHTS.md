# Public Source, Privacy, and Data Rights Checklist

**DO NOT copy the private LensIQ `.git` directory, production configuration, archived photos, Google credentials, stored customer information, or old build files into the hackathon repository.**

- [ ] Fresh `git init` and independent public repository (not a public fork or mirror).
- [ ] Review pre-event source license and personal/employer IP rights before any permitted copying.
- [ ] Obtain written organizer clarification for indie-track prior-code reuse; if unanswered, no code copied.
- [ ] Inspect all files and history with a secrets scanner; make sure no real passwords, provider keys, database credentials or private network information are present.
- [ ] Keep Meta API key only server-side. Add `.env` and device keystore files to `.gitignore`; publish `.env.example` with placeholders.
- [ ] Check original media and copyright rights; prefer synthetic fixture imagery shot or generated specifically for the entry with appropriate rights.
- [ ] Remove faces, badges, serial numbers linked to real customers, private MAC/IP ranges from screenshots or logs.
- [ ] Protect `/v1/diagnostics` with fixture-owned allowlist; no user/model-supplied arbitrary URLs; prevent SSRF and proxy/redirect escapes.
- [ ] Distinguish recorded/simulated telemetry from live local measurements in logs, UI and receipt.
- [ ] Store images only as needed; user-controlled deletion; demo retention policy documented.
- [ ] Publish license only after confirming it doesn't purport to relicense private/unowned code.
- [ ] Build from a clean checkout without credentials other than separately obtained hackathon API key.

Existing private LensIQ remediation notes state external credential rotation was skipped. Treat this as a prompt for local audit, not a claim that secrets have been exposed.
