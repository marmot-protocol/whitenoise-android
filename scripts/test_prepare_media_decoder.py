"""Host fixtures exercise build admission/transport/audits; no native compiler runs."""
import hashlib
import io
import json
import os
from pathlib import Path
import struct
import sys
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

if __package__:
    from . import prepare_media_decoder as build
else:
    import prepare_media_decoder as build


def archive(prefix, entries):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode="w:xz") as result:
        for name, data in entries.items():
            member = tarfile.TarInfo(prefix + "/" + name)
            member.size = len(data)
            member.mode = 0o755 if name == "configure" else 0o644
            result.addfile(member, io.BytesIO(data))
    return output.getvalue()


def wheel(entries):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as result:
        for name, data in entries.items():
            result.writestr(name, data)
    return output.getvalue()


class Response(io.BytesIO):
    def __init__(self, data, url="https://fixture.invalid/file"):
        super().__init__(data)
        self.url = url

    def geturl(self):
        return self.url


class PortableInspectionTests(unittest.TestCase):
    def test_cache_key_and_plan_do_not_import_unavailable_fcntl(self):
        probe = """import importlib.abc, runpy, sys
class NoFcntl(importlib.abc.MetaPathFinder):
    def find_spec(self, fullname, path=None, target=None):
        if fullname == 'fcntl':
            raise ModuleNotFoundError('fcntl is unavailable on this host')
sys.modules.pop('fcntl', None)
sys.meta_path.insert(0, NoFcntl())
script, option = sys.argv[1:]
sys.argv = [script, option]
runpy.run_path(script, run_name='__main__')
"""
        for option in ('--cache-key', '--plan'):
            result = subprocess.run([sys.executable, '-I', '-B', '-c', probe, build.__file__, option],
                                    capture_output=True, text=True, check=True)
            if option == '--cache-key':
                self.assertRegex(result.stdout.strip(), r'^[a-f0-9]{64}$')
            else:
                self.assertEqual(json.loads(result.stdout)['lock']['ndk'], '29.0.14206865')


