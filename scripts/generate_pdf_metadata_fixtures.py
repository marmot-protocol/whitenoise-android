#!/usr/bin/env python3
"""Regenerates the tagged PDF fixtures used by the PDF metadata scrubber tests (#3377).

Every document is synthetic and tagged with obviously fake private values (WNPrivate* strings and a fixed 2024
date). No real document or person is involved. The fixtures cover what the scrubber must clean or refuse:

- ghostscript.pdf: document information, an XMP packet, bookmarks and a link, written by Ghostscript
- object-streams.pdf: the same with document information compressed inside an object stream
- flate-xmp.pdf: an XMP packet compressed with /FlateDecode
- incremental.pdf: an incremental update whose new revision keeps the old author in the earlier revision
- form-photo.pdf: a filled form field, an indirect author string and an embedded JPEG with EXIF and a comment
- pdfa.pdf: XMP that declares PDF/A-2B identification, which must survive
- signed.pdf: a signature dictionary added by an incremental update, which must be refused
- encrypted.pdf: an RC4/AES-protected file with an empty user password, which must be refused
- hex-xmp.pdf: XMP stored with /ASCIIHexDecode, which cannot be rewritten at the same length and must be refused

Usage: scripts/generate_pdf_metadata_fixtures.py
Needs Ghostscript (gs), and Python with pikepdf and Pillow. Tool versions change the bytes, so the tests assert the
private values and structure rather than fixed digests. The script writes Base64 text to the JVM test resources.
"""

import base64
import io
import os
import pathlib
import re
import subprocess
import tempfile
import zlib

import pikepdf
from PIL import Image
from pikepdf import Array, Dictionary, Name, String

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "app/src/test/resources/pdf-metadata"
SOURCE_DATE_EPOCH = "1714979289"  # 2024-05-06T07:08:09Z

POSTSCRIPT = b"""%!PS
[ /Title (WNPrivateTitle) /Author (WNPrivateAuthor) /Subject (WNPrivateSubject) /Keywords (WNPrivateKeywords)
  /Creator (WNPrivateCreator) /DOCINFO pdfmark
[ /Title (Chapter One) /Page 1 /OUT pdfmark
[ /Title (Chapter Two) /Page 2 /OUT pdfmark
[ /Rect [72 600 300 640] /Border [0 0 1] /Action << /Subtype /URI /URI (https://example.org/plan) >>
  /Subtype /Link /ANN pdfmark
/Helvetica findfont 24 scalefont setfont
72 700 moveto (Searchable page one text) show
showpage
72 700 moveto (Page two content) show
showpage
"""


def incremental(source, target, objects, trailer_extra):
    """Appends an incremental update with [objects] (number, body) and a trailer chained to the previous one."""
    data = source.read_bytes()
    previous = int(re.findall(rb"startxref\s+(\d+)", data)[-1])
    with pikepdf.open(source) as pdf:
        size = int(pdf.trailer.Size)
        root = pdf.trailer.Root.objgen
    out = bytearray(data if data.endswith(b"\n") else data + b"\n")
    offsets = {}
    for number, body in objects:
        offsets[number] = len(out)
        out += b"%d 0 obj\n" % number + body + b"\nendobj\n"
    xref = len(out)
    out += b"xref\n"
    for number in sorted(offsets):
        out += b"%d 1\n%010d 00000 n \n" % (number, offsets[number])
    out += b"trailer\n<< /Size %d /Root %d %d R /Prev %d %s >>\nstartxref\n%d\n%%%%EOF\n" % (
        max(size, max(offsets) + 1), root[0], root[1], previous, trailer_extra, xref)
    target.write_bytes(bytes(out))


def tagged_jpeg():
    image = Image.new("RGB", (64, 48), (200, 30, 30))
    exif = Image.Exif()
    exif[0x010F] = "WNPrivateCamera"
    exif[0x0110] = "WNPrivateModel"
    exif[0x8825] = {1: "N", 2: (52.0, 31.0, 12.0)}
    buffer = io.BytesIO()
    image.save(buffer, "JPEG", exif=exif, comment=b"WNPrivateComment")
    return buffer.getvalue()


