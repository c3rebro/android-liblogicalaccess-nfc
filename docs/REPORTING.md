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

The Android app keeps the latest secret-free report document after a Quick Check. `Export last Quick Check as PDF` uses Android's `CreateDocument("application/pdf")` contract so the user chooses the destination.

`DesfireQuickCheckPdfRenderer` uses the platform `android.graphics.pdf.PdfDocument` API. No third-party PDF dependency is required. The renderer supports page wrapping/pagination and page footers.

The PDF is a presentation of an already completed Quick Check; generating or saving the PDF never accesses the card.

## Future formats

Because the export input is platform-neutral and secret-free, additional renderers such as JSON/CSV can be added without changing NFC/liblogicalaccess code.
