# Security policy

## Supported versions

dispatch has no releases yet (the build is `0.1.0-SNAPSHOT`). Fixes land on `main` only, so the
latest commit on `main` is the only supported version.

## Reporting a vulnerability

Please do not open a public issue or pull request for a vulnerability. Report it privately through
GitHub's private vulnerability reporting instead:
[open a draft security advisory](https://github.com/maltemindedal/dispatch/security/advisories/new)
(the repository's **Security** tab, then **Report a vulnerability**).

A useful report says:

- what is affected: the module, endpoint, configuration key, or class;
- how to reproduce it, ideally as a request sequence or a failing test;
- the commit you tested against, and the profile (`dev` or `postgres`);
- what an attacker gains: data read, jobs changed, a process taken down, code run.

The discussion stays on the private advisory until a fix is on `main`; then the advisory is
published with credit to the reporter, unless you would rather not be named.

## Scope

Some behaviour that looks like a vulnerability is a documented limitation, listed in
[known limitations](docs/architecture/limitations.md). These are known and not in scope as reports
on their own:

- **The API has no authentication** and listens on all interfaces. Anyone who can reach the port
  can submit, read, cancel, and retry jobs, and read every payload and `lastError`. Run it on a
  trusted network or behind a reverse proxy that authenticates.
- **Delivery is at-least-once.** A job can run more than once (for example when a handler outruns
  its visibility timeout), so handlers must be idempotent. See
  [reliability mechanics](docs/architecture/reliability.md#delivery-is-at-least-once).
- **`GET /jobs` is not bounded by payload size.** It returns up to 1000 rows with their full
  payloads.
- **The H2 web console runs SQL and arbitrary code** when the `dev` profile is started with
  `DISPATCH_H2_CONSOLE=true`. That is why it is off by default and meant for local use only.

A way around one of the safeguards that *is* in place is in scope. For example:

- the H2 console being served without `DISPATCH_H2_CONSOLE=true`, or to a peer that is not on the
  local machine;
- a `POST /jobs` body getting past `dispatch.max-payload-bytes` or the 64-level nesting limit by
  more than the parser's read buffer (see [configuration](docs/reference/configuration.md));
- a worker that lost its lease still recording a job's outcome;
- SQL injection, or any other way to make the stores run SQL they were not written to run;
- a vulnerable dependency that the application actually reaches. Dependabot already proposes
  routine version updates (see [CONTRIBUTING.md](CONTRIBUTING.md#dependency-updates)), so a report
  is worth sending when the vulnerable code path is reachable through dispatch.
