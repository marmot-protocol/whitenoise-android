# Outgoing PDF metadata policy

Issue [#3377](https://github.com/marmot-protocol/whitenoise-android/issues/3377).
Encryption protects an attachment in transit and at rest, but recipients decrypt
the exact bytes the sender chose. A PDF commonly records its author, title,
company, the apps that made it and when it was created and changed, and editors
keep earlier values in older revisions. Photos placed in a PDF can still carry
their camera and location data. Before a PDF picked through Files, shared from
another app or restored from a draft is uploaded, the app removes the metadata
named below. Photos and videos follow their own policies in
[Media containment](media-containment.md) and the
[video metadata policy](video-metadata-policy.md).

## How it works

[`PdfMetadataScrubber`](../app/src/main/java/dev/ipf/whitenoise/android/media/pdf/PdfMetadataScrubber.kt)
rewrites the file in place. It never rebuilds or re-saves the document. Every
edit replaces bytes with the same number of bytes, so the output has the input's
size, every cross-reference offset and incremental revision stays valid, and
page content, fonts, images, form values, structure tags, bookmarks, links and
annotations are never touched.

A PDF starts with `%PDF-`, optionally preceded by whitespace within the first
kilobyte. The reported MIME type has no effect on detection. It walks every object and trailer in file
order, so it sees each revision an incremental update kept. All parsing is
bounded through inspection, planning and verification. The limits cover work,
nesting, object count, retained object-stream bytes and replacement bytes. It plans every edit first, applies them, then plans again on the result.
If anything is left or anything fails, every write is undone, so a PDF it
refuses always keeps its original bytes.

## Policy

| Part of the PDF | Removed | Kept |
| --- | --- | --- |
| Document information (`/Info`) in every revision, stored plainly or inside an object stream | Every string value, including author, title, subject, keywords, creator and producer apps, creation and modification dates, and custom entries such as a company. Each value becomes `()` or `<>` padded with spaces. Strings referenced indirectly are emptied too. | The keys themselves and non-string values such as `/Trapped`. |
| Copies of document information left behind | Information dictionaries that no trailer points to any more, and the copies pdfTeX embeds for included PDFs (`/PTEX.InfoDict`, `/PTEX.FileName`), are emptied the same way. | — |
| XMP metadata streams (document, page, image or font level) | The whole packet: Dublin Core, XMP basic, PDF properties, rights, media management history and IDs, and vendor namespaces. It is replaced by an empty packet padded with spaces. A `/FlateDecode` stream is re-encoded to exactly its original length. | PDF/A identification (`pdfaid:part`, `pdfaid:rev` for part 4, and `pdfaid:conformance` if present). The PDF/UA declaration is removed with its required title. Tags and structure stay. |
| XMP packets stored plainly elsewhere | Replaced by the same empty packet. | — |
| Embedded baseline JPEG photos (`/DCTDecode`) | The payloads of EXIF and XMP (APP1), IPTC and Photoshop records (APP13), vendor segments (APP3 to APP12, APP15) and comments become zeros, including segments after scan data and in trailing JPEG images. | JFIF (APP0), ICC colour profiles (APP2), Adobe colour transforms (APP14) and the image data. |

Not covered:

- Visible content, including text on the page and names in headers or footers.
- Comments and markup annotations, including their author labels.
- Files attached to the PDF, and metadata inside image formats other than
  baseline JPEG image objects, such as JPEG 2000 or inline images in page content.
- The file identifier (`/ID`) in the trailer and the attachment's file name.

## PDFs that keep their metadata

Some PDFs cannot be changed this way. They are sent only after the person
chooses **Send as is**:

| PDF | Why it is left alone |
| --- | --- |
| Digitally signed or certified (a `/ByteRange` signature dictionary anywhere, including in an object stream) | Any change invalidates the signature. |
| Encrypted or password protected (`/Encrypt` in a trailer) | Its strings and streams cannot be read or rewritten without the key. |
| Application-specific `/PieceInfo` or opaque data after a JPEG's final image | These can hold private data whose format or role is unknown. |
| Metadata the scrubber cannot rewrite at the same length, an embedded JPEG wrapped in another filter, a metadata string that another part of the document also uses, or a structure it cannot read | A different length would move every later object, and emptying a shared string would change visible content. |

The composer inspects each staged PDF before a send. Supported PDFs use
**Remove metadata** by default. Open the card's metadata options and choose
**Keep metadata** to send that attachment's original bytes, for example to
preserve the XMP that identifies an invoice's embedded XML. Recipients can
read those properties. The choice belongs to the current attachment on the
shelf and the draft records its original bytes without recording that choice.

For PDFs that require **Send as is**, the composer names the affected files.
**Cancel** keeps the attachments and caption. Confirmation belongs to one
send across all its chunks. Dictation uses the same composer confirmation.
A paused collection asks again for affected PDFs in its remaining chunks.

The first-page preview uses the isolated reader and its 64 MiB input limit.
Provider streams are copied into bounded private files before the preview or
metadata parser sees them. Temporary copies are released after use.

## Where it runs

Android retains the selected source in the local encrypted composer draft and
applies the PDF policy before upload. Inline draft bytes are cleaned on a copy.
File-backed drafts are read from their selected native revision into a bounded
private upload file, with cleanup after acceptance. Refused PDFs require the
sender's decision before upload. Restored drafts default to removal. A draft
prepared by an earlier release may already contain a cleaned copy.

## Office formats

| Format | Privacy scope |
| --- | --- |
| Office Open XML (`.docx`, `.xlsx`, `.pptx`) | The ZIP package can carry core and custom properties, comments, tracked changes, workbook links, signatures and embedded documents. Safe preparation needs a package-aware policy and independent round-trip checks. |
| OpenDocument (`.odt`, `.ods`, `.odp`) | The ZIP package stores core properties in `meta.xml`, with other identifying data possible in the content and embedded objects. Its manifests and signatures need distinct handling. |
| Legacy Office (`.doc`, `.xls`, `.ppt`) | OLE compound files need a separate reader for property streams and embedded content. |

Office files follow the generic file path and preserve their bytes. The PDF
metadata control applies to PDFs.

## Tests

- `PdfMetadataScrubberTest` and `PdfFlateTest` cover the committed fixtures from
  [`scripts/generate_pdf_metadata_fixtures.py`](../scripts/generate_pdf_metadata_fixtures.py):
  removal across revisions and object streams, unchanged offsets, kept content,
  PDF/A identification, embedded photos, refusals, truncated and hostile input,
  shared strings, reused object numbers and leftover information copies.
- `DocumentUploadPrivacyCoverageTest` sends the fixtures through the real sender
  and reads the bytes handed to MDK.
- `ComposerPdfMetadataChoicesTest` and `ComposerPdfAttachmentScreenshotTest`
  cover the choice and the composer card and question.
