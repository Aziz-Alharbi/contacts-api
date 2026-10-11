"""Verify two controlled bugs are caught; always restore the original source files."""
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
MUTATIONS = (
    ("contacts-api", "src/main/java/com/unifonic/contacts/service/ContactService.java",
     "if (!currentSubject().equals(contact.getOwner())) {", "if (false) {",
     "com.unifonic.contacts.service.ContactServiceTest", "foreignUpdateDoesNotMutateRecord"),
    ("contacts-reporter", "src/main/java/com/unifonic/reporter/ReportService.java",
     'page.get("total").longValue(), "service-account"', 'page.get("items").size(), "service-account"',
     "com.unifonic.reporter.ReportServiceTest", "countFetchesOnlyOneItemAndUsesTotalRatherThanPageLength"),
)

for module, relative, before, after, test_class, test_method in MUTATIONS:
    source = ROOT / module / relative
    original = source.read_text()
    assert original.count(before) == 1, f"Mutation anchor changed in {source}"
    log = ROOT / module / "target" / "mutation-check.log"
    log.parent.mkdir(parents=True, exist_ok=True)
    try:
        source.write_text(original.replace(before, after, 1))
        with log.open("w") as output:
            result = subprocess.run(
                ["mvn", "-pl", module, f"-Dtest={test_class}#{test_method}", "test"],
                cwd=ROOT, stdout=output, stderr=subprocess.STDOUT, check=False,
            )
        report = ET.parse(ROOT / module / "target/surefire-reports" / f"TEST-{test_class}.xml").getroot()
        assert result.returncode != 0, f"Mutation survived: {test_method}"
        assert report.get("tests") == "1" and report.get("failures") == "1" and report.get("errors") == "0", f"Unexpected failure; inspect {log}"
        assert report.find("testcase").get("name") == test_method
        print(f"DETECTED: {module}: {test_method}")
    finally:
        source.write_text(original)

print("All mutation sources restored. Run mvn clean verify to rebuild original classes and coverage.")
