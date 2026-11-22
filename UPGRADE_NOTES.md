# UK LinkPad 1.1: upgrade notes

## Bugs found in 1.0
1. "Clear opened" deleted every saved link that had been opened (`links.removeAll { opened }`).
2. "Opened" was only a flag on the saved link. There was no history, no timestamp, no separate store.
3. Only one link at a time, no duplicate check, no real URL validation (any single word became `https://word`).
4. All links in one JSON string in SharedPreferences, rewritten on every change.
5. Every link was built as a view with three Buttons, and the whole list rebuilt after each action.
   Fine for 20 links, slow for thousands.
6. Row actions were tied to list position, which breaks as soon as search or filters exist.

## What changed
- **Storage**: local SQLite database (platform classes only, no new dependencies).
  `links` and `history` are separate tables. Clearing history only runs `DELETE FROM history`.
- **Opening a link**: marks the saved link as opened (it stays saved) and adds a history row with a timestamp.
- **Bulk import**: paste any amount of text. Splits on new lines, spaces, tabs, and commas/semicolons
  where that is clearly a separator. Commas inside a link (Google Maps `@51.5,-0.12,17z`) are kept.
  Removes empty lines and duplicates (also against links already saved; `www.`, `http`/`https`,
  trailing `/` and `#fragment` do not make a link "different"). Invalid entries are counted
  and kept in the box after saving so you can fix them. Live counts: detected / duplicates / invalid.
- **Lists**: native `ListView` with row recycling and a fast-scroll handle.
- **Search** on Saved and History. Filters on Saved: All / Unopened / Opened.
- **Undo** for deleting a link, a history entry, clearing history and deleting all links.
- **Settings**: System/Light/Dark theme, export links to .txt, import from a .txt/.csv file,
  copy all links, reset opened marks, delete all links (each with a confirmation dialog).
- **Share**: "Share" a link or text from any app into UK LinkPad; it lands in the import box.
- **Design**: "Paper" direction (cream and ink, terracotta accent, serif headings), light and dark.
- **Logo**: the "Pad" mark as an adaptive icon (with a themed/monochrome layer for Android 13+).

## Migration
On first launch the old SharedPreferences list is copied into the database. Opened links
become saved links with an Opened badge plus a history entry ("Opened earlier", the old app
never stored the time). The old data is left in place as a backup.

## Unchanged
Package name `com.uk.linkpad`, minSdk 26 (Android 8.0), targetSdk 32, Kotlin, Gradle files,
and the GitHub Actions workflow. The app still has no permissions and no internet access.
`allowBackup` is still `false`, so use Settings > Export before uninstalling.
