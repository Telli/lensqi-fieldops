"""Regression checks for the repository's TRX adapter (stdlib only)."""
import tempfile
import unittest
from pathlib import Path
from test_dotnet_report import convert, ET


class ReportAdapterTest(unittest.TestCase):
    def test_theory_cases_preserve_failure_and_skip_and_reject_bad_reports(self):
        xml = '''<TestRun xmlns="http://microsoft.com/schemas/VisualStudio/TeamTest/2010"><TestDefinitions><UnitTest id="same"><TestMethod className="Suite"/></UnitTest></TestDefinitions><Results><UnitTestResult testId="same" testName="case(a)" outcome="Passed"/><UnitTestResult testId="same" testName="case(b)" outcome="Failed"><Output><ErrorInfo><Message>sentinel failure</Message></ErrorInfo></Output></UnitTestResult><UnitTestResult testId="same" testName="case(c)" outcome="NotExecuted"/></Results><ResultSummary><Counters total="3" passed="1" failed="1" notExecuted="1"/></ResultSummary></TestRun>'''
        with tempfile.TemporaryDirectory() as directory:
            source, target = Path(directory)/'input.trx', Path(directory)/'output.xml'
            source.write_text(xml)
            convert(source, target)
            root = ET.parse(target).getroot()
            self.assertEqual(len(root.findall('testcase')), 3)
            self.assertEqual(root.get('failures'), '1')
            self.assertEqual(root.get('skipped'), '1')
            self.assertIn('sentinel failure', target.read_text())
            for invalid in [xml.replace('case(b)', 'case(a)'), xml.replace('total="3"', 'total="4"'), xml.replace('outcome="Failed"', 'outcome="Unknown"')]:
                with self.subTest(invalid=invalid):
                    source.write_text(invalid)
                    with self.assertRaises((ValueError, KeyError)):
                        convert(source, target)


if __name__ == '__main__':
    unittest.main()
