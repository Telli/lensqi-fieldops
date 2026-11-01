#!/usr/bin/env python3
"""Run .NET tests and emit JUnit with distinct xUnit theory-case identities.

Original TRX files remain in the run directory. Outcomes, skipped cases and
failure details are preserved; malformed or inconsistent reports fail closed.
"""
import argparse
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

NS = {'t': 'http://microsoft.com/schemas/VisualStudio/TeamTest/2010'}


def convert(source: Path, destination: Path):
    root = ET.parse(source).getroot()
    definitions = {t.get('id'): t.find('t:TestMethod', NS).get('className')
                   for t in root.findall('.//t:UnitTest', NS)}
    suite = ET.Element('testsuite', name=source.stem)
    seen = set()
    counts = dict(total=0, passed=0, failed=0, notExecuted=0)
    for result in root.findall('.//t:UnitTestResult', NS):
        name = result.get('testName')
        classname = definitions[result.get('testId')]
        identity = (classname, name)
        if not name or identity in seen:
            raise ValueError('Missing or duplicate result identity')
        seen.add(identity)
        outcome = result.get('outcome')
        category = {'Passed': 'passed', 'Failed': 'failed', 'NotExecuted': 'notExecuted'}[outcome]
        counts['total'] += 1
        counts[category] += 1
        case = ET.SubElement(suite, 'testcase', classname=classname, name=name)
        if outcome == 'Failed':
            error = result.find('t:Output/t:ErrorInfo', NS)
            ET.SubElement(case, 'failure', message='TRX test failed').text = ET.tostring(error, encoding='unicode') if error is not None else 'No failure detail supplied'
        elif outcome == 'NotExecuted':
            ET.SubElement(case, 'skipped')
    counters = root.find('.//t:ResultSummary/t:Counters', NS)
    if counters is None or not counts['total']:
        raise ValueError('Missing test results or counters')
    for key, value in counts.items():
        if int(counters.get(key, '-1')) != value:
            raise ValueError(f'TRX counter mismatch: {key}')
    suite.attrib.update(tests=str(counts['total']), failures=str(counts['failed']), errors='0', skipped=str(counts['notExecuted']))
    ET.ElementTree(suite).write(destination, encoding='utf-8', xml_declaration=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report_dir', type=Path)
    args = parser.parse_args()
    args.report_dir.mkdir(parents=True, exist_ok=True)
    if list(args.report_dir.glob('*.trx')) or list(args.report_dir.glob('*.xml')):
        parser.error('Use an empty report directory to exclude stale results')
    run = subprocess.run(['dotnet', 'test', '--nologo', '--logger', 'trx', '--results-directory', str(args.report_dir)])
    reports = list(args.report_dir.glob('*.trx'))
    if not reports:
        raise ValueError('No TRX reports produced')
    for report in reports:
        convert(report, report.with_suffix('.xml'))
    return run.returncode


if __name__ == '__main__':
    raise SystemExit(main())
