#!/usr/bin/env python3
"""Build the pinned selected-descriptor decoder on Linux x86_64, or print its plan.

No platform codec, URL protocol or secondary-source opener is included. Source
archives and Python build-tool wheels are pinned in native/media-decoder/lock.json.
"""
import argparse
import hashlib
import runpy
import json
import os
from pathlib import Path
import platform
import re
import shutil
import struct
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
NATIVE = ROOT / "native/media-decoder"
ABIS = {
    "arm64-v8a": ("aarch64-linux-android", "aarch64", "armv8-a", 183, 2),
    "armeabi-v7a": ("armv7a-linux-androideabi", "arm", "armv7-a", 40, 1),
    "x86": ("i686-linux-android", "x86", "i686", 3, 1),
    "x86_64": ("x86_64-linux-android", "x86_64", "x86-64", 62, 2),
}
DECODERS = (
    "h264", "hevc", "vp8", "vp9", "libdav1d", "aac", "opus", "vorbis", "mp3float", "flac",
    "amrnb", "amrwb", "pcm_u8", "pcm_s8", "pcm_s16le", "pcm_s16be", "pcm_s24le", "pcm_s24be",
    "pcm_s32le", "pcm_s32be", "pcm_f32le", "pcm_f32be", "pcm_f64le", "pcm_f64be", "pcm_alaw", "pcm_mulaw",
)
DEMUXERS = ("mov", "matroska", "ogg", "wav", "mp3", "aac", "flac", "amr")
PARSERS = ("h264", "hevc", "vp8", "vp9", "av1", "aac", "aac_latm", "opus", "vorbis", "mpegaudio", "flac")
ALLOWED_NEEDED = {"libc.so", "libm.so", "libdl.so"}


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def verify_seccomp_header():
    # run_path executes the reviewed pure generator without writing source-tree bytecode.
    generator = runpy.run_path(str(NATIVE / "generate_seccomp_filter.py"))
    require((NATIVE / "seccomp_filter.h").read_text() == generator["header"](),
            "seccomp header differs from reviewed generator")