class ToolWheelTests(unittest.TestCase):
    def extract(self, directory, entries):
        path = directory / "ninja.whl"
        path.write_bytes(wheel(entries))
        return build.extract_tools([path], directory / "tools")

    def test_pinned_ninja_wheel_script_layout_and_permissions(self):
        # This literal is independently checked against the SHA-pinned wheel's RECORD.
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            binary = self.extract(root, {"ninja-1.13.2.data/scripts/ninja": b"synthetic executable"})
            self.assertEqual(root / "tools/ninja-1.13.2.data/scripts/ninja", binary)
            self.assertEqual(b"synthetic executable", binary.read_bytes())
            self.assertEqual(0o755, binary.stat().st_mode & 0o777)

    def test_missing_old_or_other_version_paths_never_fall_back(self):
        for entries in ({}, {"ninja/data/bin/ninja": b"old layout"},
                        {"ninja-1.13.3.data/scripts/ninja": b"different version"}):
            with self.subTest(entries=entries), tempfile.TemporaryDirectory() as temporary:
                with self.assertRaisesRegex(ValueError, "pinned ninja executable missing"):
                    self.extract(Path(temporary), entries)

    def test_tool_wheel_traversal_absolute_and_symlink_entries_refused(self):
        for name, mode in (("../outside", 0o100644), ("/outside", 0o100644),
                           ("ninja-1.13.2.data/scripts/ninja", 0o120777)):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                path = root / "ninja.whl"
                with zipfile.ZipFile(path, "w") as result:
                    entry = zipfile.ZipInfo(name)
                    entry.external_attr = mode << 16
                    result.writestr(entry, b"/outside")
                with self.assertRaisesRegex(ValueError, "unsafe tool wheel entry"):
                    build.extract_tools([path], root / "tools")
                self.assertFalse((root / "outside").exists())

    def test_tool_extraction_budget_still_refused_before_extract(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / "ninja.whl"
            path.write_bytes(wheel({"ninja-1.13.2.data/scripts/ninja": b"tiny"}))
            entry = zipfile.ZipInfo("ninja-1.13.2.data/scripts/ninja")
            entry.file_size = 32 * 1024 * 1024 + 1
            with patch.object(zipfile.ZipFile, "infolist", return_value=[entry]):
                with self.assertRaisesRegex(ValueError, "tool extraction budget refused"):
                    build.extract_tools([path], root / "tools")
            self.assertEqual([], list((root / "tools").iterdir()))


class MesonLauncherTests(unittest.TestCase):
    def test_persistent_launcher_isolates_initial_and_repeated_invocation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            tools = root / "tools"
            module = tools / "mesonbuild"
            module.mkdir(parents=True)
            (module / "__init__.py").write_text("")
            (module / "mesonmain.py").write_text(
                "import json,sys\ndef main():\n print(json.dumps([sys.flags.isolated,"
                "sys.dont_write_bytecode,sys.argv[0],sys.argv[1:]]));return 0\n")
            poison = root / "poison"
            poison.mkdir()
            (poison / "sitecustomize.py").write_text("raise RuntimeError('poison sitecustomize')\n")
            (poison / "mesonbuild.py").write_text("raise RuntimeError('poison mesonbuild')\n")
            launcher = build.write_meson_launcher(tools)
            self.assertFalse(launcher.name.endswith(".py"))
            self.assertEqual(0o755, launcher.stat().st_mode & 0o777)
            with patch.dict(os.environ, {"PYTHONPATH": str(poison), "PYTHONHOME": str(poison)}):
                for arguments in (["setup"], ["--internal", "vcstagger"], ["--internal", "regenerate"]):
                    result = subprocess.run([launcher, *arguments], cwd=poison, check=True,
                                            capture_output=True, text=True, timeout=15)
                    self.assertEqual([1, True, str(launcher), arguments], json.loads(result.stdout))
            self.assertEqual([], list(root.rglob("__pycache__")))
            with self.assertRaises(FileExistsError):
                build.write_meson_launcher(tools)

    def test_missing_pinned_modules_refuse_before_creating_launcher(self):
        with tempfile.TemporaryDirectory() as temporary:
            tools = Path(temporary)
            with self.assertRaisesRegex(ValueError, "pinned Meson module missing"):
                build.write_meson_launcher(tools)
            self.assertFalse((tools / "meson-pinned").exists())

    def test_deleted_pinned_module_cannot_fall_back_to_poisoned_environment(self):
        with tempfile.TemporaryDirectory() as temporary:
            tools = Path(temporary)
            module = tools / "mesonbuild"
            module.mkdir()
            (module / "__init__.py").write_text("")
            entrypoint = module / "mesonmain.py"
            entrypoint.write_text("def main(): return 0\n")
            launcher = build.write_meson_launcher(tools)
            entrypoint.unlink()
            with patch.dict(os.environ, {"PYTHONPATH": str(tools)}):
                result = subprocess.run([launcher, "--version"], capture_output=True, timeout=15)
            self.assertNotEqual(0, result.returncode)


class NativeBuildTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.native = self.root / "native"
        self.native.mkdir()
        self.patch = patch.object(build, "NATIVE", self.native)
        self.patch.start()
        self.addCleanup(self.patch.stop)
        for name in ("media_decoder.cpp", "exports.map"):
            (self.native / name).write_text("synthetic " + name)
        self.data = {
            "ffmpeg": archive("ffmpeg-9.0.2", {"configure": b"#!/bin/sh\nexit 99\n"}),
            "dav1d": archive("dav1d-1.5.4", {"meson.build": b"# fixture\n"}),
            "meson": wheel({"mesonbuild/__init__.py": b"", "mesonbuild/mesonmain.py": b"# synthetic module\n"}),
            "ninja": wheel({"ninja-1.13.2.data/scripts/ninja": b"synthetic executable"}),
        }
        self.lock = {"schema": 1, "ndk": "29.0.14206865", "api": 30, "source_date_epoch": 1789687190}
        for name, data in self.data.items():
            self.lock[name] = {"filename": name + (".tar.xz" if name in {"ffmpeg", "dav1d"} else ".whl"),
                               "url": "https://fixture.invalid/" + name, "bytes": len(data),
                               "sha256": hashlib.sha256(data).hexdigest(),
                               "version": {"ffmpeg": "9.0.2", "dav1d": "1.5.4", "meson": "1.12.1", "ninja": "1.13.2"}[name]}
        for name in ("seccomp_filter.h", "generate_seccomp_filter.py"):
            (self.native / name).write_bytes((Path(__file__).resolve().parents[1] / "native/media-decoder" / name).read_bytes())
        self.save_lock()
        self.ndk = self.root / "ndk"
        self.ndk.mkdir()
        (self.ndk / "source.properties").write_text("Pkg.Revision = 29.0.14206865\n")
        self.binary = self.ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
        self.binary.mkdir(parents=True)
        for triple, _, _, _, _ in build.ABIS.values():
            for suffix in ("-clang", "-clang++"):
                (self.binary / (triple + "30" + suffix)).write_text("fixture compiler never executed")
        for name in ("llvm-ar", "llvm-nm", "llvm-ranlib", "llvm-strip"):
            (self.binary / name).write_text("fixture tool never executed")
        readelf = self.binary / "llvm-readelf"
        readelf.write_text("#!/bin/sh\nprintf ' 0 (NEEDED) Shared library: [libc.so]\\n'\n")
        readelf.chmod(0o755)
        self.downloads = []
        self.commands = []

    def save_lock(self):
        (self.native / "lock.json").write_text(json.dumps(self.lock))

    def opener(self, url, timeout):
        self.assertEqual(timeout, 120)
        self.downloads.append(url)
        return Response(self.data[url.rsplit("/", 1)[1]], url)

    def fake_command(self, command, *, cwd, env):
        command = [str(value) for value in command]
        self.commands.append(command)
        self.assertEqual("1789687190", env["SOURCE_DATE_EPOCH"])
        self.assertEqual("C", env["LC_ALL"])
        self.assertTrue(env["NINJA"].endswith("/tools/ninja-1.13.2.data/scripts/ninja"))
        if "setup" in command:
            self.assertTrue(command[0].endswith("/tools/meson-pinned"))
            self.assertEqual(" -IB", Path(command[0]).read_text().splitlines()[0][-4:])
        if "-C" in command:
            self.assertEqual(command[0], env["NINJA"])
        if command[0].endswith("/configure"):
            components = "\n".join("#define CONFIG_" + name.upper() + suffix + " 1"
                                   for suffix, names in (("_DECODER", build.DECODERS),
                                                         ("_DEMUXER", build.DEMUXERS),
                                                         ("_PARSER", build.PARSERS)) for name in names)
            (cwd / "config_components.h").write_text(components + "\n")
            (cwd / "config.h").write_text('#define FFMPEG_CONFIGURATION "synthetic-host-path"\n')
        elif "-shared" in command:
            destination = Path(command[command.index("-o") + 1])
            abi = next(abi for abi, (triple, _, _, _, _) in build.ABIS.items() if triple in command[0])
            header = bytearray(64)
            header[:4] = b"\x7fELF"
            header[4] = build.ABIS[abi][4]
            header[5] = 1
            struct.pack_into("<H", header, 18, build.ABIS[abi][3])
            destination.write_bytes(header)

    def prepare(self, output=None, abis=None):
        with patch.dict(os.environ, {"GITHUB_ACTIONS": "true"}):
            return build.prepare(self.ndk, output or self.root / "output", abis or ["arm64-v8a"],
                                 opener=self.opener, command=self.fake_command)

    def test_stale_or_changed_seccomp_program_cannot_reuse_a_build_identity(self):
        first = build.cache_key(["arm64-v8a"])
        self.assertFalse((self.native / "__pycache__").exists())
        with (self.native / "generate_seccomp_filter.py").open("a") as output:
            output.write("\n# reviewed source revision\n")
        self.assertNotEqual(first, build.cache_key(["arm64-v8a"]))
        with (self.native / "seccomp_filter.h").open("a") as output:
            output.write("// fabricated stale header\n")
        with self.assertRaisesRegex(ValueError, "seccomp header differs"):
            build.cache_key(["arm64-v8a"])
        self.assertEqual([], self.downloads)
        self.assertEqual([], self.commands)

    def test_compiled_pcm_decoders_have_matching_runtime_codec_admission(self):
        source = (Path(__file__).resolve().parents[1] / "native/media-decoder/media_decoder.cpp").read_text()
        admission = source.split("bool allowedAudio(AVCodecID id) {", 1)[1].split("void selectTrack", 1)[0]
        for decoder in build.DECODERS:
            if decoder.startswith("pcm_"):
                self.assertIn("case AV_CODEC_ID_" + decoder.upper() + ":", admission)

    def test_fake_compiler_builds_all_abis_without_github_credentials(self):
        with patch.dict(os.environ, {"GITHUB_ACTIONS": "false"}):
            manifest = build.prepare(self.ndk, self.root / "output", list(build.ABIS), opener=self.opener,
                                     command=self.fake_command)
        self.assertEqual(list(build.ABIS), [item["abi"] for item in manifest["artifacts"]])
        self.assertEqual(4, len([command for command in self.commands if "-shared" in command]))
        self.assertEqual(4, len(self.downloads))

    def test_invalid_platform_is_refused_before_any_transport_or_compiler(self):
        for system, machine in (("Darwin", "x86_64"), ("Linux", "aarch64")):
            with self.subTest(system=system, machine=machine), \
                    patch.object(build.platform, "system", return_value=system), \
                    patch.object(build.platform, "machine", return_value=machine):
                with self.assertRaisesRegex(ValueError, "Linux x86_64 build host"):
                    build.prepare(self.ndk, self.root / "out", ["arm64-v8a"], opener=self.opener,
                                  command=self.fake_command)
        self.assertEqual([], self.downloads)
        self.assertEqual([], self.commands)

    def test_malformed_source_pin_is_refused_before_any_transport_or_compiler(self):
        self.lock["ffmpeg"]["sha256"] = "invalid-source-digest"
        self.save_lock()
        with self.assertRaisesRegex(ValueError, "invalid pinned source"):
            build.prepare(self.ndk, self.root / "out", ["arm64-v8a"], opener=self.opener,
                          command=self.fake_command)
        self.assertEqual([], self.downloads)
        self.assertEqual([], self.commands)

    def test_pinned_ndk_and_abi_required_before_any_transport(self):
        (self.ndk / "source.properties").write_text("Pkg.Revision = 28.0.0\n")
        with self.assertRaisesRegex(ValueError, "revision differs"):
            self.prepare()
        self.assertEqual([], self.downloads)
        for abis in (["mips"], ["arm64-v8a", "arm64-v8a"]):
            with patch.dict(os.environ, {"GITHUB_ACTIONS": "true"}):
                with self.assertRaisesRegex(ValueError, "unique supported"):
                    build.prepare(self.ndk, self.root / "out", abis)

    def test_all_abi_real_recipes_keep_runtime_capabilities_disabled(self):
        for abi in build.ABIS:
            args, flags = build.configuration(self.ndk, abi, self.lock, self.root / "build", self.root / "install",
                                              self.root / "dav1d", self.root / "pkg-config")
            for value in ("--disable-network", "--disable-protocols", "--disable-autodetect", "--disable-programs",
                          "--disable-hwaccels", "--disable-mediacodec", "--disable-jni", "--disable-encoders",
                          "--disable-muxers", "--disable-filters", "--disable-gpl", "--disable-nonfree"):
                self.assertIn(value, args)
            self.assertIn("--enable-decoder=" + ",".join(build.DECODERS), args)
            self.assertIn("--enable-demuxer=" + ",".join(build.DEMUXERS), args)
            self.assertIn("-ffile-prefix-map=", flags)
            self.assertEqual(abi in {"x86", "x86_64"}, "--disable-x86asm" in args)

    def test_fake_compiler_beneath_real_build_guards_produces_and_reuses_bound_output(self):
        manifest = self.prepare()
        self.assertEqual(1, len(manifest["artifacts"]))
        self.assertEqual("arm64-v8a", manifest["artifacts"][0]["abi"])
        self.assertTrue(any("--wrap-mode=nodownload" in command for command in self.commands))
        self.assertTrue(any("-Dbitdepths=8,16" in command for command in self.commands))
        self.assertTrue(any("-Wl,-z,max-page-size=16384" in command for command in self.commands))
        count = len(self.commands)
        self.assertEqual(manifest, self.prepare())
        self.assertEqual(count, len(self.commands))
        library = self.root / "output/jniLibs/arm64-v8a/libmedia_decoder.so"
        library.write_bytes(b"changed output")
        with self.assertRaisesRegex(ValueError, "cached decoder output differs"):
            self.prepare()

    def test_changed_wrapper_or_abi_cannot_reuse_cached_native_outputs(self):
        self.prepare()
        (self.native / "media_decoder.cpp").write_text("changed wrapper")
        with self.assertRaisesRegex(ValueError, "input identity differs"):
            self.prepare()
        with self.assertRaisesRegex(ValueError, "input identity differs"):
            self.prepare(abis=["x86_64"])

    def test_existing_partial_build_refuses_to_regenerate(self):
        output = self.root / "output"
        (output / "tools").mkdir(parents=True)
        with self.assertRaisesRegex(ValueError, "unfinished decoder build"):
            self.prepare(output)
        self.assertEqual([], self.commands)

    def test_fetch_checks_exact_digest_size_https_and_preserves_partial_intent(self):
        for mode in ("digest", "oversize", "short", "http"):
            with self.subTest(mode=mode):
                directory = self.root / mode
                directory.mkdir()
                data = b"correct"
                item = {"filename": "source.xz", "url": "https://fixture.invalid/source",
                        "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
                wrong = b"incorrc" if mode == "digest" else data + b"x" if mode == "oversize" else data[:2]
                opener = lambda *args, **kwargs: Response(data if mode == "http" else wrong,
                                                         "http://fixture.invalid/file" if mode == "http" else item["url"])
                with self.assertRaises(ValueError):
                    build.fetch(item, directory, opener)
                self.assertTrue((directory / "source.xz.partial").exists())
                with self.assertRaisesRegex(ValueError, "unfinished dependency"):
                    build.fetch(item, directory, opener)

    def test_cached_download_is_hashed_before_reuse(self):
        directory = self.root / "downloads"
        directory.mkdir()
        item = self.lock["dav1d"]
        path = build.fetch(item, directory, self.opener)
        self.assertEqual(path, build.fetch(item, directory, self.opener))
        self.assertEqual(1, len(self.downloads))
        path.write_bytes(b"bad cached source")
        with self.assertRaisesRegex(ValueError, "cached dependency"):
            build.fetch(item, directory, self.opener)

    def test_archive_traversal_and_links_refused(self):
        for name, link in (("../outside", False), ("safe/link", True)):
            path = self.root / ("link.xz" if link else "traversal.xz")
            with tarfile.open(path, "w:xz") as result:
                member = tarfile.TarInfo(name)
                if link:
                    member.type = tarfile.SYMTYPE
                    member.linkname = "/outside"
                result.addfile(member)
            with self.assertRaisesRegex(ValueError, "unsafe source"):
                build.extract_source(path, self.root / "extract", "safe")
        self.assertFalse((self.root / "outside").exists())

    def test_component_audit_rejects_added_codec_or_protocol_and_missing_required_codec(self):
        path = self.root / "config_components.h"
        original = "\n".join("#define CONFIG_" + name.upper() + suffix + " 1"
                             for suffix, names in (("_DECODER", build.DECODERS), ("_DEMUXER", build.DEMUXERS),
                                                   ("_PARSER", build.PARSERS)) for name in names) + "\n"
        path.write_text(original)
        build.audit_components(path)
        for changed in (original + "#define CONFIG_HTTP_PROTOCOL 1\n", original + "#define CONFIG_MJPEG_DECODER 1\n",
                        original.replace("#define CONFIG_H264_DECODER 1", "#define CONFIG_H264_DECODER 0")):
            path.write_text(changed)
            with self.assertRaises(ValueError):
                build.audit_components(path)

    def test_elf_audit_refuses_wrong_machine_and_unplanned_shared_library(self):
        self.prepare()
        library = self.root / "output/jniLibs/arm64-v8a/libmedia_decoder.so"
        with self.assertRaisesRegex(ValueError, "ELF ABI"):
            build.audit_elf(library, "x86_64", "")
        with self.assertRaisesRegex(ValueError, "unexpected dynamic"):
            build.audit_elf(library, "arm64-v8a", "Shared library: [libavformat.so]")

    def test_configuration_string_normalized_without_removing_enabled_flags(self):
        path = self.root / "config.h"
        path.write_text('#define FFMPEG_CONFIGURATION "host"\n#define OTHER 1\n')
        build.normalize_configuration(path, ["--disable-network", "--prefix=" + str(self.root / "work/install"),
                                              "--cc=" + str(self.ndk / "clang")], self.root / "work", self.ndk)
        text = path.read_text()
        self.assertNotIn(str(self.root), text)
        self.assertIn("--disable-network", text)
        self.assertIn("#define OTHER 1", text)

    def test_bounded_pkg_config_accepts_only_pinned_dav1d(self):
        script = self.root / "pkg-config"
        build.write_pkg_config(script, self.root / "dav1d-install", "1.5.4")
        result = subprocess.run([script, "--modversion", "dav1d"], capture_output=True, text=True, check=True)
        self.assertEqual("1.5.4\n", result.stdout)
        denied = subprocess.run([script, "--libs", "libnetwork"], capture_output=True)
        self.assertNotEqual(0, denied.returncode)


if __name__ == "__main__":
    unittest.main()
