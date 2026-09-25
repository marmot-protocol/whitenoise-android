#!/usr/bin/env python3
"""Create deterministic, nonpersonal files for the PR #2830 SAF matrix."""

from pathlib import Path
from io import BytesIO
import struct
import wave
import zipfile

root = Path(__file__).parent / "assets"
root.mkdir(exist_ok=True)
(root / "note.txt").write_text("WN PR 2830: plain document fixture\n", encoding="utf-8")
(root / "opaque.bin").write_bytes(bytes(range(256)) * 4)
(root / "empty.txt").write_bytes(b"")

stream = b"BT /F1 12 Tf 36 72 Td (WN PR 2830 PDF fixture) Tj ET"
objects = [
    b"<< /Type /Catalog /Pages 2 0 R >>",
    b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 144] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
    b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    b"<< /Length %d >>\nstream\n" % len(stream) + stream + b"\nendstream",
]
pdf = bytearray(b"%PDF-1.4\n")
offsets = [0]
for index, obj in enumerate(objects, 1):
    offsets.append(len(pdf))
    pdf += f"{index} 0 obj\n".encode() + obj + b"\nendobj\n"
xref = len(pdf)
pdf += f"xref\n0 {len(offsets)}\n0000000000 65535 f \n".encode()
for offset in offsets[1:]:
    pdf += f"{offset:010d} 00000 n \n".encode()
pdf += f"trailer\n<< /Root 1 0 R /Size {len(offsets)} >>\nstartxref\n{xref}\n%%EOF\n".encode()
(root / "paper.pdf").write_bytes(pdf)

with zipfile.ZipFile(root / "bundle.zip", "w", zipfile.ZIP_DEFLATED) as archive:
    archive.writestr("inside.txt", "WN PR 2830 archive fixture\n")

with zipfile.ZipFile(root / "report.docx", "w", zipfile.ZIP_DEFLATED) as archive:
    archive.writestr(
        "[Content_Types].xml",
        '<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>',
    )
    archive.writestr(
        "_rels/.rels",
        '<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>',
    )
    archive.writestr(
        "word/document.xml",
        '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>WN PR 2830 Office fixture</w:t></w:r></w:p></w:body></w:document>',
    )

audio = BytesIO()
with wave.open(audio, "wb") as wav:
    wav.setnchannels(1)
    wav.setsampwidth(2)
    wav.setframerate(8000)
    wav.writeframes(b"".join(struct.pack("<h", (i % 100) * 100) for i in range(8000)))
(root / "tone.wav").write_bytes(audio.getvalue())
