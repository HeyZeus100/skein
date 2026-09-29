"""Guard the opt-in lane's dispatch, platform and dependency verification contract."""
from pathlib import Path
import os
import shlex
import subprocess
import tempfile
import tomllib
import unittest
import xml.etree.ElementTree as ET

import yaml

ROOT = Path(__file__).resolve().parents[2]


class FoldableWorkflowContractTest(unittest.TestCase):
    def test_linux_emulator_library_is_installed_before_the_version_probe(self):
        workflow = yaml.safe_load((ROOT / ".github/workflows/foldable.yml").read_text())
        steps = workflow["jobs"]["foldable"]["steps"]
        names = [step.get("name") for step in steps]
        install = names.index("Install Linux emulator runtime library")
        self.assertLess(install, names.index("SDK tools and exact fold profile"))
        commands = [shlex.split(line) for line in steps[install]["run"].splitlines() if line.strip()]
        self.assertEqual(["sudo", "apt-get", "update"], commands[0])
        self.assertEqual(["sudo", "env", "DEBIAN_FRONTEND=noninteractive", "apt-get", "install",
                          "--yes", "--no-install-recommends", "libpulse0"], commands[1])

    def test_exact_and_compatibility_profiles_are_explicit_and_labelled(self):
        workflow = yaml.safe_load((ROOT / ".github/workflows/foldable.yml").read_text())
        # PyYAML's YAML 1.1 resolver treats the GitHub `on` key as a boolean.
        triggers = workflow.get("on", workflow.get(True))
        self.assertEqual({"workflow_dispatch"}, set(triggers))
        profile = triggers["workflow_dispatch"]["inputs"]["profile"]
        self.assertEqual("pixel_9_pro_fold", profile["default"])
        self.assertEqual(["pixel_9_pro_fold", "pixel_fold", "7.6in Foldable"], profile["options"])
        job = workflow["jobs"]["foldable"]
        self.assertIn("inputs.profile", job["name"])
        steps = job["steps"]
        emulator = next(step for step in steps if "android-emulator-runner@" in step.get("uses", ""))
        self.assertEqual("${{ inputs.profile }}", emulator["with"]["profile"])
        self.assertEqual("${{ env.SKEIN_FOLD_AVD_NAME }}", emulator["with"]["avd-name"])
        self.assertEqual("skein_foldable_gate", job["env"]["SKEIN_FOLD_AVD_NAME"])
        self.assertIn("compatibility", job["name"])
        sdk = next(step["run"] for step in steps if step.get("name") == "SDK tools and exact fold profile")
        tokens = shlex.split(sdk)
        self.assertIn("platforms;android-37.0", tokens)
        self.assertNotIn("platforms;android-37", tokens)
        self.assertIn('tool_bin="$ANDROID_HOME/cmdline-tools/latest/bin"', sdk)
        self.assertNotIn("find ", sdk)
        for step in steps:
            if "./gradlew" in step.get("run", ""):
                self.assertIn("--max-workers=2", step["run"])
        upload = next(step for step in steps if "upload-artifact@" in step.get("uses", ""))
        self.assertEqual("always()", upload["if"])
        self.assertIn("inputs.profile", upload["with"]["name"])

    def test_spaced_catalog_id_is_exact_and_missing_profile_never_falls_back(self):
        workflow = yaml.safe_load((ROOT / ".github/workflows/foldable.yml").read_text())
        sdk = next(step["run"] for step in workflow["jobs"]["foldable"]["steps"]
                   if step.get("name") == "SDK tools and exact fold profile")
        # Execute only the real allowlist/catalog shell lines against a host fixture: no SDK/device calls.
        script = "set -euo pipefail\n" + "\n".join(line for line in sdk.splitlines()
                                                   if line.startswith(("case ", "grep ")))
        with tempfile.TemporaryDirectory() as directory:
            catalog = Path(directory) / "build/foldable-evidence/device-profiles.txt"
            catalog.parent.mkdir(parents=True)
            catalog.write_text('id: 62 or "7.6in Foldable"\n    OEM : Generic\n')
            for profile, expected in (("7.6in Foldable", 0), ("62", 2),
                                      ("7.6in.Foldable", 2), ("pixel_9_pro_fold", 1)):
                with self.subTest(profile=profile):
                    result = subprocess.run(["bash", "-c", script], cwd=directory,
                                            env={**os.environ, "SKEIN_FOLD_PROFILE": profile}, capture_output=True)
                    self.assertEqual(expected, result.returncode, result.stderr)

    def test_espresso_pin_and_test_only_network_manifest_are_verified(self):
        catalog = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())
        version = catalog["versions"]["androidx-test-espresso-device"]
        self.assertEqual("1.1.0", version)
        ns = {"m": "https://schema.gradle.org/dependency-verification"}
        metadata = ET.parse(ROOT / "gradle/verification-metadata.xml")
        component = metadata.find(
            f"m:components/m:component[@group='androidx.test.espresso'][@name='espresso-device'][@version='{version}']", ns)
        artifacts = {node.get("name"): node.find("m:sha256", ns).get("value")
                     for node in component.findall("m:artifact", ns)}
        self.assertEqual("be57100db268c03247f365a31209f9c2b83b7f3b3ea9f7f2334c40ecb835c010",
                         artifacts["espresso-device-1.1.0.aar"])
        self.assertEqual("673a610fed1dd0aaf66e9e8d3eb11a4b60ca5c933585d7b842e498e9bfd6309e",
                         artifacts["espresso-device-1.1.0.pom"])
        manifest = ET.parse(ROOT / "app/src/foldableTest/AndroidManifest.xml")
        permissions = {node.get("{http://schemas.android.com/apk/res/android}name")
                       for node in manifest.findall("uses-permission")}
        self.assertEqual({"android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE",
                          "android.permission.ACCESS_LOCAL_NETWORK"}, permissions)


if __name__ == "__main__":
    unittest.main()
