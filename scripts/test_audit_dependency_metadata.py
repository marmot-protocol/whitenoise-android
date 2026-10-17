"""Resolve metadata ancestry from supplier POM bytes without trusting unresolved selectors."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import xml.etree.ElementTree as ET

from scripts.audit_dependency_metadata import ancestors, audit_metadata
from scripts import audit_dependency_candidates as audit


class MetadataAncestryTest(unittest.TestCase):
    """Cover inherited BOM properties, missing variables and cyclic parents."""

    def test_parent_properties_supply_an_imported_bom_version(self):
        """A warm cache cannot hide the BOM just because its version lives in a parent."""
        poms = {
            'example:parent:1': ET.fromstring('<project><properties><bom.version>2</bom.version></properties></project>'),
            'example:child:1': ET.fromstring('<project><parent><groupId>example</groupId>'
                '<artifactId>parent</artifactId><version>1</version></parent><dependencyManagement><dependencies>'
                '<dependency><groupId>example</groupId><artifactId>bom</artifactId><version>${bom.version}</version>'
                '<type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement></project>'),
        }
        references, unresolved = ancestors(poms)
        self.assertEqual(references, {'example:parent:1', 'example:bom:2'})
        self.assertEqual(unresolved, set())
        del poms['example:parent:1']
        self.assertTrue(ancestors(poms)[1])

    def test_missing_literal_imported_bom_fails_supplier_audit(self):
        """Official 404s for a referenced BOM cannot become a successful incomplete closure."""
        child = (b'<project><dependencyManagement><dependencies><dependency><groupId>example</groupId>'
                 b'<artifactId>missing-bom</artifactId><version>2</version><type>pom</type><scope>import</scope>'
                 b'</dependency></dependencies></dependencyManagement></project>')
        sha = hashlib.sha256(child).hexdigest()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); review = root / 'review'; (review / 'downloads').mkdir(parents=True)
            (review / 'downloads' / sha).write_bytes(child)
            (review / 'provenance.json').write_text(json.dumps([{
                'coordinate': 'example:child:1', 'name': 'child-1.pom', 'sha256': sha,
                'source': 'https://repo.maven.apache.org/maven2/example/child/1/child-1.pom',
                'method': 'independent-payload-sha256'}]))
            metadata = root / 'verification.xml'
            metadata.write_text('<verification-metadata><configuration><verify-metadata>true</verify-metadata>'
                '<verify-signatures>false</verify-signatures></configuration><components>'
                '<component group="example" name="child" version="1"><artifact name="child-1.pom">'
                '<sha256 value="' + sha + '"/></artifact></component></components></verification-metadata>')
            with mock.patch.object(audit, 'download', return_value=None):
                with self.assertRaisesRegex(ValueError, 'Missing referenced parent/BOM POM'):
                    audit_metadata(metadata, review)

    def test_cyclic_parent_chain_fails_closed(self):
        """Malformed inherited metadata cannot loop or invent a usable version."""
        root = ET.fromstring('<project><parent><groupId>example</groupId><artifactId>same</artifactId>'
                             '<version>1</version></parent></project>')
        with self.assertRaisesRegex(ValueError, 'Cyclic'):
            ancestors({'example:same:1': root})


if __name__ == '__main__':
    unittest.main()
