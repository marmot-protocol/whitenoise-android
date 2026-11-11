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
    """Create a deterministic small source archive so extraction guards run without downloading dependencies."""
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode="w:xz") as result:
        for name, data in entries.items():
            member = tarfile.TarInfo(prefix + "/" + name)
            member.size = len(data)
            member.mode = 0o755 if name == "configure" else 0o644
            result.addfile(member, io.BytesIO(data))
    return output.getvalue()


def wheel(entries):
    """Build synthetic wheel bytes for exact path, permission and extraction-budget assertions."""
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as result:
        for name, data in entries.items():
            result.writestr(name, data)
    return output.getvalue()


class Response(io.BytesIO):
    def __init__(self, data, url="https://fixture.invalid/file"):
        """Wrap synthetic dependency bytes with the response URL used by the real download guard."""
        super().__init__(data)
        self.url = url

    def geturl(self):
        """Return the scripted final response URL so redirect admission is tested without network access."""
        return self.url


class PortableInspectionTests(unittest.TestCase):
    def test_cache_key_and_plan_do_not_import_unavailable_fcntl(self):
        """Cache key and plan do not import unavailable fcntl."""
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
        """Stage one synthetic wheel and exercise the real pinned-tool extraction boundary."""
        path = directory / "ninja.whl"
        path.write_bytes(wheel(entries))
        return build.extract_tools([path], directory / "tools")

    def test_pinned_ninja_wheel_script_layout_and_permissions(self):
        # This literal is independently checked against the SHA-pinned wheel's RECORD.
        """Pinned ninja wheel script layout and permissions."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            binary = self.extract(root, {"ninja-1.13.2.data/scripts/ninja": b"synthetic executable"})
            self.assertEqual(root / "tools/ninja-1.13.2.data/scripts/ninja", binary)
            self.assertEqual(b"synthetic executable", binary.read_bytes())
            self.assertEqual(0o755, binary.stat().st_mode & 0o777)

    def test_missing_old_or_other_version_paths_never_fall_back(self):
        """Missing old or other version paths never fall back."""
        for entries in ({}, {"ninja/data/bin/ninja": b"old layout"},
                        {"ninja-1.13.3.data/scripts/ninja": b"different version"}):
            with self.subTest(entries=entries), tempfile.TemporaryDirectory() as temporary:
                with self.assertRaisesRegex(ValueError, "pinned ninja executable missing"):
                    self.extract(Path(temporary), entries)

    def test_tool_wheel_traversal_absolute_and_symlink_entries_refused(self):
        """Tool wheel traversal absolute and symlink entries refused."""
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
        """Tool extraction budget still refused before extract."""
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
        """Persistent launcher isolates initial and repeated invocation."""
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
        """Missing pinned modules refuse before creating launcher."""
        with tempfile.TemporaryDirectory() as temporary:
            tools = Path(temporary)
            with self.assertRaisesRegex(ValueError, "pinned Meson module missing"):
                build.write_meson_launcher(tools)
            self.assertFalse((tools / "meson-pinned").exists())

    def test_deleted_pinned_module_cannot_fall_back_to_poisoned_environment(self):
        """Deleted pinned module cannot fall back to poisoned environment."""
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
        """Install task-owned synthetic native inputs and restore patched build dependencies during fixture cleanup."""
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
        for name, value in (("system", "Linux"), ("machine", "x86_64")):
            host = patch.object(build.platform, name, return_value=value)
            host.start()
            self.addCleanup(host.stop)

    def save_lock(self):
        """Persist the fixture lock under the same path consumed by the real build-policy loader."""
        (self.native / "lock.json").write_text(json.dumps(self.lock))

    def opener(self, url, timeout):
        """Serve only the fixture dependency bytes through the real bounded download interface."""
        self.assertEqual(timeout, 120)
        self.downloads.append(url)
        return Response(self.data[url.rsplit("/", 1)[1]], url)

    def fake_command(self, command, *, cwd, env):
        """Model tool outputs beneath real build guards; this fixture does not compile Android or native code."""
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
        """Call the real preparation orchestrator with synthetic transport/compiler seams and its actual input guards."""
        with patch.dict(os.environ, {"GITHUB_ACTIONS": "true"}):
            return build.prepare(self.ndk, output or self.root / "output", abis or ["arm64-v8a"],
                                 opener=self.opener, command=self.fake_command)

    def test_stale_or_changed_seccomp_program_cannot_reuse_a_build_identity(self):
        """Stale or changed seccomp program cannot reuse a build identity."""
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
        """Compiled pcm decoders have matching runtime codec admission."""
        source = (Path(__file__).resolve().parents[1] / "native/media-decoder/media_decoder.cpp").read_text()
        admission = source.split("bool allowedAudio(AVCodecID id) {", 1)[1].split("void selectTrack", 1)[0]
        for decoder in build.DECODERS:
            if decoder.startswith("pcm_"):
                self.assertIn("case AV_CODEC_ID_" + decoder.upper() + ":", admission)

    def test_fake_compiler_builds_all_abis_without_github_credentials(self):
        """Fake compiler builds all abis without github credentials."""
        with patch.dict(os.environ, {"GITHUB_ACTIONS": "false"}):
            manifest = build.prepare(self.ndk, self.root / "output", list(build.ABIS), opener=self.opener,
                                     command=self.fake_command)
        self.assertEqual(list(build.ABIS), [item["abi"] for item in manifest["artifacts"]])
        self.assertEqual(4, len([command for command in self.commands if "-shared" in command]))
        self.assertEqual(4, len(self.downloads))

    def test_invalid_platform_is_refused_before_any_transport_or_compiler(self):
        """Invalid platform is refused before any transport or compiler."""
        for system, machine in (("Windows", "AMD64"), ("Linux", "aarch64")):
            with self.subTest(system=system, machine=machine), \
                    patch.object(build.platform, "system", return_value=system), \
                    patch.object(build.platform, "machine", return_value=machine):
                with self.assertRaisesRegex(ValueError, "Linux x86_64 build host"):
                    build.prepare(self.ndk, self.root / "out", ["arm64-v8a"], opener=self.opener,
                                  command=self.fake_command)
        self.assertEqual([], self.downloads)
        self.assertEqual([], self.commands)

    def test_macos_verifies_linux_cache_without_ndk_or_build_tools(self):
        """Reuse a Linux-built cache with no NDK and reject every unverified packaging input."""
        manifest = self.prepare()
        output = self.root / "output"
        library = output / "jniLibs/arm64-v8a/libmedia_decoder.so"
        expected = library.read_bytes()
        for machine in ("arm64", "x86_64"):
            with self.subTest(machine=machine), \
                    patch.object(build.platform, "system", return_value="Darwin"), \
                    patch.object(build.platform, "machine", return_value=machine), \
                    patch.object(build, "toolchain", side_effect=AssertionError("macOS resolved an NDK")):
                def reuse(abis):
                    """Exercise cache verification with the selected macOS host and no NDK."""
                    return build.prepare(None, output, abis, opener=self.opener, command=self.fake_command)

                self.assertEqual(manifest, reuse(["arm64-v8a"]))
                with self.assertRaisesRegex(ValueError, "cached decoder input identity differs"):
                    reuse(["x86_64"])
                extra = library.with_name("extra.so")
                extra.write_bytes(expected)
                with self.assertRaisesRegex(ValueError, "cached decoder output differs"):
                    reuse(["arm64-v8a"])
                extra.unlink()
                library.write_bytes(b"changed output")
                with self.assertRaisesRegex(ValueError, "cached decoder output differs"):
                    reuse(["arm64-v8a"])
                library.write_bytes(expected)
        self.assertEqual(4, len(self.downloads))
        self.assertEqual(1, len([command for command in self.commands if "-shared" in command]))

    def test_macos_cache_miss_reports_how_to_build_without_creating_cache(self):
        """A cold macOS build points to the required cache and leaves preparation to Linux."""
        output = self.root / "missing"
        with patch.object(build.platform, "system", return_value="Darwin"):
            with self.assertRaisesRegex(ValueError, "Decoder cache missing.*Linux x86_64") as error:
                build.prepare(None, output, ["arm64-v8a"], opener=self.opener, command=self.fake_command)
        self.assertIn(str(output), str(error.exception))
        self.assertFalse(output.exists())
        self.assertEqual([], self.downloads)
        self.assertEqual([], self.commands)

    def test_malformed_source_pin_is_refused_before_any_transport_or_compiler(self):
        """Malformed source pin is refused before any transport or compiler."""
        self.lock["ffmpeg"]["sha256"] = "invalid-source-digest"
        self.save_lock()
        with self.assertRaisesRegex(ValueError, "invalid pinned source"):
            build.prepare(self.ndk, self.root / "out", ["arm64-v8a"], opener=self.opener,
                          command=self.fake_command)
        self.assertEqual([], self.downloads)
        self.assertEqual([], self.commands)

    def test_pinned_ndk_and_abi_required_before_any_transport(self):
        """Pinned ndk and abi required before any transport."""
        (self.ndk / "source.properties").write_text("Pkg.Revision = 28.0.0\n")
        with self.assertRaisesRegex(ValueError, "revision differs"):
            self.prepare()
        self.assertEqual([], self.downloads)
        for abis in (["mips"], ["arm64-v8a", "arm64-v8a"]):
            with patch.dict(os.environ, {"GITHUB_ACTIONS": "true"}):
                with self.assertRaisesRegex(ValueError, "unique supported"):
                    build.prepare(self.ndk, self.root / "out", abis)

    def test_all_abi_real_recipes_keep_runtime_capabilities_disabled(self):
        """All abi real recipes keep runtime capabilities disabled."""
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

    def test_only_32bit_x86_disables_inline_assembly_for_shared_pic_output(self):
        """Only 32bit x86 disables inline assembly for shared pic output."""
        for abi in build.ABIS:
            with self.subTest(abi=abi):
                args, flags = build.configuration(self.ndk, abi, self.lock, self.root / "build",
                                                  self.root / "install", self.root / "dav1d",
                                                  self.root / "pkg-config")
                self.assertEqual(abi == "x86", "--disable-inline-asm" in args)
                self.assertEqual(abi in {"x86", "x86_64"}, "--disable-x86asm" in args)
                self.assertIn("--enable-pic", args)
                self.assertIn("-fPIC", flags.split())
                self.assertIn("--disable-shared", args)
                self.assertIn("--enable-static", args)
                self.assertIn("--extra-ldflags=-Wl,-z,max-page-size=16384", args)

    def test_fake_compiler_beneath_real_build_guards_produces_and_reuses_bound_output(self):
        """Fake compiler beneath real build guards produces and reuses bound output."""
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
        """Changed wrapper or abi cannot reuse cached native outputs."""
        self.prepare()
        (self.native / "media_decoder.cpp").write_text("changed wrapper")
        with self.assertRaisesRegex(ValueError, "input identity differs"):
            self.prepare()
        with self.assertRaisesRegex(ValueError, "input identity differs"):
            self.prepare(abis=["x86_64"])

    def test_existing_partial_build_refuses_to_regenerate(self):
        """Existing partial build refuses to regenerate."""
        output = self.root / "output"
        tools = output / "tools"
        tools.mkdir(parents=True)
        marker = tools / "preserve-failed-build"
        marker.write_bytes(b"review before removal")
        with self.assertRaisesRegex(ValueError, "unfinished decoder build") as error:
            self.prepare(output)
        self.assertIn(str(tools.resolve()), str(error.exception))
        self.assertIn(str(output.resolve()), str(error.exception))
        self.assertIn("inspect and reconcile", str(error.exception))
        self.assertEqual(b"review before removal", marker.read_bytes())
        self.assertEqual([], self.commands)

    def test_fetch_checks_exact_digest_size_https_and_preserves_partial_intent(self):
        """Fetch checks exact digest size https and preserves partial intent."""
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
                partial = directory / "source.xz.partial"
                self.assertTrue(partial.exists())
                retained = partial.read_bytes()
                def forbidden_opener(*args, **kwargs):
                    """A retained partial must refuse before any further download attempt."""
                    self.fail("retained partial triggered another transport")
                with self.assertRaisesRegex(ValueError, "unfinished dependency") as error:
                    build.fetch(item, directory, forbidden_opener)
                self.assertIn(str(partial.resolve()), str(error.exception))
                self.assertIn("inspect and reconcile", str(error.exception))
                self.assertEqual(retained, partial.read_bytes())

    def test_cached_download_is_hashed_before_reuse(self):
        """Cached download is hashed before reuse."""
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
        """Archive traversal and links refused."""
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
        """Component audit rejects added codec or protocol and missing required codec."""
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
        """Elf audit refuses wrong machine and unplanned shared library."""
        self.prepare()
        library = self.root / "output/jniLibs/arm64-v8a/libmedia_decoder.so"
        with self.assertRaisesRegex(ValueError, "ELF ABI"):
            build.audit_elf(library, "x86_64", "")
        with self.assertRaisesRegex(ValueError, "unexpected dynamic"):
            build.audit_elf(library, "arm64-v8a", "Shared library: [libavformat.so]")

    def test_configuration_string_normalized_without_removing_enabled_flags(self):
        """Configuration string normalized without removing enabled flags."""
        path = self.root / "config.h"
        path.write_text('#define FFMPEG_CONFIGURATION "host"\n#define OTHER 1\n')
        build.normalize_configuration(path, ["--disable-network", "--prefix=" + str(self.root / "work/install"),
                                              "--cc=" + str(self.ndk / "clang")], self.root / "work", self.ndk)
        text = path.read_text()
        self.assertNotIn(str(self.root), text)
        self.assertIn("--disable-network", text)
        self.assertIn("#define OTHER 1", text)

    def test_bounded_pkg_config_accepts_only_pinned_dav1d(self):
        """Bounded pkg config accepts only pinned dav1d."""
        script = self.root / "pkg-config"
        prefix = self.root / "dav1d-install"
        build.write_pkg_config(script, prefix, "1.5.4")
        expected = {
            ("--version",): "whitenoise-dav1d-pkg-config-shim 1\n",
            ("--exists", "--print-errors", "dav1d >= 1.0.0"): "",
            ("--exists", "dav1d"): "",
            ("--modversion", "dav1d"): "1.5.4\n",
            ("--variable=libdir", "dav1d"): str(prefix) + "/lib\n",
            ("--variable=includedir", "dav1d"): str(prefix) + "/include\n",
            ("--cflags", "dav1d"): "-I" + str(prefix) + "/include\n",
            ("--libs", "dav1d"): "-L" + str(prefix) + "/lib -ldav1d -pthread -lm\n",
        }
        for query, stdout in expected.items():
            with self.subTest(query=query):
                result = subprocess.run([script, *query], capture_output=True, text=True, check=True)
                self.assertEqual(stdout, result.stdout)

    def test_bounded_pkg_config_refuses_unknown_queries_libraries_and_constraints(self):
        """Bounded pkg config refuses unknown queries libraries and constraints."""
        script = self.root / "pkg-config"
        build.write_pkg_config(script, self.root / "dav1d-install", "1.5.4")
        for query in ([], ["--libs", "libnetwork"], ["--libs", "dav1d", "libnetwork"],
                      ["--exists", "--print-errors", "dav1d >= 99.0.0"],
                      ["--exists", "--print-errors", "dav1d arbitrary text"],
                      ["--variable=unknown", "dav1d"], ["--unknown", "dav1d"],
                      ["--atleast-version=99", "dav1d"], ["--version", "dav1d"]):
            with self.subTest(query=query):
                denied = subprocess.run([script, *query], capture_output=True)
                self.assertNotEqual(0, denied.returncode)
                self.assertEqual(b"", denied.stdout)

    def test_bounded_pkg_config_checks_the_advertised_dav1d_minimum(self):
        """Bounded pkg config checks the advertised dav1d minimum."""
        for version in ("0.9.9", "1.5.4-custom", "invalid"):
            with self.subTest(version=version):
                path = self.root / ("pkg-config-" + version)
                with self.assertRaisesRegex(ValueError, "dav1d version below required minimum"):
                    build.write_pkg_config(path, self.root / "dav1d-install", version)
                self.assertFalse(path.exists())


if __name__ == "__main__":
    unittest.main()