def form_photo(target):
    pdf = pikepdf.new()
    pdf.add_blank_page(page_size=(300, 300))
    page = pdf.pages[0]
    photo = pikepdf.Stream(pdf, tagged_jpeg())
    photo.Type, photo.Subtype, photo.Filter = Name.XObject, Name.Image, Name.DCTDecode
    photo.Width, photo.Height, photo.ColorSpace, photo.BitsPerComponent = 64, 48, Name.DeviceRGB, 8
    page.Resources = Dictionary(XObject=Dictionary(Im0=photo))
    page.Contents = pikepdf.Stream(pdf, b"q 128 0 0 96 20 150 cm /Im0 Do Q")
    field = pdf.make_indirect(Dictionary(
        Type=Name.Annot, Subtype=Name.Widget, FT=Name.Tx, T=String("name"), V=String("Filled answer"),
        Rect=Array([20, 20, 200, 50]), P=page.obj))
    page.Annots = Array([field])
    pdf.Root.AcroForm = Dictionary(Fields=Array([field]))
    pdf.Root.MarkInfo = Dictionary(Marked=True)
    author = pdf.make_indirect(String("WNPrivateIndirect"))
    pdf.trailer.Info = pdf.make_indirect(Dictionary(Author=author, Title=String("WNPrivateTitle"), Trapped=Name.false))
    pdf.save(target, compress_streams=False)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as work_dir:
        work = pathlib.Path(work_dir)
        base = work / "ghostscript.pdf"
        (work / "source.ps").write_bytes(POSTSCRIPT)
        env = dict(os.environ, SOURCE_DATE_EPOCH=SOURCE_DATE_EPOCH)
        subprocess.run(["gs", "-q", "-dNOPAUSE", "-dBATCH", "-sDEVICE=pdfwrite", f"-sOutputFile={base}",
                        str(work / "source.ps")], check=True, env=env)
        with pikepdf.open(base) as pdf:
            with pdf.open_metadata(set_pikepdf_as_editor=False) as meta:
                meta["dc:creator"] = ["WNPrivateAuthor"]
            pdf.docinfo["/Company"] = "WNPrivateCompany"
            pdf.save(work / "object-streams.pdf", object_stream_mode=pikepdf.ObjectStreamMode.generate,
                     compress_streams=True)
        with pikepdf.open(base) as pdf:
            xmp = pdf.Root.Metadata.read_bytes()
            stream = pikepdf.Stream(pdf, zlib.compress(xmp, 6))
            stream.Type, stream.Subtype, stream.Filter = Name.Metadata, Name.XML, Name.FlateDecode
            pdf.Root.Metadata = stream
            pdf.save(work / "flate-xmp.pdf", compress_streams=False)
        with pikepdf.open(base) as pdf:
            info = pdf.trailer.Info.objgen[0]
            size = int(pdf.trailer.Size)
            root = pdf.Root.objgen[0]
            pages = pdf.Root.Pages.objgen[0]
        incremental(base, work / "incremental.pdf",
                    [(info, b"<< /Author (WNPrivateSecondAuthor) /Producer (WNPrivateEditor) >>")], b"/Info %d 0 R" % info)
        signature = (b"<< /Type /Sig /Filter /Adobe.PPKLite /SubFilter /adbe.pkcs7.detached"
                     b" /ByteRange [0 100 200 300] /Contents <00> /Name (WNPrivateSigner) >>")
        incremental(base, work / "signed.pdf", [(size, signature)], b"/Info %d 0 R" % info)
        hex_data = xmp.hex().encode() + b">"
        hex_stream = (b"<< /Type /Metadata /Subtype /XML /Filter /ASCIIHexDecode /Length %d >>\nstream\n" % len(hex_data)
                      + hex_data + b"\nendstream")
        catalog = b"<< /Type /Catalog /Pages %d 0 R /Metadata %d 0 R >>" % (pages, size)
        incremental(base, work / "hex-xmp.pdf", [(root, catalog), (size, hex_stream)], b"/Info %d 0 R" % info)
        with pikepdf.open(base) as pdf:
            pdf.save(work / "encrypted.pdf", encryption=pikepdf.Encryption(owner="owner", user="", R=4))
        with pikepdf.open(base) as pdf:
            with pdf.open_metadata(set_pikepdf_as_editor=False) as meta:
                meta["pdfaid:part"] = "2"
                meta["pdfaid:conformance"] = "B"
            pdf.save(work / "pdfa.pdf", compress_streams=False)
        form_photo(work / "form-photo.pdf")
        for name in ["ghostscript", "object-streams", "flate-xmp", "incremental", "form-photo", "pdfa", "signed",
                     "encrypted", "hex-xmp"]:
            data = (work / f"{name}.pdf").read_bytes()
            (OUT / f"{name}.pdf.b64").write_bytes(base64.encodebytes(data))
            print(f"{name}.pdf: {len(data)} bytes")


if __name__ == "__main__":
    main()
