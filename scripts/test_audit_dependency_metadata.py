"""Resolve metadata ancestry from supplier POM bytes without trusting unresolved selectors."""
import unittest
import xml.etree.ElementTree as ET

from scripts.audit_dependency_metadata import ancestors


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

    def test_cyclic_parent_chain_fails_closed(self):
        """Malformed inherited metadata cannot loop or invent a usable version."""
        root = ET.fromstring('<project><parent><groupId>example</groupId><artifactId>same</artifactId>'
                             '<version>1</version></parent></project>')
        with self.assertRaisesRegex(ValueError, 'Cyclic'):
            ancestors({'example:same:1': root})


if __name__ == '__main__':
    unittest.main()
