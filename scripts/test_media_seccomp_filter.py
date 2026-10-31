"""Evaluate the actual generated production sock_filter records without a native compiler."""
import runpy
from pathlib import Path
import re
import struct
import unittest

NATIVE = Path(__file__).resolve().parents[1] / "native/media-decoder"
# Independent Linux UAPI policy expectations, cross-checked by NDK static_asserts when built.
EXPECTED = {
    "arm64-v8a": (0xC00000B7, 220, (435, 221, 281)),
    "armeabi-v7a": (0x40000028, 120, (2, 190, 435, 11, 387)),
    "x86": (0x40000003, 120, (2, 190, 435, 11, 358)),
    "x86_64": (0xC000003E, 56, (57, 58, 435, 59, 322)),
}
ALLOW, DENY, KILL = 0x7FFF0000, 0x50001, 0x80000000
THREAD, VM, SIGHAND = 0x10000, 0x100, 0x800


def records():
    text = (NATIVE / "seccomp_filter.h").read_text()
    result = {}
    for abi, body in re.findall(r"// ABI ([^\n]+)\n(.*?)(?=\n#elif|\n#else)", text, re.S):
        result[abi] = [tuple(int(value, 0) for value in row) for row in re.findall(
            r"\{(0x[0-9a-f]+), (\d+), (\d+), (0x[0-9a-f]+)U\}", body)]
    if set(result) != set(EXPECTED):
        raise ValueError("missing or unexpected compiled filter ABI")
    return result


def evaluate(program, nr, arch, flags=0):
    # seccomp_data is 64 bytes: nr, arch, instruction_pointer, six 64-bit args.
    data = struct.pack("<IIQ6Q", nr & 0xFFFFFFFF, arch, 0, flags, 0, 0, 0, 0, 0)
    pc, accumulator = 0, 0
    for _ in range(len(program) + 1):
        if not 0 <= pc < len(program):
            raise ValueError("production BPF jumped outside its program")
        code, yes, no, value = program[pc]
        pc += 1
        if code == 0x20:  # BPF_LD | BPF_W | BPF_ABS
            accumulator = struct.unpack_from("<I", data, value)[0]
        elif code == 0x15:  # BPF_JMP | BPF_JEQ | BPF_K
            pc += yes if accumulator == value else no
        elif code == 0x45:  # BPF_JMP | BPF_JSET | BPF_K
            pc += yes if accumulator & value else no
        elif code == 0x54:  # BPF_ALU | BPF_AND | BPF_K
            accumulator &= value
        elif code == 0x06:  # BPF_RET | BPF_K
            return value
        else:
            raise ValueError("unreviewed production BPF instruction")
    raise ValueError("production BPF did not terminate")


class MediaSeccompTests(unittest.TestCase):
    def test_generated_bytes_equal_the_committed_production_header(self):
        generator = runpy.run_path(str(NATIVE / "generate_seccomp_filter.py"))
        self.assertEqual((NATIVE / "seccomp_filter.h").read_text(), generator["header"]())


    def test_wrong_architecture_is_killed_before_any_syscall_admission(self):
        for abi, program in records().items():
            arch = EXPECTED[abi][0]
            for other in {0, 0xFFFFFFFF, *(value[0] for value in EXPECTED.values())} - {arch}:
                self.assertEqual(KILL, evaluate(program, 0, other))
                self.assertEqual(KILL, evaluate(program, EXPECTED[abi][1], other, THREAD | VM | SIGHAND))

    def test_fork_vfork_clone3_and_exec_are_refused_for_each_real_abi(self):
        for abi, program in records().items():
            arch, _, forbidden = EXPECTED[abi]
            for nr in forbidden:
                for flags in (0, THREAD | VM | SIGHAND, 0xFFFFFFFFFFFFFFFF):
                    with self.subTest(abi=abi, nr=nr, flags=flags):
                        self.assertEqual(DENY, evaluate(program, nr, arch, flags))

    def test_every_missing_thread_sharing_bit_refuses_clone(self):
        bits = (THREAD, VM, SIGHAND)
        for abi, program in records().items():
            arch, clone, _ = EXPECTED[abi]
            for subset in range(7):
                flags = sum(bit for index, bit in enumerate(bits) if subset & (1 << index))
                for upper in (0, 0xFFFFFFFF00000000):
                    self.assertEqual(DENY, evaluate(program, clone, arch, flags | upper))

    def test_pthread_clone_flags_and_other_syscalls_retain_platform_policy(self):
        required = THREAD | VM | SIGHAND
        for abi, program in records().items():
            arch, clone, forbidden = EXPECTED[abi]
            for flags in (required, required | 0x3D0E00, required | 0xFFFFFFFF00000000):
                self.assertEqual(ALLOW, evaluate(program, clone, arch, flags))
            # This filter adds restrictions; Android's existing filter still decides these calls.
            for nr in set(range(1024)) - {*forbidden, clone}:
                self.assertEqual(ALLOW, evaluate(program, nr, arch))

    def test_x32_numbers_cannot_bypass_native_x86_64_policy(self):
        program = records()["x86_64"]
        for nr in range(1024):
            self.assertEqual(DENY, evaluate(program, nr | 0x40000000, 0xC000003E, THREAD | VM | SIGHAND))

    def test_installer_is_mandatory_tsync_with_exact_zero_and_exported_onload(self):
        source = (NATIVE / "media_decoder.cpp").read_text()
        installer = source.split("bool installProcessRestriction() {", 1)[1].split("int deniedExecErrno", 1)[0]
        self.assertIn("prctl(PR_SET_NO_NEW_PRIVS, 1L, 0L, 0L, 0L) != 0", installer)
        self.assertIn("syscall(__NR_seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_TSYNC, &program) != 0", installer)
        self.assertLess(installer.index("PR_SET_NO_NEW_PRIVS"), installer.index("SECCOMP_FILTER_FLAG_TSYNC"))
        self.assertNotIn("PR_SET_SECCOMP", source)
        self.assertIn("return installProcessRestriction() ? JNI_VERSION_1_6 : JNI_ERR;", source)
        open_body = source.split("NativeMediaEngine_nativeOpen(", 1)[1]
        self.assertLess(open_body.index("media_process_restriction_missing"), open_body.index("avformat"))
        self.assertIn("JNI_OnLoad;", (NATIVE / "exports.map").read_text())
        self.assertIn("static_assert(__NR_clone == kSandboxClone", source)
        self.assertIn("offsetof(seccomp_data, args) == 16", source)

    def test_safe_diagnostics_never_attempt_fork_vfork_or_a_valid_exec(self):
        source = (NATIVE / "media_decoder.cpp").read_text()
        self.assertIn("syscall(__NR_execve, nullptr, nullptr, nullptr)", source)
        self.assertIn("syscall(__NR_clone3, nullptr, 0)", source)
        self.assertIn("pthread_create(&thread, nullptr, collectSandboxThreadStatus, &child)", source)
        self.assertNotRegex(source, r"syscall\(__NR_(?:fork|vfork|clone),")
        self.assertIn("while (calls < 8 && !diagnosticCalls.compare_exchange_weak", source)


if __name__ == "__main__":
    unittest.main()
