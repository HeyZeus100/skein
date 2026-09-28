# Markdown import link resolution

`skein-382u` resolves Obsidian-style wikilinks within one imported folder or ordinary
Markdown zip. It preserves frontmatter titles, heading-derived titles, custom metadata,
and display labels. Single-file `ImportService.importText` keeps its existing behavior.

After successful files are imported, `MarkdownImportLinker` matches links against those
files only. Bare names can match filenames without their Markdown/text extension, display
titles, or scalar/list `alias` and `aliases` frontmatter. Directory-qualified names match
paths from the imported tree root. `./` and `../` resolve from the source file's directory;
escaping the imported root, absolute paths, and URI/drive paths do not resolve. Matching
normalizes Unicode NFC and case. Backslashes in links normalize to directory separators.
A duplicate basename stays ambiguous even if one candidate happens to share its folder;
use an explicit relative or qualified path in the source vault to distinguish it.

Unique matches with UUID document IDs become `[[document-uuid#heading|original display text]]`.
Legacy non-UUID frontmatter IDs remain unchanged, but links targeting those imported files
stay guarded/unresolved. They cannot be distinguished safely from ordinary title links on
later re-ingest or deletion; canonical import IDs are separate work in `skein-xtov.27.1`. Existing UUID
links and links in code spans/fences remain unchanged. Graph ingestion and editor navigation
recognize UUID targets directly. Repository rename preserves incoming ID-bound edges;
deletion changes them to ID sentinels that a same-title replacement cannot claim.
Title-bound links keep their existing rename/delete behavior.
A missing UUID target never creates a note named after that ID.

Missing and ambiguous targets keep their original link spelling. Flat frontmatter lists
`_skein_unresolved_import_links` and `_skein_ambiguous_import_links` prevent the ordinary
title resolver from selecting an unrelated note or creating a duplicate when clicked.
The editor explains unresolved links without leaving the source note. These keys round-trip
through Markdown export and the frontmatter editor. Creation and initial unresolved guards
commit in one repository transaction. If import is cancelled, already committed notes retain
guards. Final resolution also commits metadata and body together for each note; it never
writes through the separate index connection inside that transaction.

Archives with `.skein/manifest.json`, including late or malformed manifests, keep restored
wikilinks and frontmatter instead of applying the link rewrite. A leading manifest keeps
the existing top-level Markdown/attachment layout. Ordinary archives now accept nested
`.md` files. All existing size/count bounds and traversal rejection remain. The existing
archive ID collision policy is unchanged: a Markdown entry whose ID already exists is
skipped. Ordinary folder/single-file import still creates a separate note and reports the
ID conflict. Canonical ordinary-import identity policy is tracked separately in
`skein-xtov.27.1`.

This does not add a persistent vault-wide alias registry or filesystem paths to documents.
New manually typed filename/path/alias links after import do not gain global lookup. A later
import does not automatically repair another import's unresolved targets. Fixing those
links in the source tree and importing again is supported, as are explicit UUID links;
a persistent alias/disambiguation workflow remains separate work. Device import and visual
verification are still required beyond the JVM coverage below.

Regression coverage lives in `MarkdownImportLinkerTest`, `VaultZipImportTest`,
`FolderImportJobTest`, and `NoteTabStateTest`. It covers title/filename mismatches, aliases,
qualified and relative paths, Unicode/case, ambiguous names, missing/deleted targets,
rename-safe UUID navigation, literal code, cancellation guards, truncated archives, and
manifest restoration.
