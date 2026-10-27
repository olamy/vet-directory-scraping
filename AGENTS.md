# AGENTS.md

Guidance for Claude, Copilot, Codex, OpenCode, and other coding agents working in this repository.

## Project overview

This is a Java 21 JBang CLI that builds a clean database of Australian veterinary clinics from several online directories. The entry point is `VetDb.java`; implementation code lives under `src/vetdb/`; tests and fixtures live under `tests/`.

## Required tools

- Java 21 or newer
- JBang

Run commands from the repository root so relative paths such as `cache/`, `out/`, and `tests/fixtures/` resolve correctly.

## Common commands

```bash
# Run all tests
jbang tests/VetDbTests.java

# Show CLI help
jbang VetDb.java --help

# Re-parse cached source pages without network access
jbang VetDb.java parse

# Export from the current database
jbang VetDb.java export

# Small smoke run; may use the network
jbang VetDb.java all --limit 25
```

Prefer `jbang tests/VetDbTests.java` before handing work back. If a change only affects parsing logic, add or update fixture-based tests and run the full test launcher.

## Repository structure

- `VetDb.java` - JBang script, CLI commands, dependency declarations, and top-level orchestration.
- `src/vetdb/source/` - scraper/discovery/parser implementations for each directory.
- `src/vetdb/clean/` - normalization and cleanup rules.
- `src/vetdb/merge/` - matching, deduplication, and verification logic.
- `src/vetdb/enrich/` - ABR and Google Places enrichment clients.
- `src/vetdb/export/` - CSV/XLSX export code.
- `src/vetdb/store/` - SQLite persistence.
- `tests/vetdb/` - JUnit tests.
- `tests/fixtures/` - static HTML/JSON fixtures used by tests.
- `cache/`, `out/`, and `result-*` - generated or captured runtime data; do not modify unless explicitly requested.

## Coding conventions

- Keep code simple, explicit Java; avoid adding new build systems or frameworks.
- Keep dependencies in the JBang `//DEPS` block in `VetDb.java` unless there is a strong reason to restructure the project.
- Preserve the existing package layout and style: 4-space indentation, small helper methods, and clear domain names.
- Source parsers should expose deterministic parse helpers where practical, so tests can run from fixtures without network access.
- Normalize fields through existing model/normalization helpers rather than duplicating cleanup logic in each parser.
- Prefer deterministic ordering (`List`, `LinkedHashMap`, `LinkedHashSet`, sorted output where applicable) for repeatable exports and tests.

## Network, cost, and data safety

- Do not run full `fetch`, `all`, `enrich-abr`, or `enrich-google` unless the user explicitly asks. These may be slow, hit external services, or cost money.
- Never expose, log unnecessarily, or commit `ABR_GUID`, `GOOGLE_PLACES_API_KEY`, or other credentials.
- Respect the existing cache and robots/politeness behavior. Do not bypass request delays or blocked paths.
- Prefer `parse` and fixture-based tests while developing; they avoid network access.
- Treat `out/`, `cache/`, `result-*`, `.sqlite`, `.csv`, and `.xlsx` outputs as generated data unless the user asks to inspect or update them.

## Testing guidance

- Add tests for parser, normalizer, merger, enrichment, or export behavior near the relevant package under `tests/vetdb/`.
- Add representative fixtures under `tests/fixtures/<source>/` for parser changes.
- Keep tests deterministic and offline.
- If tests cannot be run, state why and provide the exact command that should be run later.

## Before finishing

- Check `git status --short` and mention changed files.
- Report which tests or commands were run.
- Call out any network access, generated data changes, or commands intentionally skipped.
