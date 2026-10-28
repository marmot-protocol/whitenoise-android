"""Apply one audited diagnostic callsite without changing the checked-out source ref."""
import hashlib,json,pathlib,sys
root=pathlib.Path(sys.argv[1]);out=pathlib.Path(sys.argv[2]);out.mkdir(exist_ok=True,parents=True)
p=root/'app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/media/MediaViewerEntryFrame.kt';original=p.read_text()
assert 'Perf3439FrameMetrics' not in original
anchor='    val window = (view.parent as? DialogWindowProvider)?.window\n';assert original.count(anchor)==1
patched=original.replace(anchor,anchor+'    androidx.compose.runtime.DisposableEffect(window) {\n        val detach = window?.let { dev.ipf.whitenoise.android.perf3439.Perf3439FrameMetrics.attach(it) }\n        onDispose { detach?.invoke() }\n    }\n')
p.write_text(patched)
(out/'frame-metrics-callsite-original.kt').write_text(original)
(out/'frame-metrics-callsite-patched.kt').write_text(patched)
(out/'frame-metrics-overlay.json').write_text(json.dumps({'original_sha256':hashlib.sha256(original.encode()).hexdigest(),'patched_sha256':hashlib.sha256(patched.encode()).hexdigest(),'diagnostic_only':True,'production_admission_unchanged':True},indent=2)+'\n')
