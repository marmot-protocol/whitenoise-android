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

The scrubber detects a PDF from its content (`%PDF-` within the first kilobyte),
never from the reported MIME type. It walks every object and trailer in file
order, so it sees each revision an incremental update kept. All parsing is
bounded: nesting depth, object count, name length and the size of each inflated
stream. It plans every edit first, applies them, then plans again on the result.
If anything is left or anything fails, every write is undone, so a PDF it
refuses always keeps its original bytes.

## Policy

| Part of the PDF | Removed | Kept |
| --- | --- | --- |
| Document information (`/Info`) in every revision, stored plainly or inside an object stream | Every string value, including author, title, subject, keywords, creator and producer apps, creation and modification dates, and custom entries such as a company. Each value becomes `()` or `<>` padded with spaces. Strings referenced indirectly are emptied too. | The keys themselves and non-string values such as `/Trapped`. |
| XMP metadata streams (document, page, image or font level) | The whole packet: Dublin Core, XMP basic, PDF properties, rights, media management history and IDs, and vendor namespaces. It is replaced by an empty packet padded with spaces. A `/FlateDecode` stream is re-encoded to exactly its original length. | PDF/A (`pdfaid:part`, `pdfaid:conformance`) and PDF/UA (`pdfuaid:part`) identification, when the original declared it. |
| XMP packets stored plainly elsewhere | Replaced by the same empty packet. | — |
| Embedded baseline JPEG photos (`/DCTDecode`) | The payloads of EXIF and XMP (APP1), IPTC and Photoshop records (APP13), vendor segments (APP3 to APP12, APP15) and comments become zeros. | JFIF (APP0), ICC colour profiles (APP2), Adobe colour transforms (APP14) and the image data. |

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
| Metadata the scrubber cannot rewrite at the same length, an embedded JPEG wrapped in another filter, or a structure it cannot read | A different length would move every later object. |

The composer card shows the state of each staged PDF before sending. Sending
asks once for the PDFs that keep their metadata, naming them; **Cancel** keeps
everything on the shelf. The choice belongs to the PDFs on the shelf at that
moment: it travels with each prepared attachment into the send, it is never
saved with a draft, and removing a PDF forgets it. A PDF that nobody chose to
send as it is never leaves the device by any path; the send reports
"Couldn't remove this PDF's metadata, so it wasn't sent".

## Where it runs

- Files picks, incoming shares and draft preparation read the PDF and remove its
  metadata in place, in memory or in a private staged file, before the bytes are
  saved as a draft or offered for upload.
- Drafts that an earlier version saved are cleaned again on a copy before a send
  reuses them, because a draft's bytes are shared with its native draft.
- The send checks every document's bytes once more and drops a PDF that still
  carries metadata without the person's choice.

The composer previews each staged PDF's first page through the isolated document
parser that the in-app reader uses, one fresh worker per PDF.

## Tests

- `PdfMetadataScrubberTest` and `PdfFlateTest` cover the committed fixtures from
  [`scripts/generate_pdf_metadata_fixtures.py`](../scripts/generate_pdf_metadata_fixtures.py):
  removal across revisions and object streams, unchanged offsets, kept content,
  PDF/A identification, embedded photos, refusals and truncated input.
- `DocumentUploadPrivacyCoverageTest` sends the fixtures through the real sender
  and reads the bytes handed to MDK.
- `ComposerPdfMetadataChoicesTest` and `ComposerPdfAttachmentScreenshotTest`
  cover the choice and the composer card and question.
