# Quick Check reporting

Quick Check reporting is separated from card access.

```text
DesfireQuickCheckService
        |
DesfireQuickCheckReport
        |
DesfireQuickCheckReportDocumentFactory
        |
secret-free DesfireQuickCheckReportDocument
        |---------------------|
        |                     |
Text renderer            Android PDF renderer
```

## Secret boundary

`DesfireQuickCheckReportDocument` is the export boundary. It may contain:

- card UID and DESFire version metadata;
- application IDs and key settings;
- file types, sizes, communication modes and access rights;
- access result (`PUBLIC`, `AUTHENTICATED`, `KEY_REQUIRED`, etc.);
- key labels, types and key numbers used/attempted;
- warnings and result status.

It must never contain DESFire key bytes. JVM tests verify that raw key material does not appear in rendered report text.

## PDF export

The Android app stores secret-free scan snapshots in an atomic private history file. History survives orientation changes, menu changes and restarts. Each scan offers PDF export; the Results toolbar can export all scans together or clear history after confirmation. PDF exports use Android's `CreateDocument("application/pdf")` contract so the user chooses the destination. Card results and reader/environment details have separate sections. A newly added scan expands and collapses earlier scans.

`DesfireQuickCheckPdfRenderer` uses the platform `android.graphics.pdf.PdfDocument` API. No third-party PDF dependency is required. The renderer supports page wrapping/pagination and page footers.

The PICC master-key probe follows the application-directory line in both new and persisted reports. The report title and Card heading use the same blue header style as Reader / Environment; warning headings remain red. This formatting is applied at presentation time without requiring a card rescan.

The PDF is a presentation of an already completed Quick Check; generating or saving the PDF never accesses the card.

## Future formats

Because the export input is platform-neutral and secret-free, additional renderers such as JSON/CSV can be added without changing NFC/liblogicalaccess code.