def cache_key(abis):
    require(abis and len(set(abis)) == len(abis) and all(abi in ABIS for abi in abis), "unique supported ABIs required")
    verify_seccomp_header()
    values = {"abis": sorted(abis), "inputs": {name: sha(NATIVE / name)
              for name in ("lock.json", "media_decoder.cpp", "exports.map", "seccomp_filter.h", "generate_seccomp_filter.py")}, "builder": sha(__file__)}
    return hashlib.sha256(json.dumps(values, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def run(command, *, cwd, env):
    subprocess.run([str(value) for value in command], cwd=cwd, env=env, check=True)


def load_lock():
    value = json.loads((NATIVE / "lock.json").read_text())
    require(value.get("schema") == 1 and value.get("ndk") == "29.0.14206865" and value.get("api") == 30,
            "unsupported decoder toolchain lock")
    for name in ("ffmpeg", "dav1d", "meson", "ninja"):
        item = value[name]
        require(re.fullmatch(r"[0-9a-f]{64}", item["sha256"]) and
                item["url"].startswith("https://") and type(item["bytes"]) is int and item["bytes"] > 0,
                "invalid pinned source/tool: " + name)
    return value


def fetch(item, directory, opener=urllib.request.urlopen):
    """One bounded HTTPS attempt; an interrupted transfer is never used as a cache hit."""
    path = directory / item["filename"]
    if path.exists():
        require(path.is_file() and not path.is_symlink() and path.stat().st_size == item["bytes"] and
                sha(path) == item["sha256"], "cached dependency bytes differ")
        return path
    temporary = path.with_suffix(path.suffix + ".partial")
    require(not temporary.exists(), "unfinished dependency transfer requires inspection")
    try:
        with opener(item["url"], timeout=120) as response, temporary.open("xb") as output:
            require(response.geturl().startswith("https://"), "dependency redirect left HTTPS")
            digest = hashlib.sha256()
            size = 0
            while True:
                chunk = response.read(min(65536, item["bytes"] + 1 - size))
                if not chunk:
                    break
                size += len(chunk)
                require(size <= item["bytes"], "dependency exceeds pinned size")
                digest.update(chunk)
                output.write(chunk)
            require(size == item["bytes"] and digest.hexdigest() == item["sha256"], "dependency digest differs")
            output.flush()
            os.fsync(output.fileno())
        temporary.replace(path)
    except BaseException:
        # Preserve partial bytes so a new invocation cannot disguise an uncertain transfer.
        raise
    return path


def extract_source(archive, destination, prefix):
    with tarfile.open(archive, "r:xz") as source:
        members = source.getmembers()
        require(0 < len(members) <= 20000, "source archive entry count refused")
        for member in members:
            path = Path(member.name)
            require(not path.is_absolute() and ".." not in path.parts and path.parts[0] == prefix and
                    (member.isfile() or member.isdir()) and member.size <= 64 * 1024 * 1024,
                    "unsafe source archive entry")
        require(sum(member.size for member in members) <= 256 * 1024 * 1024, "source extraction budget refused")
        source.extractall(destination, members=members, filter="data")
    return destination / prefix


def extract_tools(wheels, destination):
    destination.mkdir()
    for wheel in wheels:
        with zipfile.ZipFile(wheel) as source:
            entries = source.infolist()
            require(sum(item.file_size for item in entries) <= 32 * 1024 * 1024, "tool extraction budget refused")
            for item in entries:
                path = Path(item.filename)
                require(not path.is_absolute() and ".." not in path.parts and
                        (item.external_attr >> 16) & 0o170000 != 0o120000, "unsafe tool wheel entry")
            source.extractall(destination)
    binaries = list(destination.glob("ninja/data/bin/ninja"))
    require(len(binaries) == 1 and binaries[0].is_file(), "pinned ninja executable missing")
    binaries[0].chmod(0o755)
    return binaries[0]


def toolchain(ndk, abi, lock):
    require(abi in ABIS, "unsupported Android ABI")
    require(ndk.is_dir() and not ndk.is_symlink(), "real pinned NDK directory required")
    properties = (ndk / "source.properties").read_text()
    require(re.search(r"^Pkg\.Revision\s*=\s*" + re.escape(lock["ndk"]) + r"\s*$", properties, re.M),
            "Android NDK revision differs from lock")
    binary = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    triple, arch, cpu, _, _ = ABIS[abi]
    compiler = binary / (triple + str(lock["api"]) + "-clang")
    for name in (compiler.name, compiler.name + "++", "llvm-ar", "llvm-nm", "llvm-ranlib", "llvm-strip", "llvm-readelf"):
        require((binary / name).is_file(), "pinned NDK tool missing: " + name)
    return binary, compiler, arch, cpu


def configuration(ndk, abi, lock, build, prefix, dav1d_prefix, pkg_config):
    binary, compiler, arch, cpu = toolchain(ndk, abi, lock)
    flags = "-O2 -fPIC -fstack-protector-strong -D_FORTIFY_SOURCE=2 " + \
            "-ffile-prefix-map=" + str(build) + "=/media-build -fdebug-prefix-map=" + str(build) + "=/media-build " + \
            "-ffile-prefix-map=" + str(ROOT) + "=/src -fdebug-prefix-map=" + str(ROOT) + "=/src"
    arguments = [
        "--target-os=android", "--arch=" + arch, "--cpu=" + cpu, "--enable-cross-compile",
        "--cc=" + str(compiler), "--cxx=" + str(compiler) + "++", "--ar=" + str(binary / "llvm-ar"),
        "--nm=" + str(binary / "llvm-nm"), "--ranlib=" + str(binary / "llvm-ranlib"),
        "--strip=" + str(binary / "llvm-strip"), "--sysroot=" + str(binary.parent / "sysroot"),
        "--prefix=" + str(prefix), "--pkg-config=" + str(pkg_config),
        "--extra-cflags=" + flags, "--extra-ldflags=-Wl,-z,max-page-size=16384", "--optflags=-O2",
        "--disable-everything", "--disable-autodetect", "--disable-programs", "--disable-doc",
        "--disable-debug", "--disable-shared", "--enable-static", "--enable-pic", "--enable-small",
        "--disable-avdevice", "--disable-avfilter", "--disable-network", "--disable-protocols",
        "--disable-encoders", "--disable-muxers", "--disable-filters", "--disable-bsfs",
        "--disable-hwaccels", "--disable-jni", "--disable-mediacodec", "--disable-gpl", "--disable-nonfree",
        "--disable-version3", "--disable-zlib", "--disable-bzlib", "--disable-lzma", "--disable-iconv",
        "--disable-symver", "--enable-pthreads", "--enable-avcodec", "--enable-avformat", "--enable-avutil",
        "--enable-swscale", "--enable-swresample", "--enable-libdav1d",
        "--enable-decoder=" + ",".join(DECODERS), "--enable-demuxer=" + ",".join(DEMUXERS),
        "--enable-parser=" + ",".join(PARSERS),
    ]
    if abi in {"x86", "x86_64"}:
        arguments.append("--disable-x86asm")
    return arguments, flags


def audit_components(path):
    text = path.read_text()
    enabled = set(re.findall(r"^#define CONFIG_([A-Z0-9_]+) 1$", text, re.M))
    for suffix, allowed in (("_DECODER", DECODERS), ("_DEMUXER", DEMUXERS), ("_PARSER", PARSERS)):
        actual = {name[:-len(suffix)].lower() for name in enabled if name.endswith(suffix)}
        require(actual == set(allowed), "compiled component allowlist differs: " + suffix)
    require(not any(name.endswith(("_ENCODER", "_MUXER", "_PROTOCOL", "_FILTER", "_HWACCEL", "_INDEV", "_OUTDEV"))
                    for name in enabled), "unapproved parser/output/runtime capability enabled")


def audit_elf(path, abi, dynamic):
    header = path.read_bytes()[:20]
    require(len(header) == 20 and header[:4] == b"\x7fELF" and header[4] == ABIS[abi][4] and header[5] == 1 and
            struct.unpack_from("<H", header, 18)[0] == ABIS[abi][3], "decoder ELF ABI differs")
    needed = set(re.findall(r"Shared library: \[([^\]]+)\]", dynamic))
    require(needed <= ALLOWED_NEEDED, "unexpected dynamic dependency: " + ",".join(sorted(needed - ALLOWED_NEEDED)))


def write_pkg_config(path, dav1d_prefix, version):
    # FFmpeg only needs these pkg-config queries for its one explicit external library.
    program = """#!PYTHON
import sys
args = sys.argv[1:]
if not any(value == 'dav1d' or value.startswith('dav1d ') for value in args):
    sys.exit(1)
if '--variable=libdir' in args:
    print(PREFIX + '/lib')
elif '--modversion' in args:
    print(VERSION)
elif '--cflags' in args:
    print('-I' + PREFIX + '/include')
elif '--libs' in args:
    print('-L' + PREFIX + '/lib -ldav1d -lpthread -lm')
elif '--exists' not in args and not any(value.startswith('--atleast-version') for value in args):
    sys.exit(1)
""".replace("PYTHON", sys.executable).replace("PREFIX", repr(str(dav1d_prefix))).replace("VERSION", repr(version))
    path.write_text(program)
    path.chmod(0o755)


def normalize_configuration(path, arguments, work, ndk):
    """Keep libav's embedded recipe while removing host-specific build locations."""
    normalized = " ".join(arguments).replace(str(work), "/media-build").replace(str(ndk), "/android-ndk")
    normalized = normalized.replace(str(ROOT), "/src")
    text = path.read_text()
    replacement = "#define FFMPEG_CONFIGURATION " + json.dumps(normalized)
    rewritten, count = re.subn(r"^#define FFMPEG_CONFIGURATION .*?$", lambda _: replacement, text, flags=re.M)
    require(count == 1, "FFmpeg embedded configuration declaration differs")
    path.write_text(rewritten)


def build_abi(ndk, abi, lock, sources, tools, output, jobs, command=run):
    destination = output / "jniLibs" / abi
    require(not destination.exists(), "ABI output already exists; inspect provenance before rebuilding")
    with tempfile.TemporaryDirectory(prefix="media-" + abi + "-", dir=output) as temporary:
        work = Path(temporary)
        ffmpeg = extract_source(sources["ffmpeg"], work, "ffmpeg-" + lock["ffmpeg"]["version"])
        dav1d = extract_source(sources["dav1d"], work, "dav1d-" + lock["dav1d"]["version"])
        ffmpeg_build = work / "ffmpeg-build"
        ffmpeg_build.mkdir()
        install = work / "install"
        dav1d_install = work / "dav1d-install"
        pkg_config = work / "pkg-config-dav1d"
        write_pkg_config(pkg_config, dav1d_install, lock["dav1d"]["version"])
        arguments, flags = configuration(ndk, abi, lock, work, install, dav1d_install, pkg_config)
        binary, compiler, arch, cpu = toolchain(ndk, abi, lock)
        cross = work / "android.ini"
        cross.write_text(
            "[binaries]\nc = " + repr(str(compiler)) + "\nar = " + repr(str(binary / "llvm-ar")) +
            "\nstrip = " + repr(str(binary / "llvm-strip")) + "\n[host_machine]\nsystem = 'android'\n" +
            "cpu_family = " + repr(arch) + "\ncpu = " + repr(cpu) + "\nendian = 'little'\n" +
            "[properties]\nneeds_exe_wrapper = true\n[built-in options]\nc_args = " + repr(flags.split()) +
            "\nc_link_args = ['-Wl,-z,max-page-size=16384']\n")
        env = {key: value for key, value in os.environ.items()
               if key not in {"CFLAGS", "CXXFLAGS", "CPPFLAGS", "LDFLAGS", "PKG_CONFIG_PATH", "PYTHONPATH", "PYTHONHOME"}}
        env.update(SOURCE_DATE_EPOCH=str(lock["source_date_epoch"]), LC_ALL="C", TZ="UTC", ZERO_AR_DATE="1",
                   NINJA=str(tools / "ninja/data/bin/ninja"), PKG_CONFIG_LIBDIR=str(dav1d_install / "lib/pkgconfig"))
        meson = [sys.executable, "-I", "-c",
                 "import sys;sys.path.insert(0," + repr(str(tools)) + ");from mesonbuild.mesonmain import main;sys.exit(main())"]
        dav1d_build = work / "dav1d-build"
        command(meson + ["setup", str(dav1d_build), str(dav1d), "--cross-file=" + str(cross),
                         "--prefix=" + str(dav1d_install), "--libdir=lib", "--wrap-mode=nodownload",
                         "-Ddefault_library=static", "-Db_staticpic=true", "-Dbuildtype=release", "-Db_ndebug=true",
                         "-Denable_tools=false", "-Denable_tests=false", "-Denable_examples=false", "-Denable_docs=false",
                         "-Denable_asm=" + ("false" if abi in {"x86", "x86_64"} else "true"), "-Dbitdepths=8,16"],
                cwd=work, env=env)
        command([tools / "ninja/data/bin/ninja", "-C", dav1d_build, "-j", str(jobs), "install"], cwd=work, env=env)
        command([ffmpeg / "configure", *arguments], cwd=ffmpeg_build, env=env)
        audit_components(ffmpeg_build / "config_components.h")
        normalize_configuration(ffmpeg_build / "config.h", arguments, work, ndk)
        command(["make", "-j", str(jobs), "install"], cwd=ffmpeg_build, env=env)
        library = work / "libmedia_decoder.so"
        command([str(compiler) + "++", "-std=c++17", *flags.split(), "-fvisibility=hidden", "-shared",
                 "-static-libstdc++", "-Wl,-z,max-page-size=16384", "-Wl,-z,relro,-z,now,--no-undefined",
                 "-Wl,-soname,libmedia_decoder.so",
                 "-Wl,--version-script=" + str(NATIVE / "exports.map"), "-I" + str(install / "include"),
                 NATIVE / "media_decoder.cpp", "-o", library, "-Wl,--start-group",
                 *[install / ("lib/lib" + name + ".a") for name in ("avformat", "avcodec", "swscale", "swresample", "avutil")],
                 dav1d_install / "lib/libdav1d.a", "-Wl,--end-group", "-lm"], cwd=work, env=env)
        command([binary / "llvm-strip", "--strip-unneeded", library], cwd=work, env=env)
        dynamic = subprocess.check_output([binary / "llvm-readelf", "--dynamic", library], text=True, env=env)
        audit_elf(library, abi, dynamic)
        destination.mkdir(parents=True)
        shutil.copyfile(library, destination / library.name)
        (output / (abi + "-ffmpeg-config.h")).write_bytes((ffmpeg_build / "config.h").read_bytes())
        (output / (abi + "-ffmpeg-components.h")).write_bytes((ffmpeg_build / "config_components.h").read_bytes())
        return {"abi": abi, "sha256": sha(destination / library.name),
                "configure": [argument.replace(str(work), "/media-build").replace(str(ndk), "/android-ndk").replace(str(ROOT), "/src")
                              for argument in arguments],
                "elf_needed": sorted(set(re.findall(r"Shared library: \[([^\]]+)\]", dynamic)))}


def prepare(ndk, output, abis, *, jobs=2, opener=urllib.request.urlopen, command=run):
    lock = load_lock()
    require(platform.system() == "Linux" and platform.machine() == "x86_64", "Linux x86_64 build host required")
    import fcntl  # Linux-only locking is unnecessary for portable --cache-key/--plan inspection.
    require(type(jobs) is int and 1 <= jobs <= 8, "build worker count refused")
    require(abis and len(set(abis)) == len(abis) and all(abi in ABIS for abi in abis), "unique supported ABIs required")
    for abi in abis:
        toolchain(ndk, abi, lock)
    output.mkdir(parents=True, exist_ok=True)
    require(not output.is_symlink() and not re.search(r"\s", str(output)), "real build directory without whitespace required")
    with (output / ".build.lock").open("a") as stream:
        fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        key = cache_key(abis)
        manifest_path = output / "manifest.json"
        if manifest_path.exists():
            require(not manifest_path.is_symlink(), "decoder manifest symlink refused")
            manifest = json.loads(manifest_path.read_text())
            require(manifest.get("cache_key") == key and type(manifest.get("artifacts")) is list and
                    sorted(item.get("abi", "") for item in manifest["artifacts"]) == sorted(abis),
                    "cached decoder input identity differs")
            for item in manifest["artifacts"]:
                library = output / "jniLibs" / item["abi"] / "libmedia_decoder.so"
                require(library.is_file() and not library.is_symlink() and sha(library) == item.get("sha256"),
                        "cached decoder output differs")
            return manifest
        downloads = output / "downloads"
        downloads.mkdir(exist_ok=True)
        inputs = {name: fetch(lock[name], downloads, opener) for name in ("ffmpeg", "dav1d", "meson", "ninja")}
        tools = output / "tools"
        require(not tools.exists(), "unfinished decoder build requires inspection")
        extract_tools([inputs["meson"], inputs["ninja"]], tools)
        built = [build_abi(ndk, abi, lock, inputs, tools, output, jobs, command) for abi in abis]
        manifest = {"schema": 1, "cache_key": key, "lock_sha256": sha(NATIVE / "lock.json"),
                    "wrapper_sha256": sha(NATIVE / "media_decoder.cpp"), "build_script_sha256": sha(__file__),
                    "seccomp_header_sha256": sha(NATIVE / "seccomp_filter.h"),
                    "seccomp_generator_sha256": sha(NATIVE / "generate_seccomp_filter.py"),
                    "ndk": lock["ndk"], "api": lock["api"], "artifacts": built,
                    "qualification": "source-built outputs; exact-APK runtime and reproducibility checks remain required"}
        (output / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
        return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--abi", action="append", choices=ABIS)
    parser.add_argument("--jobs", type=int, default=2)
    parser.add_argument("--plan", action="store_true")
    parser.add_argument("--cache-key", action="store_true")
    args = parser.parse_args()
    if args.cache_key:
        print(cache_key(args.abi or list(ABIS)))
        return
    if args.plan:
        print(json.dumps({"lock": load_lock(), "abis": args.abi or list(ABIS),
                          "decoders": DECODERS, "demuxers": DEMUXERS, "parsers": PARSERS}, indent=2, sort_keys=True))
        return
    if not args.ndk or not args.output:
        parser.error("--ndk and --output are required for CI compilation")
    result = prepare(args.ndk.resolve(), args.output.absolute(), args.abi or list(ABIS), jobs=args.jobs)
    print(json.dumps(result, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
