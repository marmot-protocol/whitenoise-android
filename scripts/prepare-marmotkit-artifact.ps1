$ErrorActionPreference = "Stop"

$pythonCommand = if ($env:WHITENOISE_PYTHON) { $env:WHITENOISE_PYTHON } else { "python" }
if (-not (Get-Command $pythonCommand -ErrorAction SilentlyContinue)) {
    [Console]::Error.WriteLine(
        "error: Python 3 executable '$pythonCommand' was not found; set WHITENOISE_PYTHON to an executable name or absolute path."
    )
    exit 1
}

& $pythonCommand @args
exit $LASTEXITCODE
