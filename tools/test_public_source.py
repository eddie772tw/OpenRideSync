from pathlib import Path
import tempfile,unittest
from check_public_source import inspect_source

class PublicSourceChecks(unittest.TestCase):
    def test_private_paths_are_rejected_without_reading_contents(self):
        self.assertEqual(inspect_source(Path("does-not-exist"),"app/src/private/assets/auth-client.json")[0]["category"],"private_or_generated_path")
        self.assertEqual(inspect_source(Path("does-not-exist"),"signing/release.keystore")[0]["category"],"private_or_generated_path")

    def test_credential_findings_never_echo_the_value(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/"source.txt"
            secret="ghp_"+"a"*36
            path.write_text(secret)
            findings=inspect_source(path,"source.txt")
            self.assertEqual(findings,[{"path":"source.txt","category":"github_token","line":1}])
            self.assertNotIn(secret,str(findings))

    def test_only_the_pinned_wrapper_binary_is_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/"wrapper.jar";path.write_bytes(b"not-a-wrapper")
            self.assertEqual(inspect_source(path,"gradle/wrapper/gradle-wrapper.jar")[0]["category"],"unreviewed_binary")
