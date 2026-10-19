# vet-directory-scraping

A JBang tool that builds one clean database of Australian vet clinics and hospitals from four online directories:

| Source | What it brings |
|---|---|
| [RateMyVet](https://ratemyvet.com.au/) (reference list, ~3,900 clinics) | Address, phone, website, Google rating and review count, species, after-hours, home visits |
| [BestForPet](https://bestforpet.com.au/find-a-vet/) (~210 clinics) | Email, opening hours, team size, services, payment plans, wellness plan, online booking |
| [PIA Vet Directory](https://www.piavetdirectory.com.au/) (~2,200 listings, mostly 2016 data) | Name, phone, website, address, mobile vet flag |
| [vet.net.au](https://www.vet.net.au/) (~1,100 clinics) | Opening hours, staff list, Facebook/Instagram, pet services |

Optionally it adds [ABN Lookup](https://abr.business.gov.au/) data (ABN, registered name, ABN status and date) and [Google Places](https://developers.google.com/maps/documentation/places/web-service/text-search) data (Maps link, rating, review count, closed check).

## Requirements

- Java 21 or newer
- [JBang](https://www.jbang.dev/) (`sdk install jbang`)

Run every command from the project folder: the default paths (`cache/`, `out/`) are relative to the current directory. The first run downloads the dependencies and compiles the code, which takes a minute; later runs start straight away.

## Running it

### Quick start

```bash
jbang VetDb.java all --limit 25      # try it: 25 listings per source, a few minutes
jbang VetDb.java all                 # the real thing: every listing, then enrich and export
```

`VetDb.java` is also an executable script on Linux and macOS, so `./VetDb.java all` works the same (run `chmod +x VetDb.java` once).

Every command and subcommand accepts `-h`/`--help` and `-V`/`--version`:

```bash
jbang VetDb.java --help
jbang VetDb.java fetch --help
```

### Commands

| Command | What it does | Network |
|---|---|---|
| `fetch` | Finds every listing on the selected sites, downloads the pages (cached), cleans them and stores them in the database | Yes, the directory sites |
| `parse` | Same as `fetch` but reads only from the cache, never the network. Use it after changing a parser | No |
| `enrich-abr` | Looks each clinic up in ABN Lookup | Yes, abr.business.gov.au |
| `enrich-google` | Looks each clinic up in Google Places (paid) | Yes, places.googleapis.com |
| `merge` | Dedupes the stored records into clinics and prints a summary | No |
| `export` | Runs `merge`, then writes the CSV files and `clinics.xlsx` | No |
| `all` | `fetch`, `enrich-abr`, `enrich-google`, `export`, in that order | Yes |

The usual order is `fetch` → `enrich-abr` → `enrich-google` → `export`. Each step reads what the previous ones stored in the SQLite database, so you can run them one at a time, stop, and carry on later.

### Options used by several commands

| Option | Default | Used by | Meaning |
|---|---|---|---|
| `--db=<file>` | `out/vetdb.sqlite` | every command | SQLite database holding the records, the clinics and the lookups |
| `--cache=<folder>` | `cache` | `fetch`, `parse`, `enrich-abr`, `enrich-google`, `all` | Where the HTTP responses are kept (one gzipped file per URL) |
| `--user-agent=<text>` | `Mozilla/5.0 (X11; Linux x86_64) vet-directory-builder/1.0` | `fetch`, `enrich-abr`, `enrich-google`, `all` | User-Agent header sent with every request |
| `--out=<folder>` | `out` | `export`, `all` | Where the CSV and XLSX files are written. Existing files are overwritten |
| `--source=<name>[,<name>...]` | `all` | `fetch`, `parse`, `all` | Which sites to scrape: `all`, or any of `ratemyvet`, `bestforpet`, `pia`, `vetnet`, comma separated |
| `--limit=<n>` | `0` (no limit) | `fetch`, `parse`, `all` | Only the first N listings of each selected site |

### `fetch`

```text
jbang VetDb.java fetch [--source=<name>[,<name>...]] [--limit=<n>] [--refresh]
                       [--db=<file>] [--cache=<folder>] [--user-agent=<text>]
```

| Option | Meaning |
|---|---|
| `--source` | Sites to scrape (default `all`). The selected sites run in parallel, one thread each, since they are different hosts |
| `--limit` | Only the first N listings per site (default `0`, all of them) |
| `--refresh` | Ignore the cache and download every page again, overwriting the cached copy |
| `--db`, `--cache`, `--user-agent` | See the shared options above |

- Pages already in the cache are not downloaded again, so an interrupted fetch simply carries on where it stopped when you run it again. Stopping with Ctrl+C is safe: cache files are written to a temporary name first, so a half-written page is never kept.
- The listing pages (sitemaps, REST lists) are cached too, so a plain `fetch` won't see clinics added to a site since the first run. Use `--refresh` to pick those up.
- Successful answers and permanent misses (404/410) are cached. Failures (timeouts, 5xx after 4 attempts) are not, so they are retried on the next run.
- For each site, the records from this run **replace** that site's records in the database. So `fetch --limit 25` leaves only 25 records for that site; run a full `fetch` (or `parse`, which is quick when everything is cached) to get them all back.
- If a site returns nothing at all (site down, blocked), its existing records are kept.

### `parse`

```text
jbang VetDb.java parse [--source=<name>[,<name>...]] [--limit=<n>] [--db=<file>] [--cache=<folder>]
```

It works like `fetch` but never touches the network: it re-reads the cached pages, parses and cleans them again, and replaces the records in the database. Pages missing from the cache are reported as `could not get ...` and skipped. Use it after changing a parser or a cleaning rule; for all sites it takes a minute or two.

### `enrich-abr`

```text
ABR_GUID=<guid> jbang VetDb.java enrich-abr [--limit=<n>] [--refresh] [--db=<file>] [--cache=<folder>] [--user-agent=<text>]
```

| Option | Meaning |
|---|---|
| `--limit` | Look up at most N clinics in this run (default `0`, all of them) |
| `--refresh` | Look up again the clinics already done, ignoring the cached answers |
| `--db`, `--cache`, `--user-agent` | See the shared options above |

- Needs `ABR_GUID`: register for free at <https://abr.business.gov.au/Tools/WebServices>. Without it the step prints a message and does nothing.
- Each clinic takes two calls (name search, then ABN details), with 0.5 s between calls.
- Rejected clinics and clinics already looked up are skipped, so you can run it in several goes with `--limit`.
- The results (`MATCHED`, `AMBIGUOUS`, `NOT_FOUND`) go in the `enrichment` table and show up in the clinics at the next `merge` or `export`. Errors are not stored, so they are retried next time.
- A GUID that ABR refuses stops the run with an error.

### `enrich-google`

```text
GOOGLE_PLACES_API_KEY=<key> jbang VetDb.java enrich-google [--budget=<n>] [--refresh] [--db=<file>] [--cache=<folder>] [--user-agent=<text>]
```

| Option | Meaning |
|---|---|
| `--budget` | Stop after N paid calls (default `100`). Answers coming from the cache don't count |
| `--refresh` | Look up again the clinics already done, ignoring the cache. This costs money again |
| `--db`, `--cache`, `--user-agent` | See the shared options above |

- Needs `GOOGLE_PLACES_API_KEY`, the key of a Google Cloud project with billing turned on. Without it the step prints a message and does nothing.
- One Text Search call per clinic. Asking for rating, review count and website puts each call in the Enterprise price tier, so check the current Google pricing before a full pass (about one call per clinic, so several thousand calls).
- Rejected clinics and clinics already looked up (matched or not found) are skipped. Raise `--budget` and run again to continue.
- A matched place adds `google_maps_url`, `google_rating`, `google_review_count` and `google_business_status`, and fills `phone` and `website` when no directory had them. `CLOSED_PERMANENTLY` clinics are rejected at the next `export`.

### `merge`

```text
jbang VetDb.java merge [--db=<file>]
```

Dedupes the stored records into clinics, applies the ABR and Google results, rewrites the `clinic` and `clinic_member` tables and prints a summary: records per source, clinics by verification status, clinics by number of sources, reasons for rejection, and the most common flags. It writes no files.

### `export`

```text
jbang VetDb.java export [--db=<file>] [--out=<folder>]
```

Runs `merge`, then writes `clinics.csv`, `needs_review.csv`, `rejected.csv`, `source_records.csv` and `clinics.xlsx` to the `--out` folder (default `out`), replacing the previous files.

### `all`

```text
jbang VetDb.java all [--source=<name>[,<name>...]] [--limit=<n>] [--budget=<n>]
                     [--db=<file>] [--cache=<folder>] [--user-agent=<text>] [--out=<folder>]
```

Runs `fetch` (with `--source` and `--limit`), then `enrich-abr` on every clinic not done yet, then `enrich-google` up to `--budget` paid calls (default `100`), then `export`. The enrichment steps are skipped when their environment variable is missing. It doesn't have `--refresh`; use `fetch --refresh` for that.

### Environment variables

| Variable | Used by | Meaning |
|---|---|---|
| `ABR_GUID` | `enrich-abr`, `all` | GUID for the ABN Lookup web services (free) |
| `GOOGLE_PLACES_API_KEY` | `enrich-google`, `all` | Google Places API (New) key (paid) |

### Exit codes

| Code | Meaning |
|---|---|
| `0` | Done (also when an enrichment step was skipped for lack of a key) |
| `1` | The command failed with an error (for example the database can't be opened, or ABR refused the GUID) |
| `2` | Bad command line: unknown option, or a `--source` name that doesn't exist |

### Speed and politeness

Each site gets its own minimum gap between two requests. The sites run in parallel, so the first full `fetch` takes about as long as the slowest one, RateMyVet: around 1.5 to 2 hours.

| Site | Gap between requests | Requests for a full run |
|---|---|---|
| ratemyvet.com.au | 1.5 s | ~3,940 pages + 2 sitemaps |
| bestforpet.com.au | 2.5 s (Wordfence protected) | ~210 pages + ~210 store-search calls + 4 REST pages |
| www.piavetdirectory.com.au | 1 s | ~2,230 pages + 23 REST pages |
| www.vet.net.au | 1 s | ~1,120 pages + 1 sitemap |
| abr.business.gov.au | 0.5 s | 2 per clinic |
| places.googleapis.com | 0.1 s | 1 per clinic |

`robots.txt` is respected: RateMyVet's `/search` and `/search-index.json` are never requested. 429 and 5xx answers are retried up to 4 times, honouring `Retry-After`.

### Common recipes

```bash
# Resume a fetch that was interrupted (cached pages are skipped)
jbang VetDb.java fetch

# Refresh a single site, for example once a month
jbang VetDb.java fetch --source ratemyvet --refresh

# After changing a parser or a cleaning rule: re-parse from the cache, then export
jbang VetDb.java parse
jbang VetDb.java export

# Try the ABR lookup on 50 clinics first, then do the rest
export ABR_GUID=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
jbang VetDb.java enrich-abr --limit 50
jbang VetDb.java enrich-abr

# Google in steps of 500 paid calls, then rebuild the files
export GOOGLE_PLACES_API_KEY=...
jbang VetDb.java enrich-google --budget 500
jbang VetDb.java export

# Keep a separate data set (another database, cache and output folder)
jbang VetDb.java all --db data/test.sqlite --cache data/cache --out data/out --limit 50

# Only two sites
jbang VetDb.java fetch --source bestforpet,vetnet
```

## Output (`out/`)

| File | Content |
|---|---|
| `vetdb.sqlite` | `source_record` (every listing, cleaned, with the raw values in `raw_json`), `clinic` (merged), `clinic_member` (which listing went where and why), `enrichment` |
| `clinics.csv` | Every clinic that wasn't rejected, with a `verification_status` column |
| `needs_review.csv` | Clinics worth a human look, with the reason |
| `rejected.csv` | Closed, not a vet, or not in Australia, with the reason |
| `source_records.csv` | The cleaned listings, one row per source listing |
| `clinics.xlsx` | The same four tables as sheets |

The CSV files start with a UTF-8 BOM so Excel shows accents correctly.

### Columns

`clinic_id, name, category, street, suburb, state, postcode, latitude, longitude, website, phone, email, facebook, instagram, other_socials, species_treated, home_mobile_visits, services, opening_hours, google_review_count, google_rating, directory_rating, after_hours_emergency, number_of_vets, number_of_staff, book_online, booking_url, telehealth, parasite_pilot, payment_options, digital_payment, payment_plan, wellness_plan, abn, registered_name, abn_status, abn_registered_from, google_maps_url, google_business_status, ratemyvet_url, bestforpet_url, pia_url, vetnet_url, sources, verification_status, flags`

A blank value means no source gave it; nothing is guessed. None of the four directories publishes telehealth, parasite pilot participation or an established date, so those stay blank. `abn_registered_from` is the date the ABN became active, not necessarily the year the clinic opened.

Y/N columns are `Y` when any source says yes, `N` only when a source explicitly says no, and blank otherwise.

## Cleaning rules

- **Phone**: Australian national format, `(02) 9953 1264`, `0412 345 678`, `1300 123 456`. An 8 digit landline gets the area code of its state, flagged `PHONE_AREA_CODE_ADDED`.
- **State**: `NSW VIC QLD SA WA TAS NT ACT`.
- **Postcode**: always 4 digits (`800` becomes `0800`), checked against the state's ranges.
- **Street**: Australia Post abbreviations (`Rd`, `St`, `Ave`, `Hwy`...); directions like "Parking entrance via ..." are dropped.
- **Text**: HTML entities decoded, broken encodings fixed (`â€™` becomes `'`), invisible characters removed, ALL CAPS names re-cased.
- **URLs**: tracking parameters (`utm_*`, `gclid`, `fbclid`) removed; Facebook/Instagram links go in their own columns.

## How duplicates are found

Two listings are the same clinic when:

1. they have the same street number, street and postcode (unit numbers ignored) and similar names, or
2. they share a phone number (13/1300/1800 numbers and numbers used in 3+ postcodes don't count) and are in the same postcode or within 1 km, or
3. they are within 75 m of each other with very similar names.

Listings at the same address that don't pass any rule stay separate and get a `SAME_ADDRESS_AS_<id>` flag. For each field, the value comes from the source most likely to be right (for example RateMyVet for address and phone, BestForPet for website and email). The `provenance` column in SQLite records which source each value came from.

A clinic is `verified` when it is listed in two or more directories, or Google says it is operating, or it has an active ABN that matches. Otherwise it is `single_source` and also listed in `needs_review.csv`.

## Tests

```bash
jbang tests/VetDbTests.java
```

The parsers are tested against real pages saved in `tests/fixtures/`. If a site changes its layout, save a fresh page there and adjust the parser.
