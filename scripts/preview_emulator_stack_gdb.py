"""GDB-only sidecar: retain explicit frame fields, then detach with the original pending signal."""

from datetime import datetime, timezone
import json
import os
from pathlib import Path

import gdb


def write_receipt(path, value):
    """Publish bounded diagnostic metadata without exposing partial JSON to the driver."""
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, indent=2) + '\n')
    temporary.replace(path)


def collect():
    """Capture only the stopped thread's function, PC and module, preserving signal delivery on detach."""
    pid = int(os.environ['STACK_PID'])
    report = Path(os.environ['STACK_REPORT'])
    ready = Path(os.environ['STACK_READY'])
    facts = {'pid': pid, 'frames': [], 'signal_stop_is_not_termination': True}
    stopped = {}

    def on_stop(event):
        """Record only GDB's signal identifier; no target values or arbitrary event output."""
        stopped['signal'] = getattr(event, 'stop_signal', None)

    def on_continue(event):
        """Confirm execution resumed after attach before the qualification driver touches the app."""
        if not ready.exists():
            write_receipt(ready, {'pid': pid, 'resumed': True})

    try:
        for command in ('set pagination off', 'set confirm off', 'set print frame-arguments none',
                        'set print entry-values no', 'set print thread-events off',
                        'handle SIGPIPE nostop noprint pass', 'handle SIGSEGV stop print pass',
                        'handle SIGBUS stop print pass', 'handle SIGABRT stop print pass'):
            gdb.execute(command, to_string=True)
        gdb.execute(f'attach {pid}', to_string=True)
        gdb.events.stop.connect(on_stop)
        gdb.events.cont.connect(on_continue)
        gdb.execute('continue', to_string=True)
        signum = stopped.get('signal')
        facts['signal'] = signum
        facts['observed_at'] = datetime.now(timezone.utc).isoformat()
        if signum in {'SIGSEGV', 'SIGBUS', 'SIGABRT'}:
            faulting_thread = gdb.selected_thread()
            frame = gdb.newest_frame()
            for _ in range(24):
                if frame is None:
                    break
                pc = frame.pc()
                module = gdb.solib_name(pc) or gdb.current_progspace().filename
                facts['frames'].append({'function': (frame.name() or '<unknown>')[:256],
                                        'pc': hex(pc), 'module': Path(module).name if module else None})
                frame = frame.older()
            facts['truncated'] = frame is not None
            faulting_thread.switch()
            gdb.execute('queue-signal ' + signum, to_string=True)
    except KeyboardInterrupt:
        facts['cleanup_interrupt'] = True
    except BaseException as error:
        # Error text and raw GDB output can contain target values, so retain only its type.
        facts['error_type'] = type(error).__name__
    finally:
        if gdb.selected_inferior().pid:
            try:
                gdb.execute('detach', to_string=True)
                facts['detached'] = True
            except BaseException as error:
                facts['detach_error_type'] = type(error).__name__
        write_receipt(report, facts)


collect()
