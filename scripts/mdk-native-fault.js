// External instrumentation of the immutable published x86_64 binary, only in the disposable CI fixture.
// The host verifies its complete SHA-256 and these instruction bytes before attaching.
const native = Process.getModuleByName('libmarmot_uniffi.so');
const libc = Process.getModuleByName('libc.so');
const mode = FAULT_MODE;
let fired = false;

if (mode === 'crash') {
    const address = native.base.add(0x2cc2660);
    const expected = '415741564154534881ec1801000048833e00';
    const actual = Array.from(new Uint8Array(address.readByteArray(18)))
        .map(value => value.toString(16).padStart(2, '0')).join('');
    if (actual !== expected) throw new Error('Published prepare-entry instruction mismatch');
    Interceptor.attach(address, {
        onEnter(args) {
            if (fired || !this.returnAddress.equals(native.base.add(0x2c2b5c0)) ||
                !args[2].equals(native.base.add(0x6ec0eb)) || args[3].toInt32() !== 229) return;
            fired = true;
            send({event: 'published-before-legacy-delete', returnOffset: '0x2c2b5c0', sqlBytes: 229});
            // The host kills this process while preparation is blocked; the DELETE has not executed.
            recv('never-resume', () => {}).wait();
        }
    });
} else if (mode === 'enospc') {
    const readlink = new NativeFunction(libc.getExportByName('readlink'), 'long', ['pointer', 'pointer', 'ulong']);
    Interceptor.attach(libc.getExportByName('write'), {
        onEnter(args) {
            this.mediaWrite = false;
            if (fired || this.returnAddress.compare(native.base) < 0 ||
                this.returnAddress.compare(native.base.add(native.size)) >= 0) return;
            const path = Memory.allocUtf8String('/proc/self/fd/' + args[0].toInt32());
            const output = Memory.alloc(4096);
            const length = readlink(path, output, 4095).toNumber();
            if (length <= 0) return;
            const file = output.readUtf8String(length).replace('/data/data/', '/data/user/0/');
            if (!file.startsWith(MEDIA_ROOT + '/')) return;
            fired = true;
            this.mediaWrite = true;
            send({event: 'admitted-media-write-blocked'});
            // Admission already succeeded. The host fills only this bounded tmpfs before the real syscall.
            recv('volume-filled', () => {}).wait();
        },
        onLeave(result) {
            if (this.mediaWrite) send({event: 'media-write-result', result: result.toInt32(), errno: this.errno});
        }
    });
} else {
    throw new Error('Unknown native fault mode');
}
send({event: 'armed', mode: mode});
