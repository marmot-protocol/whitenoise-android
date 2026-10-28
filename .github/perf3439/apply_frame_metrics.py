"""Apply audited, bounded diagnostic timing only; leave motion/admission and source ref unchanged."""
import hashlib,json,pathlib,re,sys
root=pathlib.Path(sys.argv[1]);out=pathlib.Path(sys.argv[2]);out.mkdir(exist_ok=True,parents=True)
base=root/'app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/media'
p=base/'MediaViewerEntryFrame.kt';original=p.read_text();assert 'Perf3439FrameMetrics' not in original
anchor='    val window = (view.parent as? DialogWindowProvider)?.window\n';assert original.count(anchor)==1
patched=original.replace(anchor,anchor+'    androidx.compose.runtime.DisposableEffect(window) {\n        val detach = window?.let { dev.ipf.whitenoise.android.perf3439.Perf3439FrameMetrics.attach(it) }\n        onDispose { detach?.invoke() }\n    }\n')
v=base/'MediaViewer.kt';viewer=v.read_text();assert 'Perf3439FrameMetrics' not in viewer
helper='dev.ipf.whitenoise.android.perf3439.Perf3439FrameMetrics'
window='    val diagnosticWindow = (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window\n'

def tokens(text):
    """Yield structural characters only, excluding Kotlin comments and quoted text."""
    i=0;mode=None;depth=0
    while i<len(text):
        if mode=='line':
            if text[i]=='\n':mode=None
            i+=1;continue
        if mode=='block':
            if text.startswith('/*',i):depth+=1;i+=2
            elif text.startswith('*/',i):
                depth-=1;i+=2
                if not depth:mode=None
            else:i+=1
            continue
        if mode=='triple':
            if text.startswith('"""',i):mode=None;i+=3
            else:i+=1
            continue
        if mode in ('"',"'"):
            if text[i]=='\\':i+=2
            elif text[i]==mode:mode=None;i+=1
            else:i+=1
            continue
        if text.startswith('//',i):mode='line';i+=2;continue
        if text.startswith('/*',i):mode='block';depth=1;i+=2;continue
        if text.startswith('"""',i):mode='triple';i+=3;continue
        if text[i] in ('"',"'"):mode=text[i];i+=1;continue
        yield i,text[i]
        i+=1

def body_bounds(text,name):
    marker='internal fun '+name+'(';assert text.count(marker)==1
    lo=text.index(marker)+len(marker)-1;parens=0;body=None;depth=0
    for rel,c in tokens(text[lo:]):
        i=lo+rel
        if body is None:
            if c=='(':parens+=1
            elif c==')':parens-=1
            elif c=='{' and parens==0:body=i;depth=1
        else:
            if c=='{':depth+=1
            elif c=='}':
                depth-=1
                if not depth:return body+1,i
    raise AssertionError('Unbalanced Kotlin function '+name)

def instrument(text,name,label,first,edit=lambda x:x,needs_window=False):
    lo,hi=body_bounds(text,name);body=text[lo:hi];assert body.count(first)==1
    at=body.index(first);prefix,rest=body[:at],body[at:];rest=edit(rest)
    wrapped=prefix+(window if needs_window else '')+'    '+helper+'.Phase("'+label+'") {\n'+''.join('    '+line if line.strip() else line for line in rest.splitlines(keepends=True))+'    }\n'
    return text[:lo]+wrapped+text[hi:]

def edit_pager(body):
    a='            modifier = pagerModifier,\n';b='            modifier = pagerModifier.then('+helper+'.layoutPhase(diagnosticWindow, "pager")),\n'
    assert body.count(a)==1;body=body.replace(a,b)
    a='                pageContent(pageDescriptor, pageDescriptor.key() == currentPageKey)\n'
    b='                '+helper+'.Phase("page-content") {\n    '+a+'                }\n'
    assert body.count(a)==1;return body.replace(a,b)

def wrap_slot(body,name,following):
    start='    val '+name+': @Composable () -> Unit = {\n';assert body.count(start)==1
    a=body.index(start)+len(start);end=body.index(following,a);part=body[a:end]
    assert part.endswith('    }\n')
    contents=part[:-6]
    label={'topChrome':'top-chrome','bottomChrome':'bottom-chrome'}[name]
    replacement='        '+helper+'.Phase("'+label+'") {\n'+''.join('    '+line if line.strip() else line for line in contents.splitlines(keepends=True))+'        }\n    }\n'
    return body[:a]+replacement+body[end:]

def edit_frame(body):
    for a,b in [('.fillMaxWidth()\n                        .onSizeChanged', '.fillMaxWidth()\n                        .then('+helper+'.layoutPhase(diagnosticWindow, "top-chrome"))\n                        .onSizeChanged'),('.fillMaxWidth()\n                        .testTag(MEDIA_VIEWER_BOTTOM_CHROME_TAG)', '.fillMaxWidth()\n                        .then('+helper+'.layoutPhase(diagnosticWindow, "bottom-chrome"))\n                        .testTag(MEDIA_VIEWER_BOTTOM_CHROME_TAG)'),('Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))', 'Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).then('+helper+'.layoutPhase(diagnosticWindow, "frame")))')]:
        assert body.count(a)==1,a;body=body.replace(a,b)
    body=wrap_slot(body,'topChrome','    // The body keeps one composition slot')
    body=wrap_slot(body,'bottomChrome','    Box(modifier.fillMaxSize()')
    return body

modified=instrument(viewer,'StableMediaViewerPager','pager','    val currentPageKey =',edit_pager,True)
modified=instrument(modified,'MediaViewerFrame','frame','    var moreExpanded',edit_frame,True)
modified=instrument(modified,'FullScreenMediaViewer','viewer-create','    val context = LocalContext.current')
for name in ('StableMediaViewerPager','MediaViewerFrame','FullScreenMediaViewer'):body_bounds(modified,name)
for name,before,after in [('frame-metrics-callsite',original,patched),('viewer-phase-callsite',viewer,modified)]:
    (out/(name+'-original.kt')).write_text(before);(out/(name+'-patched.kt')).write_text(after)
p.write_text(patched);v.write_text(modified)
(out/'frame-metrics-overlay.json').write_text(json.dumps({'diagnostic_only':True,'production_admission_unchanged':True,'composition_wrappers_and_layout_timing':True,'scope_labels':['viewer-create','frame','top-chrome','bottom-chrome','pager','page-content'],'source_files':{str(p.relative_to(root)):{'original_sha256':hashlib.sha256(original.encode()).hexdigest(),'patched_sha256':hashlib.sha256(patched.encode()).hexdigest()},str(v.relative_to(root)):{'original_sha256':hashlib.sha256(viewer.encode()).hexdigest(),'patched_sha256':hashlib.sha256(modified.encode()).hexdigest()}},'limits':['Instrumented diagnostic only; extra Compose groups/layout modifiers and timing calls perturb performance.','CPU and wall clocks are sampled sequentially, not atomically; nested spans overlap.','No admission/delay/pixel/gesture/reference-selection change is intentional.']},indent=2)+'\n')
